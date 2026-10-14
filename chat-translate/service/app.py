"""mc-translator: one-way (non-English -> English) chat translation service for the
ChatTranslate plugin.

POST /translate  {"uuid","name","text","context":[{"name","text"}...]}
  -> {"action":"skip","reason":...}
  -> {"action":"translate","lang":"sv","translation":"...","backend":"llm|libre|cache","ms":123}
POST /detect     same body, gate decision only (no translation) - used by the tuning tool
GET  /health
"""
import collections, difflib, json, logging, os, re, threading, time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import requests
from lingua import Language, LanguageDetectorBuilder

from prompt import SYSTEM_PROMPT

env = os.environ.get
PORT = int(env("PORT", "5100"))
BIND = env("BIND", "127.0.0.1")
LLM_URL = env("LLM_URL", "http://127.0.0.1:8081")   # llama.cpp llama-server (OpenAI-compatible)
MODEL = env("MODEL", "gemma-3-4b-it-qat-Q4_0")
LIBRE_URL = env("LIBRE_URL", "http://127.0.0.1:5000")
LLM_TIMEOUT = float(env("LLM_TIMEOUT", "8"))        # seconds before falling back to LibreTranslate
MAX_LLM_QUEUE = int(env("MAX_LLM_QUEUE", "2"))      # in-flight LLM requests before overflow goes to Libre
EN_SKIP = float(env("EN_SKIP", "0.75"))             # English confidence at/above which we pass through
EN_SKIP_ENGLISH_PLAYER = float(env("EN_SKIP_ENGLISH_PLAYER", "0.35"))  # same, for players who usually write English
MIN_CONF = float(env("MIN_CONF", "0.6"))            # confidence needed to learn a player's language
SHORT_WORDS = int(env("SHORT_WORDS", "2"))          # <= this many words = too short to detect reliably
DATA = env("DATA_DIR", "/data")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("mc-translator")

CODES = {"en": Language.ENGLISH, "sv": Language.SWEDISH, "es": Language.SPANISH, "fr": Language.FRENCH,
         "de": Language.GERMAN, "pt": Language.PORTUGUESE, "it": Language.ITALIAN, "nl": Language.DUTCH,
         "pl": Language.POLISH, "da": Language.DANISH, "no": Language.BOKMAL, "fi": Language.FINNISH,
         "ru": Language.RUSSIAN, "tr": Language.TURKISH}
LANGS = [c.strip() for c in env("LANGS", "en,sv,es,fr").split(",")]
L2C = {CODES[c]: c for c in LANGS}
NAMES = {"en": "English", "sv": "Swedish", "es": "Spanish", "fr": "French", "de": "German",
         "pt": "Portuguese", "it": "Italian", "nl": "Dutch", "pl": "Polish", "da": "Danish",
         "no": "Norwegian", "fi": "Finnish", "ru": "Russian", "tr": "Turkish"}
detector = LanguageDetectorBuilder.from_languages(*L2C).with_preloaded_language_models().build()

# Messages made only of these tokens are never translated (same in every language).
TRIVIAL = set("""gg ggs gl hf glhf wp ez lol lmao lmfao rofl xd xdd kek ok okay k kk ty thx tysm np yw brb afk
wb gn gm hi hey hello yo bye cya o7 f rip omg wtf wth bruh bro ya ye yes no nah haha hahaha jaja jajaja
mdr ptdr hehe :d :p <3 ? ?? ! !! ... idk ikr smh""".split())
URL_RE = re.compile(r"https?://\S+|www\.\S+")
WORD_RE = re.compile(r"[\w']+", re.UNICODE)


