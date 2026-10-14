"""Tuning harness: replay real server chat logs through the exact gate + prompt the live
service uses, without touching the live service's logs or player profiles.

  docker exec mc-translator python tune.py /logs/2026-09-28-1.log.gz /logs/latest.log ...
  (mount or docker cp the log files in; plain .log and .log.gz both work)

Writes /data/tune/report-<timestamp>.tsv (every chat line: gate decision, confidences,
translation, backend, latency) and prints a summary: how much chat is non-English, the
translations/minute it would have needed at peak, and latency percentiles.

--regex overrides the chat-line pattern (needs named groups time, name, msg) for servers
whose chat format isn't vanilla "<Name> message".
"""
import argparse, collections, gzip, os, re, statistics, sys, tempfile, time
from datetime import datetime

os.environ["DATA_DIR"] = tempfile.mkdtemp(prefix="tune-")   # keep live logs/profiles clean
import app  # noqa: E402  (reads DATA_DIR at import)

DEFAULT_RE = (r"^\[?(?P<time>\d{2}:\d{2}:\d{2})[^\]]*\].*?:\s+(?:\[Not Secure\]\s+)?"
              r"<(?P<name>[^>]{1,40})>\s(?P<msg>.+)$")
ANSI = re.compile(r"\x1b\[[0-9;]*[A-Za-z]|§[0-9a-fk-or]", re.I)


def lines(path):
    op = gzip.open if path.endswith(".gz") else open
    with op(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            yield ANSI.sub("", line.rstrip("\n"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("logs", nargs="+")
    ap.add_argument("--regex", default=DEFAULT_RE)
    ap.add_argument("--no-translate", action="store_true", help="gate only (fast)")
    ap.add_argument("--context", type=int, default=3)
    a = ap.parse_args()
    rx = re.compile(a.regex)

    chats = []  # (logfile, time, name, msg)
    for p in a.logs:
        day = os.path.basename(p)[:10]
        for l in lines(p):
            m = rx.search(l)
            if m:
                chats.append((day, m["time"], m["name"].strip(), m["msg"].strip()))
    if not chats:
        sys.exit("no chat lines matched — pass --regex for this server's chat format")

    out_dir = "/data/tune"
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"report-{datetime.now():%Y%m%d-%H%M%S}.tsv")
    per_min = collections.Counter()
    lat, reasons, langs, backends = [], collections.Counter(), collections.Counter(), collections.Counter()
    recent = collections.deque(maxlen=a.context)
    with open(out, "w") as f:
        f.write("day\ttime\tname\tmessage\tdecision\treason\tlang\ten_conf\ttranslation\tbackend\tms\n")
        for i, (day, t, name, msg) in enumerate(chats, 1):
            src, reason, det = app.decide(msg, "name:" + name)
            reasons[reason] += 1
            tr, backend, ms = "", "", ""
            if src:
                langs[src] += 1
                per_min[(day, t[:5])] += 1
                if not a.no_translate:
                    t0 = time.perf_counter()
                    tr, backend = app.translate(src, msg, name, [{"name": n, "text": x} for n, x in recent])
                    ms = round((time.perf_counter() - t0) * 1000)
                    if tr and app.difflib.SequenceMatcher(None, app.norm(tr), app.norm(msg)).ratio() >= 0.8:
                        backend += " (suppressed: same as original)"
                    lat.append(ms)
                    backends[backend] += 1
            recent.append((name, msg))
            en = (det.get("conf") or {}).get("en", "")
            f.write("\t".join(map(str, [day, t, name, msg.replace("\t", " "), "translate" if src else "skip",
                                        reason, src or "", en, (tr or "").replace("\t", " "), backend, ms])) + "\n")
            if i % 50 == 0:
                print(f"  {i}/{len(chats)} lines", flush=True)

    n = len(chats)
    nt = sum(langs.values())
    print(f"\nreport: {out}")
    print(f"chat lines: {n}   to translate: {nt} ({100 * nt / n:.1f}%)   by language: {dict(langs)}")
    print(f"gate reasons: {dict(reasons)}")
    if per_min:
        peak = per_min.most_common(3)
        print(f"peak translations/minute: {[(f'{d} {m}', c) for (d, m), c in peak]}")
    if lat:
        q = statistics.quantiles(lat, n=20)
        print(f"latency ms: median {statistics.median(lat):.0f}  p95 {q[18]:.0f}  max {max(lat)}   backends: {dict(backends)}")


if __name__ == "__main__":
    main()
