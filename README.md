# llm-router

A lightweight, embeddable library — implemented per-language — that gives an application a single, stable entry point for calling any supported LLM provider, with automatic provider/model fallback, capability-aware request adaptation, and optional cost-aware model selection.

**Status:** early stage / work in progress. The design spec and a seed model capability table exist; language implementations are being built out one at a time (Java first).

## Repository layout

- **`LIBRARY_SPEC.md`** — the language-agnostic design specification. This is the source of truth for what every language implementation should do; each per-language folder implements it in that language's own idiom.
- **`model-capability-table.json`** — seed data for the router's Model Capability Table (`LIBRARY_SPEC.md` §7): per-model pricing, `thinkingScore`/`speedScore`, context window, and capability flags, across Anthropic, OpenAI, Perplexity, NVIDIA, and OpenRouter (Hugging Face is intentionally excluded — see below).
- **`MODEL_CAPABILITY_HEURISTICS.md`** — the methodology behind the numbers in the capability table: data sources, the `thinkingScore`/`speedScore` scoring heuristic, and estimation fallbacks. Read this before hand-editing the table.
- **`java/`** — the Java/Maven implementation (in progress).
- Future language implementations get their own top-level folder alongside `java/`, each with its own `.gitignore`.

## Updating the model capability table

Pricing and model lineups go stale fast — that's expected, and the table is designed to be refreshed, not treated as a one-time snapshot.

**Easiest way:** in Claude Code, run:

```
/update-model-capability-table
```

This re-researches current pricing/lineups per provider, re-scores changed or new models using the documented heuristic, and rewrites `model-capability-table.json`.

**To update by hand:** read `MODEL_CAPABILITY_HEURISTICS.md` first, then edit `model-capability-table.json` directly, keeping the exact field shape defined in `LIBRARY_SPEC.md` §7.1 and bumping the `lastUpdated` field.
