# Changelog

Changes that affect callers of `llm-router`, newest first. Each entry says what changed and what, if
anything, calling code needs to do. The full behavior is in [`LIBRARY_SPEC.md`](LIBRARY_SPEC.md).

## Unreleased (after Java 1.0.11)

### Timeouts, retries and cooldown

New, optional `RouterConfig` settings (`LIBRARY_SPEC.md` §5.5). All are off by default, so calling
code needs no change, and a config that sets none of them behaves exactly as before.

- **`attemptTimeout`** gives up on an attempt that takes too long and falls back to the next
  candidate. The attempt is recorded as failed, with the reason `attempt timed out after <duration>`.
- **`callTimeout`** bounds the whole call, across attempts, retries and fallback. When it runs out,
  the router throws `RouterExhaustedException` with the attempts made so far.
- **`maxRetries`** (default 0) and **`retryDelay`** (default 1s, doubling) retry a transient failure
  (HTTP 408/429/5xx/529 or an I/O error) on the same model before falling back. A provider's
  `Retry-After` is honoured up to 30 seconds. `AttemptRecord.retries` counts the retries made.
- **`cooldown`** (`CooldownPolicy.of(failures, duration)`): after that many failed attempts in a
  row, a model is skipped on later calls for `duration`, then its count resets.

**Java additions:**

- `ProviderAdapter.isTransient(Throwable)` and `retryAfter(Throwable)` are default methods, so
  existing custom adapters keep compiling.
- `ProviderHttpException.retryAfter()`.
- `HttpTransport.HttpResponseRecord` gains a `headers` component. Its two-argument constructor
  still works.

### Capability table refresh (2026-10-16)

- **Replaced:** `anthropic/claude-haiku-4-5-20251001` with `claude-haiku-5-5` ($0.10 / $0.50, 1M context,
  effort levels). Also `openrouter/google/gemini-3.1-pro-preview` with `google/gemini-4-argon` ($2 / $10,
  an introductory price that Google doubles on 1 December 2026). Both old models are still served:
  register them with `ModelCapabilityTable.registerModel(...)` if you route to them by name and want
  capability negotiation. No seeded model uses a thinking budget any more.
- **Added:** `openrouter/meta/muse-wonder`, Meta's new flagship. `meta/muse-spark-1.3` moves down to
  the mid tier (thinking score 8).
- **Re-priced (OpenRouter):** `google/gemini-3.8-flash`, `meta-llama/llama-3.3-70b-instruct`,
  `deepseek/deepseek-v4-pro-0813` and `deepseek/deepseek-v4.1-flash`.

### Perplexity moves to the Agent API (breaking for code that names Perplexity models)

Perplexity ended Sonar Chat Completions on 27 September 2026. The Perplexity adapter now calls
Perplexity's [Agent API](https://docs.perplexity.ai/docs/agent-api/quickstart) (`POST /v1/agent`).
See `LIBRARY_SPEC.md` §8.2.

**What calling code needs to change:**

- **Model ids.** `sonar` becomes `perplexity/sonar`. `sonar-pro` and `sonar-reasoning-pro` no longer
  exist: use `preset:fast` and `preset:low` (Perplexity's suggested replacements), or one of the
  seeded models below. The old ids aren't aliased. Perplexity rejects them, so the attempt fails and
  the router falls back to the next route entry.
- **Java: adapter package.** `PerplexityAdapter` moved from
  `io.github.manishpateluk.llmrouter.provider.compatible` to
  `io.github.manishpateluk.llmrouter.provider.perplexity`. This only matters if you construct
  adapters yourself; `new LlmRouter()` picks it up as before.

Code that routes with `Provider.PERPLEXITY` and no model, or that never uses Perplexity, needs no
change.

**What behaves differently:**

- **Seed table.** The Perplexity rows are now `perplexity/sonar`, `perplexity/kimi-k3`,
  `perplexity/glm-5.3`, `perplexity/glm-5.3-flash` and `perplexity/nemotron-3.5-super-120b-a12b`.
  All four non-Sonar models support tools, so `requiredFeatures(TOOLS)` no longer skips Perplexity
  outright. `perplexity/kimi-k3` takes a reasoning effort.
- **Other vendors through Perplexity.** Any Agent API model id (e.g. `openai/gpt-6-luna`) can be named
  in an explicit route entry. These ids have no seed rows; register them if you want capability
  negotiation and cost-ordering.
- **Presets.** A model string `preset:<name>` runs a Perplexity preset (`fast`, `low`, `medium`,
  `high`, `xhigh`).
- **Web search** is switched on for every Perplexity model call, so answers stay grounded in the web
  whichever model serves them. Each search costs about $0.0025 on top of tokens.
- **Cost.** `Response.usage` for a Perplexity call carries the cost Perplexity reports, including
  search fees, instead of the table's token-price estimate. Other providers are unchanged.
- **Citations** come from the Agent API's `search_results`, ordered by their id, so `[1]` (or
  `[web:1]`) still refers to the first citation. Whether the answer carries markers depends on the
  model: `perplexity/sonar` and the presets add them, other models may need asking.
- **Streaming** reads the Agent API's typed events. Token usage is now always reported on streamed
  Perplexity calls.
