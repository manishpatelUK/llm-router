# Changelog

Changes that affect callers of `llm-router`, newest first. Each entry says what changed and what, if
anything, calling code needs to do. The full behavior is in [`LIBRARY_SPEC.md`](LIBRARY_SPEC.md).

## Java 1.0.14 (unreleased)

### Prompt caching that agent loops actually read back, on every provider

1.0.12's Anthropic markers put the conversation's cache entry on the prompt, which an agent loop
replaces on every call, so later calls read back only the tools and system instructions and wrote
the whole conversation to the cache again, at 1.25× the input price, every step.

- **Anthropic: up to four markers, never on the prompt** (`LIBRARY_SPEC.md` §4.3): the last tool,
  the system instructions, the history's `SYSTEM` turns (now a second system block), and the last
  block of the last history message (text, image, document, `tool_use` or `tool_result`). The
  prompt is unmarked whenever there's a conversation. A history ending in a marked message is sent
  as content blocks rather than a plain string; nothing else about the wire shape changes.
- **What callers should do** to be read from the cache on every provider: keep each call's
  system instructions, tools and history the same as the previous call's, adding turns only at the
  end, and put whatever changes per call in the prompt. llm-agent-loop 1.0.10 does this.
- **`Message.attachments`** (USER messages only): a file shown earlier in a conversation goes on its
  history message, so it's cached with the conversation instead of being re-sent with every prompt.
  Negotiated like `Request.attachments`: `DroppedAttachment.index` now counts history attachments
  first (unchanged when there are none). Every adapter sends them. New `Message.user(text, attachments)`
  and `Message.toBuilder()`; `Request.everyAttachment()` and `hasAttachments()` see both kinds.
  Attachments on any other role are refused with `IllegalArgumentException`.
- **`RouterConfig.promptCacheTtl`** (`DEFAULT` or `LONG`): `LONG` asks Anthropic for its one-hour
  cache. New `Usage.cacheWriteLongTtlTokens`, priced at the long-lifetime write rate.
- **OpenAI cache writes are reported**: GPT-6 bills them, and `Usage.cacheWriteTokens` now carries
  them, so they're priced at the write rate rather than as plain input.

### OpenAI: the Responses API, so GPT-6 can call tools with reasoning on

The OpenAI adapter called Chat Completions, which refuses function tools on GPT-6 models while
reasoning is on — and they reason by default — so any request with tools to a GPT-6 model failed
with a 400, with or without a thinking level. The adapter now calls the Responses API
(`/v1/responses`), which takes both. Nothing changes for callers: same `OpenAiAdapter`
constructors, same `Request`/`Response`, same usage fields.

