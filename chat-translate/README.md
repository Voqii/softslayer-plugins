# ChatTranslate

Non-English chat gets a grey `↳ [SV] name: translation` line under it (hover shows the original). The original
message is delivered untouched. One way only, everything gets translated to English.

- `plugin/`: Paper plugin, `build.sh` → `dist/ChatTranslate-1.0.0.jar`. Config: `service-url`, format, context lines.
- `service/`: Python HTTP service on 127.0.0.1:5100. Language gate (lingua + per-player language profile),
  then a Gemma 3 4B prompt (`prompt.py`: slang glossary + examples), then LibreTranslate as fallback.
  `tune.py` replays real chat logs and writes a TSV report for tuning thresholds.
- `docker-compose.snippet.yml`: the three containers (llama.cpp server, translator, LibreTranslate) as they run
  next to the dev server. Model file: `gemma-3-4b-it-qat-Q4_0.gguf` (Google's QAT GGUF) in `./llm/`.

Why llama.cpp and not Ollama: Gemma 3's sliding-window attention means Ollama re-processes most of the ~800-token
system prompt every message (~16 s on CPU). llama-server with `--swa-full` reuses it, which gets it to 1–3.5 s on CPU.
Always keep `mem_limit` on the LLM container. An uncapped one OOM-killed the MC server once.
