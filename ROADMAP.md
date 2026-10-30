# Roadmap

Planned work that isn't implemented yet. Each item says what's missing and the direction agreed so
far. Remove an item once it ships. Items within a section are roughly in priority order.

## Resilience

- **Spend cap per call.** `costOptimized` orders by price but nothing stops an expensive call.
  Direction: `RouterConfig.maxCostUsd`: skip any candidate whose pre-call estimate (§5.3) exceeds
  it, with a reason in `attempts`. It caps the estimate, not the actual bill. The input side of the
  estimate is already computed for every candidate. The weak part is output, which is
  `maxOutputTokens` or a flat 1,000 tokens, so a cap is only tight when callers set
  `maxOutputTokens`. Reasoning tokens and Perplexity's per-search fees aren't in the estimate at all.

## Cost and usage

- **OpenAI 12-hour cache retention.** The adapter marks the end of the conversation with an
  explicit breakpoint (§4.3), at OpenAI's default `30m` lifetime; the table already carries the
  12-hour write price. Direction: map `PromptCacheTtl.LONG` to the longer lifetime once
  `prompt_cache_options.ttl` accepts it (the SDK documents `30m` as the only value today).
- **OpenAI reasoning items between steps.** The OpenAI adapter is stateless (`store: false`) and
  doesn't send the model's reasoning items back with its function calls, since `Message` has
  nowhere to keep them, so a GPT-6 model reasons afresh on each tool step. OpenAI says passing them
  back (`include: ["reasoning.encrypted_content"]`) improves tool-using runs and reuses cached
  reasoning. Direction: an opaque, provider-specific field on assistant `Message`s that the adapter
  fills and replays, and that llm-agent-loop stores with the conversation.
- **Prompt caching through OpenRouter for Claude.** OpenRouter passes Anthropic `cache_control`
  through for Claude models, but the OpenAI-compatible adapter sends none, so Claude reached
  through OpenRouter isn't cached. Direction: the same breakpoints as the Anthropic adapter
  (§4.3), on OpenRouter requests to `anthropic/*` models.
- **Micrometer and OpenTelemetry adapters for `UsageListener`.** Optional modules that turn the
  listener's events (§5.6) into standard metrics and spans, so callers don't write the mapping.
  Separate artifacts, so the core stays dependency-light.

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
- **Decision models (Jev and similar).** Models such as TypeSafe AI's Jev don't write text: they
  pick one of the answers the caller defines and report a probability for each. They're much
  cheaper and faster than an LLM for yes/no, routing and scoring questions. The router assumes
  every model writes text: `Request` has nowhere to put the answers, `Response` has nowhere to put
  the probabilities, and nothing in the table stops a plain text request being routed to such a
  model. Direction: `Request.choices` (labels, optional descriptions) and `Response.choice` +
  `choiceScores`, plus a §7.1 capability that marks a model as choice-only. Text requests skip those
  models. A choices request sent to an LLM goes out as a one-field `enum` `responseSchema` and returns
  `choice` without scores, so a route of Jev then Haiku falls back cleanly. Needs a new provider id
  (§12.1), and a per-call price in the table if the provider bills per call rather than per token.
  Check the real API first: Jev is in early access, and its published speed and cost figures are
  the vendor's own.

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