- **Stateless, with no copy kept by OpenAI**: each request carries the whole conversation and sets
  `store: false` (the Responses API keeps responses by default; Chat Completions didn't).
  OpenAI's own prefix caching works as before, and cache reads and writes are still reported.
- **Tools and response schemas are sent with `strict: false`**, as before in effect: the Responses
  API makes function tools strict by default, which would demand every property be required.
- **Wire changes**: `instructions` instead of a system message, `max_output_tokens` instead of
  `max_completion_tokens`, `reasoning.effort` instead of `reasoning_effort`, history tool calls
  and results as `function_call`/`function_call_output` items. An assistant turn that only called
  tools no longer sends an empty text message.
- **An explicit cache breakpoint at the end of the conversation** (`LIBRARY_SPEC.md` §4.3):
  OpenAI's implicit breakpoint sits at the end of each request, so in a tool loop each step's cache
  entry ended with that step's prompt and only the first call's prefix was ever read back. The last
  user message or tool result now carries `prompt_cache_breakpoint`; off with `promptCaching(false)`.
  Measured on gpt-6-luna, 4 tool steps: reads grew 1,718 → 2,910 → 4,102 tokens instead of staying
  at 1,716, writes stayed at 1,204 per step instead of growing to 3,588, and the run cost 34% less
  (the last step 59% less).
- **Not yet**: the model's reasoning items aren't passed back between tool steps (`ROADMAP.md`).

### Prices

- **Cache prices on every row of the seed table**, from each provider's pricing page: Anthropic's
  differ by model (reads 0.025× on Fable 5.1, 0.05× on Opus 5.5 and Sonnet 5.5, 0.1× on Haiku 5.5),
  so cached Opus 5.5 calls were over-estimated 2× before; OpenAI, Perplexity and OpenRouter cached
  input was priced at the full input rate. A provider with no discount has its input price as the
  read price.
- **New table fields** `cacheWriteLongTtlCostPerMillionTokens`, `longContextThresholdTokens`,
  `longContextInputMultiplier`, `longContextOutputMultiplier` (§7.1): Claude Haiku 5.5 bills prompts
  over 100,000 tokens at 5×, and GPT-6 over 272,000 tokens at 2× input and 1.5× output; estimates now
  follow.
- **Re-priced**: OpenRouter `deepseek/deepseek-v4-pro-0813` ($1.32 / $3.96) and
  `deepseek/deepseek-v4.1-flash` ($0.02 / $1.00), as OpenRouter now lists them. Every row re-verified
  on 2026-10-26.
- **The table-update skill** now requires cache and long-context prices on every row and checks
  Anthropic's against the live pricing page; a table test fails the build when a row has none.

## Java 1.0.12 (2026-10-20)

### Prompt caching, cached and reasoning tokens, usage listener

- **Prompt caching on Anthropic** (`LIBRARY_SPEC.md` §4.3). New `RouterConfig.promptCaching`, on by
  default. The Anthropic adapter now marks the system instructions (or the last tool definition)
  and, when there's history, the end of the conversation for Anthropic's prompt cache, so agent
  loops and multi-turn conversations read the repeated prefix at a tenth of the input price.
  Cache writes cost 1.25 times the input price, so a long prompt that's never reused costs a
  little more than before. Set `promptCaching(false)` to send no cache markers. OpenAI and the
  other providers cache on their own, as before.
- **Fix: five or more attachments on Anthropic.** Every attachment used to carry its own cache
  marker, and Anthropic rejects more than four in a request. Now only the last attachment does.
- **`Usage.cacheReadTokens` and `cacheWriteTokens`**, filled from Anthropic, OpenAI, Perplexity
  and OpenAI-compatible providers (OpenRouter, NVIDIA, Hugging Face) where they report them.
  **`Usage.reasoningTokens`** is now filled for OpenAI and OpenAI-compatible providers too.
- **Anthropic `inputTokens` now include cached tokens.** Anthropic reports cached tokens apart from
  `input_tokens`, and the adapter used to pass that through, so `inputTokens` left them out. It now
  counts every input token, as it does for every other provider. If you sum Anthropic
  `inputTokens`, expect higher numbers on calls that hit the cache.
- **Cached tokens are priced at the cache rates.** New optional table fields
  `cacheReadCostPerMillionTokens` and `cacheWriteCostPerMillionTokens` (§7.1). Without them,
  Anthropic rows use 0.1× and 1.25× the input price, and other providers the full input price.
  So estimates for cached Anthropic calls drop, and others are unchanged until the table has
  their cache prices.
- **`UsageListener`** (§5.6). `new LlmRouter().withUsageListener(listener)` reports every attempt
  (`AttemptUsage`: provider, model, outcome, retries, latency, usage) and every call (`CallUsage`:
  outcome, who answered, total latency, usage, the attempts, the failure) for sync, async,
  streaming and embedding calls. A listener that throws is logged and ignored.

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
- **The SDKs no longer retry on their own.** The Anthropic and OpenAI clients the adapters build
  used to retry a failed request twice (the SDKs' default) before the router saw it. They now make
  one request, and retrying is left to `maxRetries`. With the default `maxRetries` of 0, a rate
  limit or server error on those providers now falls back to the next candidate at once. To keep
  retrying on the same model, set `maxRetries(2)`. A client you build yourself and pass to
  `new AnthropicAdapter(client)` or `new OpenAiAdapter(client)` is unchanged.

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
