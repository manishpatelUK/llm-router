# llm-router

A lightweight, embeddable library — implemented per-language — that gives an application a single, stable entry point for calling any supported LLM provider, with automatic provider/model fallback, capability-aware request adaptation, and optional cost-aware model selection.

Design priorities, in order:
1. **Trivially easy to adopt** — add the dependency, instantiate one accessor object, call it with a prompt.
2. **Resilient by default** — if a provider or model fails or is unavailable, the router tries the next one without the caller having to handle it.
3. **Capability-safe** — the router never sends a request feature a model can't handle; it adapts or drops, and tells the caller what it did.
4. **Cost-aware, optionally** — callers who care about spend can opt into cheapest-first routing.
5. **Long-lived, reusable config** — a router config object is built once (with sensible defaults) and reused across many calls, not rebuilt per request.

**Status:** the design spec, a seed model capability table, and a full Java implementation exist. Further language implementations will follow the same spec.

## Supported providers

Anthropic (Claude), OpenAI, Perplexity, NVIDIA (NIM), Hugging Face, and OpenRouter — each behind a common adapter, so calling code never needs to know which one actually served a request.

## Language implementations

- **[Java](java/README.md)** — install/usage instructions in its own README.
- Future language implementations (Python, TypeScript, Go, ...) will be listed here as they're built, each getting its own top-level folder alongside `java/` with its own README and `.gitignore`.

## Repository layout

- **`LIBRARY_SPEC.md`** — the language-agnostic design specification. This is the source of truth for what every language implementation should do; each per-language folder implements it in that language's own idiom.
- **`model-capability-table.json`** — seed data for the router's Model Capability Table (`LIBRARY_SPEC.md` §7): per-model pricing, `thinkingScore`/`speedScore`, context window, and capability flags, across Anthropic, OpenAI, Perplexity, NVIDIA, and OpenRouter (Hugging Face is intentionally excluded — see below).
- **`MODEL_CAPABILITY_HEURISTICS.md`** — the methodology behind the numbers in the capability table: data sources, the `thinkingScore`/`speedScore` scoring heuristic, and estimation fallbacks. Read this before hand-editing the table.
- **`java/`** — the [Java/Maven implementation](java/README.md).
- Future language implementations get their own top-level folder alongside `java/`, each with its own `.gitignore`.

## How it works

1. Bring the library into your project and instantiate the router client — zero required arguments. It lazily detects available provider credentials from environment variables on first use.
2. Call it with a prompt (plus, optionally, history, system instructions, tools, a structured-output schema, file attachments, and/or a `RouterConfig`).
3. The router resolves an ordered list of `(provider, model)` candidates — from your config, or a computed default of whichever providers it found credentials for — and tries them in order. Before each attempt it checks that model's entry in the Model Capability Table and adapts the request to what it actually supports (dropping or falling back on anything it can't send, and reporting that back to you). If a candidate fails, it moves to the next one automatically.
4. You get back a unified response — the text, structured output, any tool calls, which provider/model actually served it, token usage and estimated cost, what (if anything) got dropped, and the full list of attempts if it didn't succeed on the first try.

Optionally, `costOptimized: true` on a `RouterConfig` changes how ambiguous routing (a provider given without an exact model) resolves: instead of one model per provider, the router gathers every model that qualifies for the requested capability tier and tries the cheapest one first.

This is a deliberately short summary — see [`LIBRARY_SPEC.md`](LIBRARY_SPEC.md) for the full, authoritative behavior spec (capability negotiation, the model-selection heuristic, cost-optimized ordering, credential detection, error codes, and the canonical enum values every language implementation must share).

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