# ---------------------------------------------------------------- player profiles
class Profiles:
    """Remembers the languages each player writes in, from their longer, confidently
    detected messages, so their short messages ("KÄFTNE", "NÄHÄ") can still be handled."""
    def __init__(self, path):
        self.path, self.lock, self.dirty = path, threading.Lock(), False
        try:
            raw = json.load(open(path))
        except Exception:
            raw = {}
        self.d = {k: collections.deque(v, maxlen=20) for k, v in raw.items()}

    def record(self, uuid, lang):
        with self.lock:
            self.d.setdefault(uuid, collections.deque(maxlen=20)).append(lang)
            self.dirty = True

    def dominant(self, uuid, include_en=False):
        with self.lock:
            q = list(self.d.get(uuid, ()))
        if len(q) < 2:
            return None
        lang, n = collections.Counter(q).most_common(1)[0]
        if n / len(q) < 0.5 or (lang == "en" and not include_en):
            return None
        return lang

    def save(self):
        with self.lock:
            if not self.dirty:
                return
            data = {k: list(v) for k, v in self.d.items()}
            self.dirty = False
        tmp = self.path + ".tmp"
        json.dump(data, open(tmp, "w"))
        os.replace(tmp, self.path)


profiles = Profiles(os.path.join(DATA, "players.json"))


def decide(text, uuid):
    """English gate. Returns (source_lang or None, reason, details)."""
    stripped = URL_RE.sub("", text).strip()
    words = WORD_RE.findall(stripped.lower())
    if not words:
        return None, "no-words", {}
    if all(w in TRIVIAL for w in words):
        return None, "trivial", {}
    confs = {L2C[c.language]: round(c.value, 3) for c in detector.compute_language_confidence_values(stripped)}
    top = max(confs, key=confs.get)
    en = confs.get("en", 0.0)
    known = profiles.dominant(uuid) if uuid else None
    english_player = uuid and profiles.dominant(uuid, include_en=True) == "en"
    det = {"conf": confs, "profile": known or ("en" if english_player else None)}
    if len(words) > SHORT_WORDS and confs[top] >= MIN_CONF and uuid:
        profiles.record(uuid, top)
    if en >= EN_SKIP:
        return None, "english", det
    if english_player and en >= EN_SKIP_ENGLISH_PLAYER:
        # sloppy lowercase English scores low; players who normally write English get the benefit of the doubt
        return None, "english-player", det
    if len(words) <= SHORT_WORDS:
        # too short to trust the detector; only translate players we know write non-English
        return (known, "short-known-player", det) if known else (None, "short-unknown", det)
    best = max((c for c in confs if c != "en"), key=confs.get)
    if known and known != best and confs[best] < 0.5:
        best = known
    return best, "detected", det


# ---------------------------------------------------------------- translation backends
cache, cache_lock = collections.OrderedDict(), threading.Lock()
llm_inflight, llm_lock = 0, threading.Lock()
REFUSAL = re.compile(r"^(i'?m sorry|i can(no|')t|as an ai|sorry,? (but )?i)", re.I)
PREFIX = re.compile(r"^(english( translation)?|translation)\s*:\s*", re.I)


def clean(out, src_text):
    out = (out or "").strip()
    out = next((l.strip() for l in out.splitlines() if l.strip()), "")
    out = PREFIX.sub("", out).strip()
    if len(out) >= 2 and out[0] in "\"'“«" and out[-1] in "\"'”»":
        out = out[1:-1].strip()
    if not out or REFUSAL.match(out):
        return None
    if len(out) > max(3 * len(src_text), len(src_text) + 60):
        return None  # rambling / commentary
    return out


def llm(src, text, name, context, timeout=None):
    lines = []
    if context:
        lines.append("Recent chat (context only, do not translate):")
        lines += [f"{c.get('name', '?')}: {c.get('text', '')[:150]}" for c in context[-3:]]
        lines.append("")
    lines.append(f"Translate this {NAMES.get(src, src)} message from {name} into English:")
    lines.append(text)
    r = requests.post(f"{LLM_URL}/v1/chat/completions", timeout=timeout or LLM_TIMEOUT, json={
        "model": MODEL, "temperature": 0, "max_tokens": 120, "cache_prompt": True,
        "messages": [{"role": "system", "content": SYSTEM_PROMPT},
                     {"role": "user", "content": "\n".join(lines)}]})
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"]


