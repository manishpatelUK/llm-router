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

**Easiest way:** in Claude Code, run the `update-model-capability-table` skill. It supports a few modes:

```
/update-model-capability-table
```
Full refresh: re-researches current pricing/lineups for every provider already in the table, re-scores changed or new models using the documented heuristic, removes any model that's been deprecated/retired, and rewrites `model-capability-table.json`.

```
/update-model-capability-table add openrouter/qwen-3-max
```
Add mode: researches and scores only the named model(s) and inserts them, without touching the rest of the table.

```
/update-model-capability-table provider:openai
```
Provider refresh: runs the full refresh procedure (research, re-score, deprecation check), scoped to just one provider.

```
/update-model-capability-table prune
```
Prune mode: scans every provider for deprecated/retired models and removes them — no re-pricing, no additions.

Every run (except a no-op) rewrites the file via a small local Node script that de-duplicates `models` by `provider`+`model` (keeping the last entry for any collision) and stamps every row's `lastUpdated` with the current UTC timestamp, so the table both stays free of duplicate rows and always tells you how fresh each individual entry is — not just the file as a whole.

**To update by hand:** read `MODEL_CAPABILITY_HEURISTICS.md` first, then edit `model-capability-table.json` directly, keeping the exact field shape defined in `LIBRARY_SPEC.md` §7.1 (including each row's own `lastUpdated` ISO 8601 UTC timestamp) and bumping the top-level `lastUpdated` field to match.
