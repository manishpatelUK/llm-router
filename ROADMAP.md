# Roadmap

Planned work that isn't implemented yet. Each item says what's missing and the direction agreed so
far. Remove an item once it ships. Items within a section are roughly in priority order.

## Resilience

- **Timeouts and transient retries.** There's no way to bound how long an attempt (or a whole call)
  takes, and no retry on a 429/5xx before moving to the next candidate. A hung provider holds the
  caller until the HTTP client gives up. Direction: `RouterConfig.attemptTimeout` and `callTimeout`
  (a deadline across all attempts, so fallback can't run unbounded), plus one retry on 429/503 that
  honours `Retry-After` when it's short. §11 already allows the retry. A timeout becomes a `FAILED`
  attempt with a reason, so fallback works as for any other failure.
- **Provider cooldown (circuit breaker).** Every call re-tries a provider that has just failed for
  every caller, adding its timeout to each request during an outage. Direction: the router remembers
  repeated failures (auth errors, 5xx, timeouts) per provider and model for the life of the
  `LlmRouter`. It skips that candidate for a short, growing cooldown and records it as `SKIPPED`
  with a reason such as `cooling down after 3 failures`. Opt-out via config. Never skip the last
  remaining candidate.
- **Spend cap per call.** `costOptimized` orders by price but nothing stops an expensive call.
  Direction: `RouterConfig.maxCostUsd`: skip any candidate whose pre-call estimate (§5.3) exceeds
  it, with a reason in `attempts`. It caps the estimate, not the actual bill.

## Cost and usage

- **Prompt caching.** Only attachments get Anthropic `cache_control`. Long system prompts, tool
  definitions and history, which agent loops resend every turn, aren't cached, so callers pay full
  input price each time. Direction: cache the stable prefix (system, tools, earlier history) on
  Anthropic automatically, with a config switch to turn it off. OpenAI and others cache
  automatically. Report it either way (next item).
- **Cached and reasoning tokens in `Usage`.** `Usage` has no cached-input count, and
  `reasoningTokens` isn't filled by the adapters. So cost estimates overstate cached calls and
  understate reasoning-heavy ones. Direction: add `cacheReadTokens` and `cacheWriteTokens`, fill
  all three from each provider's usage block, and add cached-input prices to the capability table
  (§7.1) so `estimatedCostUsd` uses them.
- **Usage listener for metrics.** Tracking spend or latency means wrapping every call site.
  Direction: an optional listener on `LlmRouter`, called once per attempt with provider, model,
  outcome, latency, tokens and cost (and once per call with the total). Callers wire it to
  Micrometer, OpenTelemetry or their own logs. A Micrometer/OpenTelemetry adapter can come later
  as an optional module, so the core stays dependency-light.

## Request features

- **Tool choice.** No way to force a tool, forbid tools, or require some tool call, which agent
  loops and extraction-by-tool rely on. Direction: `Request.toolChoice` (`auto` | `none` |
  `required` | a tool name), mapped per adapter. A model that can't honour it drops it and reports
  `"toolChoice"` in `droppedFeatures` (new §12.7 value). Parallel tool calls on/off sits alongside.
- **Stop sequences and seed.** Common sampling options missing from `RouterConfig`. Direction:
  `stopSequences` and `seed`, negotiated and dropped like `temperature` (§4).
- **Streaming tool calls and reasoning.** `StreamListener` gets text only. Tool calls and reasoning
  arrive at the end. Direction: optional `onToolCall` (when a call's arguments are complete) and
  `onReasoning` (summary/thinking text, where the provider exposes it) callbacks with no-op
  defaults, so existing listeners keep working. Also expose final reasoning text on `Response`.
- **Provider-run tools.** Designed in `LIBRARY_SPEC.md` §13, not implemented. Covers web search,
  web fetch and code execution on Anthropic and OpenAI, and switching Perplexity's search on only
  when asked. OpenAI's hosted tools need its adapter moved to the Responses API first (§13.2).

## Providers

- **Google Gemini directly.** Gemini is reachable only through OpenRouter, which adds a markup and
  another point of failure. Direction: a native Gemini adapter (`GEMINI_API_KEY`/`GOOGLE_API_KEY`)
  with its own table rows, thinking-budget control and file input.
- **Cloud-hosted Claude and GPT (Bedrock, Vertex AI, Azure OpenAI).** Many companies can only call
  models through their cloud account. Direction: adapters that reuse the Anthropic and OpenAI
  adapters' request mapping with cloud authentication and region/deployment settings. Table rows key
  by the provider id, so prices can differ from the direct API.
- **Local models (Ollama, any OpenAI-compatible server).** No way to point the router at a local or
  self-hosted endpoint, which is useful for development, privacy and as a free last fallback.
  Direction: a configurable OpenAI-compatible provider with a base URL and optional key, on the
  existing `OpenAiCompatibleHttpAdapter`. Unknown models get conservative capabilities unless
  registered.

## Developer experience

- **Test double.** Testing code that calls the router means writing a `ProviderAdapter`. Direction:
  a public fake (`FakeLlmRouter` or a scripted adapter) that returns queued responses or failures
  and records the negotiated requests it got, so callers can assert on fallback and dropped
  features without network calls.
- **Typed structured output.** `structuredOutput` is a generic object. Direction (Java): `complete(
  request, MyRecord.class)` that derives the `responseSchema` from a record and returns the parsed
  instance, failing the attempt if the reply doesn't bind.
- **Capability table updates without a release.** The table ships inside the jar, so new prices and
  models wait for a library release. Direction: let callers load an override file or URL at startup,
  merged over the seed by `provider`+`model`, and validated against §7.1 before use.
- **Spring Boot starter.** Auto-configure an `LlmRouter` bean and named `RouterConfig`s from
  `application.yml`. A separate artifact, so the core has no Spring dependency.

## Other languages

- **Python and TypeScript implementations.** The spec is language-agnostic and only Java exists.
  Python first (largest LLM user base), then TypeScript, each in its own top-level folder per the
  README, sharing `model-capability-table.json` and the §12 enum values.