def libre(src, text):
    r = requests.post(f"{LIBRE_URL}/translate", timeout=10,
                      json={"q": text, "source": src, "target": "en"})
    r.raise_for_status()
    return r.json()["translatedText"]


def translate(src, text, name, context):
    key = (src, text)
    with cache_lock:
        if key in cache:
            cache.move_to_end(key)
            return cache[key], "cache"
    global llm_inflight
    out, backend = None, "llm"
    with llm_lock:
        busy = llm_inflight >= MAX_LLM_QUEUE
        if not busy:
            llm_inflight += 1
    if busy:
        backend = "libre-overflow"
    else:
        try:
            out = clean(llm(src, text, name, context), text)
            if out is None:
                backend = "libre-badoutput"
        except requests.Timeout:
            backend = "libre-timeout"
        except Exception as e:
            log.warning("LLM error: %s", e)
            backend = "libre-llmerror"
        finally:
            with llm_lock:
                llm_inflight -= 1
    if out is None:
        try:
            out = clean(libre(src, text), text)
        except Exception as e:
            log.warning("Libre error: %s", e)
            return None, "failed"
    if out and backend == "llm":
        with cache_lock:
            cache[key] = out
            if len(cache) > 2000:
                cache.popitem(last=False)
    return out, backend


def norm(s):
    return " ".join(WORD_RE.findall(s.lower()))


# ---------------------------------------------------------------- logging
log_lock = threading.Lock()


def jlog(rec):
    rec["ts"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    os.makedirs(os.path.join(DATA, "logs"), exist_ok=True)
    path = os.path.join(DATA, "logs", f"translations-{rec['ts'][:10]}.jsonl")
    with log_lock, open(path, "a") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def handle(body, dry):
    text = (body.get("text") or "").strip()[:500]
    uuid, name = body.get("uuid") or "", body.get("name") or "?"
    t0 = time.perf_counter()
    src, reason, det = decide(text, uuid)
    res = {"action": "skip", "reason": reason, "detect": det}
    if src and not dry:
        out, backend = translate(src, text, name, body.get("context") or [])
        if out and difflib.SequenceMatcher(None, norm(out), norm(text)).ratio() < 0.8:
            res = {"action": "translate", "lang": src, "translation": out, "backend": backend}
        else:
            res = {"action": "skip", "reason": "same-or-failed", "backend": backend}
    elif src:
        res = {"action": "translate", "lang": src, "reason": reason, "detect": det}
    res["ms"] = round((time.perf_counter() - t0) * 1000)
    if not dry:
        jlog({"uuid": uuid, "name": name, "text": text, **{k: v for k, v in res.items() if k != "detect"},
              "reason": reason, "conf": det.get("conf")})
    return res


class H(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/health":
            return self._send(200, {"ok": True, "model": MODEL, "llm_inflight": llm_inflight})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path not in ("/translate", "/detect"):
            return self._send(404, {"error": "not found"})
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
            self._send(200, handle(body, dry=self.path == "/detect"))
        except Exception as e:
            log.exception("request failed")
            self._send(500, {"error": str(e)})

    def log_message(self, *a):
        pass


def background():
    # load the model + prime the cached system-prompt prefix, then save profiles periodically
    for _ in range(30):
        try:
            t = time.perf_counter()
            llm("sv", "hej", "warmup", [], timeout=300)
            log.info("model %s warm (%.1fs)", MODEL, time.perf_counter() - t)
            break
        except Exception as e:
            log.info("waiting for llm (%s)", e.__class__.__name__)
            time.sleep(10)
    while True:
        time.sleep(30)
        try:
            profiles.save()
        except Exception as e:
            log.warning("profile save failed: %s", e)


if __name__ == "__main__":
    threading.Thread(target=background, daemon=True).start()
    log.info("listening on %s:%d, langs=%s, model=%s", BIND, PORT, LANGS, MODEL)
    ThreadingHTTPServer((BIND, PORT), H).serve_forever()
