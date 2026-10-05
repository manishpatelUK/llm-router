# LLM Router — Library Specification

> **Purpose of this document**: This is a language-agnostic design specification for the `llm-router` library. It is not a README for end users — it is context for generating idiomatic implementations of this library in any target language/framework (Java, Python, TypeScript, Go, C#, Rust, etc.). Every language implementation should conform to the concepts, contracts, and behaviors described here, expressed in that language's own idioms (naming conventions, overloads vs. keyword args vs. builder patterns, exception vs. error-return semantics, etc.).
>
> Do not treat any syntax examples below as literal code to copy — they are pseudocode illustrating shape and intent only.

---

## 1. What This Library Is

`llm-router` is a lightweight, embeddable library that gives an application a single, stable entry point for calling any supported LLM provider, with automatic provider/model fallback, capability-aware request adaptation, and optional cost-aware model selection.

Design priorities, in order:
1. **Trivially easy to adopt** — add the dependency, instantiate one accessor object, call it with a prompt.
2. **Resilient by default** — if a provider or model fails or is unavailable, the router tries the next one without the caller having to handle it.
3. **Capability-safe** — the router never sends a request feature a model can't handle; it adapts or drops, and tells the caller what it did.
4. **Cost-aware, optionally** — callers who care about spend can opt into cheapest-first routing.
5. **Long-lived, reusable config** — a router config object is built once (with sensible defaults) and reused across many calls, not rebuilt per request.

---

## 2. Standard Usage Pattern

1. Bring the library into the project (dependency/import per language convention).
2. Instantiate the accessor object (the "router client") — zero required arguments. Internally, on first use, it lazily detects available provider credentials from environment variables (see §6) and caches that detection for the process lifetime.
3. Call the router with a prompt. There are many call variants (see §3) — same underlying request, different amounts of optional structure supplied: chat history, system instructions, a structured-output schema, tool definitions, and/or a `RouterConfig`.
4. The router resolves a candidate list of (provider, model) pairs from the config (or the default config), attempts them in order, adapting the request per model's known capabilities, and returns a unified response.

---

## 3. Call Surface (conceptual)

Every call ultimately builds one **Request** object. Language implementations should offer this via whatever mechanism is idiomatic — true method overloading (Java/C#), optional/keyword arguments (Python), a fluent builder, or an options object (TypeScript/Go functional options). All variants funnel into the same underlying request shape:

```
Request {
  prompt: string                       // required — the latest user message
  history: Message[]?                  // optional prior turns: { role: "user"|"assistant"|"system"|"tool", content,
                                        // toolCalls?, toolCallId? } — role values per §12.5. toolCalls (ASSISTANT
                                        // only) and toolCallId (TOOL only) are optional correlation fields: when
                                        // present, the adapter sends the turn using the provider's native
                                        // tool-call/tool-result wire format; when absent, it falls back to a plain
                                        // flattened text turn (e.g. "Tool result: " + content) for that entry.
  systemInstructions: string?          // optional system/developer prompt
  responseSchema: Schema?              // optional structured-output schema (JSON-Schema-like)
  tools: ToolDefinition[]?             // optional tool/function definitions the model may call
  attachments: Attachment[]?           // optional files to include with the prompt (documents, images, etc.)
  config: RouterConfig?                // optional; falls back to the router's default config if omitted
  temperature: number?                 // resolved from RouterConfig.temperature (§5) during capability negotiation (§4) — not set directly by callers
  topP: number?                        // resolved from RouterConfig.topP (§5) during capability negotiation (§4) — not set directly by callers
}

ToolDefinition {
  name: string
  description: string
  parameters: Schema                   // JSON-Schema-like description of arguments
}

Attachment {
  mediaType: string                    // MIME type, e.g. "application/pdf", "image/png"
  data: bytes                          // raw file content — each language uses its own idiomatic binary type
                                        // (e.g. byte[] in Java); base64 is purely a wire-format detail internal
                                        // to each provider adapter, never part of this struct
  filename: string?                    // optional display name
}
```

The router does **not** execute tool calls itself — it only requests them from the model and returns them to the caller to execute. This keeps the library side-effect-free and embeddable in any application architecture.

### 3.1 Tool definition validation

Before routing — and so before any network call — the router validates `tools` against rules every supported provider enforces, and rejects the whole request with an `INVALID_REQUEST` error (§10, §12.8) if any are violated:

- `name` must match `^[a-zA-Z0-9_-]{1,64}$`.
- `name` must be unique within the request's `tools`.
- `parameters` must be a JSON Schema object: non-null, with `"type": "object"`. A tool that takes no arguments uses `{ "type": "object" }`.

The error message should list every violation (not just the first), identifying each tool by index and name. Without this check, a bad definition fails as a provider 400 on every candidate in turn, which is slow, costs a request per candidate, and surfaces only as an opaque exhaustion.

Schema keywords that some providers handle inconsistently (`oneOf`, `anyOf`, `$ref`) are deliberately **not** rejected or rewritten: every provider this library currently targets accepts them in tool parameters, and the Model Capability Table has no per-model flag to say otherwise. If a provider that rejects them is added, the right shape is a new capability flag plus negotiation (§4), not a blanket validation error.

### Response shape

Implementations should try and fill in as much of the shape as possible, and omit if not possible.

```
Response {
  content: string                      // primary text output
  structuredOutput: object?            // present if a responseSchema was honored natively or via fallback
  toolCalls: ToolCall[]?               // present if the model requested tool invocations
  providerUsed: string                 // canonical provider ID, §12.1 (e.g. "anthropic")
  modelUsed: string                    // provider-defined model id, §12.2 (e.g. "claude-opus-5")
  usage: {
    inputTokens: int
    outputTokens: int
    reasoningTokens: int
    estimatedCostUsd: int              // converted to cents to avoid floating point issues
  }
  droppedFeatures: string[]            // values per §12.7 (e.g. ["responseSchema", "tools"]) if the chosen model couldn't support them
  attempts: AttemptRecord[]            // one entry per candidate tried before success (empty if first candidate succeeded)
  generatedFiles: GeneratedFile[]?     // files the model produced (e.g. via code execution or image generation), if any
  original: {}                         // the raw original output from the provide, for convenience
}

AttemptRecord {
  provider: string                     // canonical provider ID, §12.1
  model: string
  outcome: "success" | "failed" | "skipped"   // §12.6
  reason: string?                      // error message or skip reason (e.g. "no API key detected")
}

GeneratedFile {
  mediaType: string
  filename: string?
  data: bytes?                         // raw file content, when the provider returns it inline
  url: string?                         // temporary download URL, when the provider returns a reference instead
                                        // of inline bytes — exactly one of data/url is populated
}
```

---

## 4. Capability Negotiation

Before each attempt, the router checks the chosen model's entry in the Model Capability Table (§7) and adapts the outgoing request:

| Requested feature | If model supports it | If model does NOT support it |
|---|---|---|
| `responseSchema` | Sent natively via provider's structured-output mechanism | Dropped by default (`droppedFeatures` records it). Optionally, implementations may offer a `structuredOutputStrategy` on `RouterConfig`: `"native"` (fail/drop if unsupported), `"promptFallback"` (inject schema + "respond with JSON only" instructions into the system prompt and best-effort parse the result), or `"auto"` (native when available, prompt-fallback otherwise). Default is `"auto"`. |
| `tools` | Sent natively via provider's tool-calling mechanism | Dropped, recorded in `droppedFeatures`. No prompt-based faking of tool calls — this is unreliable enough that dropping is the safe default. |
| `history` | Mapped to provider's message format | N/A — all supported providers accept multi-turn history |
| `attachments` | Sent natively via the provider's image/document input mechanism. Each attachment's `mediaType` is checked individually: `image/*` against `ModelEntry.supportsVision`, everything else against `ModelEntry.supportsFileInput`. | If **any** attachment in the request can't be supported by the chosen model, the whole `attachments` request is dropped (not sent) and recorded once in `droppedFeatures` — matching this table's existing feature-level (not per-item) granularity. |
| `temperature` (`RouterConfig.temperature`) | Sent natively as the model's sampling-temperature parameter | Omitted from the outgoing request (not sent — the provider's own default applies), recorded in `droppedFeatures` |
| `topP` (`RouterConfig.topP`) | Sent natively as the model's nucleus-sampling (top-p) parameter | Omitted from the outgoing request (not sent — the provider's own default applies), recorded in `droppedFeatures` |

**`temperature`/`topP` mutual exclusion.** Providers universally document that altering `temperature` *or* `topP` — never both in the same request — is the recommended usage; combining them compounds unpredictably. This isn't a wire-protocol restriction (nothing rejects a request with both set), but the router enforces it as policy: if `RouterConfig` has both set, at most one is ever sent for a given attempt — whichever the candidate model supports, preferring `temperature` if it supports both. The other is omitted and recorded in `droppedFeatures` for that attempt, exactly as if the model didn't support it. This resolution is per-attempt like everything else in this section — a fallback to a model that only supports `topP` correctly sends `topP` instead.

Separately, `Response.generatedFiles` is only ever populated when `ModelEntry.supportsFileOutput` is true for the model that served the request — this isn't something a caller requests per-call (there's no matching `Request` field), it's a property of whether the chosen model/provider combination can produce downloadable files at all (e.g. via a code-execution or image-generation tool).

Every "dropped" outcome in this table becomes a **skip** instead when the feature is listed in `RouterConfig.requiredFeatures` — see §5.4.

This negotiation happens **per attempt**, not once — if the router falls back from a model that supports structured output to one that doesn't, the second attempt correctly drops it and reports that in `droppedFeatures`/`attempts`.

`droppedFeatures` entries must use the exact canonical values in §12.7 (`"responseSchema"`, `"tools"`, `"attachments"`) — not a paraphrase or a differently-cased variant — so calling code can reliably branch on them regardless of which language implementation produced the response.

### 4.1 Request interceptor (optional last-chance hook)

Implementations may offer an optional hook — a `RequestInterceptor` in the Java implementation, named per whatever's idiomatic elsewhere (a callback, a delegate, a middleware function) — that lets a caller inspect and modify the final, negotiated request immediately before it's sent to a specific provider/model. This is the last point in the pipeline where the outgoing request can still be changed: it runs *after* capability negotiation (this section), so the request it receives is the fully adapted form actually about to be sent for this attempt, not the caller's original `Request`. A caller might use this to compress conversation history against that exact model's context window right before sending, inject a provider-specific header via `original`-style metadata, or log/redact the outgoing payload.

Shape (conceptual):

```
RequestInterceptor {
  beforeSend(provider: string, model: string, request: Request): Request
}
```

Contract:
- Called once per attempt in the fallback loop (§5.1) — if an earlier candidate fails and the router advances to the next one, the hook runs again with that next candidate's own `provider`/`model` and its own freshly negotiated request.
- Returning the request unchanged is a no-op; there is no way to skip a candidate or short-circuit fallback from inside the hook — it only transforms the request, it doesn't participate in routing decisions.
- The default, when a caller supplies no hook, is the identity function — behavior is unchanged from a library with no interceptor at all.
- The same hook instance is shared across every call the router instance serves, and applies uniformly regardless of which call style (sync/async/callback) was used to invoke the router.
- If the hook itself throws, implementations should treat it the same as any other in-attempt failure (§10: not fatal, recorded as a failed attempt, router advances to the next candidate) rather than letting it escape uncaught — a broken interceptor shouldn't take down the whole call when a working fallback candidate exists.

### 4.2 Streaming

Implementations should offer a streaming variant of the canonical call — `completeStreaming(request, listener)` in Java — that delivers the response's text to a listener as the provider generates it, for showing an answer to a user as it's written.

Shape (conceptual):

```
StreamListener {
  onText(delta: string)      // the next piece of the response's text, in order; never empty
  onReset()                  // optional: text delivered so far belongs to a failed attempt
}
```

Contract:
- Everything except delivery is identical to the non-streaming call: routing, capability negotiation (§4), the request interceptor (§4.1), required features (§5.4), fallback (§5.1), and the returned `Response`, whose `content` is the full text.
- Fallback still applies mid-stream. If an attempt fails after some text has been delivered, `onReset` is called before the router advances to the next candidate, whose text then streams from the beginning. A failure before any text was delivered falls back without a reset.
- An error raised by the listener itself (from `onText` or `onReset`) is the caller's, not the provider's: it ends the call immediately and reaches the caller unchanged, with no `onReset`, no recorded attempt and no fallback. Treating it as a failed attempt would spend a request on every remaining candidate and end in a misleading exhaustion error.
- Implementations with an async call style (§11) should offer the streaming call in that style too (`completeStreamingAsync` in Java), with this same contract. Where the async variant runs on another thread, the listener is still called one piece at a time, in order. Whether text arrives in pieces or all at once depends only on the adapter, not on the call style: an adapter without an async streaming path runs its streaming path off the caller's thread (the `sendStreamingAsync` default in §8.1).
- Only text streams. Tool calls, structured output and usage arrive on the returned `Response` once the attempt completes.
- Every provider supports the call: an adapter without native streaming delivers its whole text in one `onText` call (the `sendStreaming` default in §8.1).

---

## 5. `RouterConfig`

A `RouterConfig` is a reusable, long-lived object. Applications typically build one (or a small number of named ones, e.g. "fast", "high-quality") at startup and pass it on calls that need non-default behavior. It is deliberately **not** rebuilt per-request.

```
RouterConfig {
  route: RouteEntry[]?              // ordered fallback preference; if omitted, use the computed default (§6)
  thinkingLevel: ThinkingLevel?     // "low" | "medium" | "high" | "max"; default "medium"
  costOptimized: boolean?           // default false — see §5.3
  structuredOutputStrategy: string? // "native" | "promptFallback" | "auto"; default "auto"
  temperature: number?              // optional sampling temperature; no default (provider's own default applies when omitted). Sent to the model only if the resolved candidate's ModelEntry.supportsTemperature is true (§4, §7.1) — otherwise omitted for that attempt and recorded in droppedFeatures. If topP is also set, temperature wins whenever the candidate supports both — see §4's mutual-exclusion note.
  topP: number?                     // optional nucleus-sampling (top-p) parameter; no default. Sent to the model only if the resolved candidate's ModelEntry.supportsTopP is true (§4, §7.1) — otherwise omitted for that attempt and recorded in droppedFeatures. If temperature is also set and the candidate supports both, topP is omitted instead — see §4's mutual-exclusion note.
  requiredFeatures: string[]?       // features that must never be dropped — values per §12.9; default empty. See §5.4.
}

RouteEntry =
    { provider: string, model: string }   // fully specified — this exact model is tried
  | { provider: string }                  // provider only — resolved to a model at call time via thinkingLevel (§7.2)
```

`provider` above must be one of the canonical provider IDs (§12.1). `thinkingLevel` and `structuredOutputStrategy` must be one of the exact literal values given here — see §12.3 and §12.4 for the consolidated, cross-language reference.

### 5.1 Routing & fallback

`route` is an ordered list of candidates. The router tries each in order (after capability negotiation) until one succeeds. A candidate is skipped without being attempted (recorded as `"skipped"` in `attempts`) if no credentials are available for its provider, or if it can't honor one of `requiredFeatures` (§5.4).

Provider-only entries (`{ provider: "openai" }`) are expanded at call time into a specific model using the config's `thinkingLevel` and the Model Capability Table's selection heuristic (§7.2). Fully-specified entries (`{ provider, model }`) are used exactly as given, regardless of `thinkingLevel`.

### 5.1.1 Exhaustion

If every candidate in the resolved route fails or is skipped, the router raises a single, descriptive error (exception or error-return, per language convention) — e.g. `RouterExhaustedError` — whose payload is the full `attempts` list, so the caller can see exactly what was tried and why each attempt didn't succeed. This is not a generic error; the message should be human-readable and list each attempt.

### 5.2 Default route

If `RouterConfig.route` is omitted entirely (or no config is passed at all), the router uses a **computed default route**: the built-in provider preference order (§6.2) filtered down to only providers for which credentials were detected at startup, each expanded via the default `thinkingLevel` ("medium"). If *no* provider credentials are detected at all, the first call raises a configuration error immediately (fail fast, don't silently no-op).

### 5.3 Cost-optimized ordering

`costOptimized: true` changes how **provider-only route entries expand**, and how the **default route** expands: instead of picking a single model per provider via the thinking-level heuristic, the router gathers *all* models from that provider whose capability score qualifies for the requested `thinkingLevel` tier (§7.2) and orders them ascending by estimated cost (using the prompt's approximate token count against the Model Capability Table's per-token pricing), trying the cheapest qualifying model first.

Fully-specified `{ provider, model }` entries are never reordered by cost — an explicit model choice is always honored as given. `costOptimized` only affects how ambiguity (provider-only entries, or the default route) is resolved. When `costOptimized` is absent or `false`, it has no effect on ordering at all.

### 5.4 Required features

By default, a feature a candidate can't support is dropped and the attempt goes ahead (§4). That's the right trade-off for a one-off prompt, but quietly fatal for, say, an agent loop whose control flow depends on its tools: without them the model carries on with no abilities. `requiredFeatures` lets a caller opt specific features out of dropping.

- **What counts as unmet.** A required feature is unmet for a candidate exactly when §4 negotiation would record it in `droppedFeatures` for this request. So `responseSchema` honored via prompt-fallback (`structuredOutputStrategy` `"auto"` or `"promptFallback"`) counts as honored; to require native structured output, also set `structuredOutputStrategy: "native"`. A required feature the request doesn't actually use (e.g. `"tools"` required but `tools` empty) is trivially met.
- **Fully-specified entries** whose model can't honor a required feature are skipped, not attempted, and recorded in `attempts` as `"skipped"` with a reason naming the unmet features (e.g. `model does not support required feature(s) [tools]`).
- **Provider-only entries and the default route** expand only over the provider's models that can honor every required feature: the thinking-level heuristic (§7.2), and with `costOptimized` the qualifying set and cost ordering (§5.3), all run over that filtered lineup. If none of the provider's models qualify, the entry is recorded as `"skipped"` with a `null` model and a reason such as `no perplexity model supports required feature(s) [tools]`, rather than silently vanishing from `attempts`.
- **Models missing from the capability table** have no data to check against, so, consistent with §4 sending their requests unadapted, they are attempted rather than skipped. Nothing is dropped for them either; an unsupported feature surfaces as a provider error and a normal failed attempt.
- **Exhaustion.** If every candidate is skipped or fails, the router raises the normal `ROUTER_EXHAUSTED` error (§5.1.1); the skip reasons in `attempts` (and the error message) show which required features couldn't be met where. This applies to the default route too: when credentials exist but no available provider can meet the requirements, the result is exhaustion, not `NO_PROVIDERS_CONFIGURED`.
- **Default.** An empty `requiredFeatures` (the default) leaves routing and negotiation exactly as described in §4–§5.3.

Credential checks still come first: a candidate with no credentials is skipped with the usual `no API key detected` reason, whatever its capabilities.

---

## 6. Environment & Credential Detection

Credential detection happens **once**, lazily, on first use of the router in the process, and is cached for the process lifetime (not re-read per call). This keeps calls fast and behavior deterministic within a run.

### 6.1 Environment variable convention

For each supported provider, the router checks a library-namespaced variable first, then falls back to that provider's common convention variable, so the library plays nicely alongside other tools that already expect the common name:

| Provider ID (§12.1) | Display name | Primary env var | Fallback env var |
|---|---|---|---|
| `anthropic` | Anthropic (Claude) | `LLM_ROUTER_ANTHROPIC_API_KEY` | `ANTHROPIC_API_KEY` |
| `openai` | OpenAI | `LLM_ROUTER_OPENAI_API_KEY` | `OPENAI_API_KEY` |
| `perplexity` | Perplexity | `LLM_ROUTER_PERPLEXITY_API_KEY` | `PERPLEXITY_API_KEY` |
| `nvidia` | NVIDIA (NIM) | `LLM_ROUTER_NVIDIA_API_KEY` | `NVIDIA_API_KEY` |
| `huggingface` | Hugging Face | `LLM_ROUTER_HUGGINGFACE_API_KEY` | `HF_TOKEN`, then `HUGGINGFACE_API_KEY` |
| `openrouter` | OpenRouter | `LLM_ROUTER_OPENROUTER_API_KEY` | `OPENROUTER_API_KEY` |

A provider is considered "available" if either variable resolves to a non-empty value. The left column (`Provider ID`) is the exact, case-sensitive string every implementation must use wherever a `provider` field appears (`RouteEntry.provider`, `ModelEntry.provider`, `ProviderAdapter.id`, `Response.providerUsed`, `AttemptRecord.provider`) — see §12.1 for the authoritative list.

### 6.2 Default provider preference order

Used to build the default route (§5.2) once filtered to available providers:

1. `anthropic`
2. `openai`
3. `perplexity`
4. `nvidia`
5. `huggingface`
6. `openrouter`

This order is a reasonable general-purpose default (frontier general-purpose models first, specialized/search-oriented and open-model providers after, with the multi-vendor aggregator `openrouter` last since it overlaps the others and is most useful as a broad catch-all) and should be easy for a caller to override entirely via `RouterConfig.route`.

---

## 7. Model Capability Table

The router ships with a built-in table describing every model it knows about across supported providers. This table drives capability negotiation (§4), thinking-level resolution (§7.2), and cost calculations (§5.3). It is **mutable at runtime** — callers can register new models, update pricing/scores on existing ones, or remove entries — because pricing and model lineups change faster than the library can be re-released.

### 7.1 Schema

```
ModelEntry {
  provider: string              // canonical provider ID, §12.1
  model: string                 // provider-defined model id, §12.2 — not a library-level enum
  inputCostPerMillionTokens: float
  outputCostPerMillionTokens: float
  thinkingScore: number        // 0-10, relative reasoning/capability strength within this table
  speedScore: number           // 0-10, relative latency/throughput (higher = faster)
  contextWindowTokens: int
  maxOutputTokens: int
  supportsStructuredOutput: boolean
  supportsTools: boolean
  supportsVision: boolean
  supportsFileInput: boolean    // can accept non-image file attachments (documents, etc.) in a request — §4
  supportsFileOutput: boolean   // can produce downloadable generated files, e.g. via code execution or image generation — §4
  supportsTemperature: boolean  // accepts a caller-supplied sampling-temperature value — false if the model's API rejects/ignores it (e.g. a reasoning mode with a fixed sampling configuration) — §4
  supportsTopP: boolean         // accepts a caller-supplied nucleus-sampling (top-p) value, same rationale as supportsTemperature — §4
  lastUpdated: string          // ISO 8601 UTC timestamp, e.g. "2026-09-14T13:09:08Z" — when this specific row was last verified/updated
}
```

Library API surface for the table (name per language convention): `registerModel(entry)`, `updateModel(provider, model, partialFields)`, `removeModel(provider, model)`, `listModels(provider?)`. Updates take effect immediately for subsequent calls; no restart required.

The library should ship with a reasonable seed table covering current flagship, mid, and lightweight models from each of the providers in §8, populated with approximate real-world pricing and relative capability/speed scores at time of writing. Treat these seed values as "best effort, expected to go stale" — this is exactly why the table is user-updatable. The pre-built table will be provided in the root folder of this project (alongside this file) along with a skill to keep it updated.

### 7.2 Thinking-level → model selection heuristic

This heuristic is what resolves a provider-only `RouteEntry` (e.g. `{ provider: "openai" }`) into a concrete model, and is also what defines the "qualifying set" used by cost-optimized ordering (§5.3).

`thinkingLevel` is one of four tiers: `low`, `medium`, `high`, `max`. Each tier maps to a target percentile of `thinkingScore` **within that provider's own model set**:

| Tier | Target percentile of provider's `thinkingScore` range |
|---|---|
| `low` | ~25th percentile (fast, inexpensive, simple-task models) |
| `medium` | ~50th percentile (balanced default) |
| `high` | ~75th percentile |
| `max` | 100th percentile (the single highest `thinkingScore` model the provider offers) |

Algorithm:
1. Take all `ModelEntry` rows for the target provider.
2. Compute the provider's min/max `thinkingScore` across those rows.
3. Compute the target score = `min + percentile * (max - min)` for the requested tier.
4. Select the model(s) whose `thinkingScore` is closest to the target score.
5. Break ties, in order: (a) higher `speedScore`, (b) lower combined input+output cost, (c) alphabetical `model` id — so selection is deterministic.

For cost-optimized expansion (§5.3), instead of collapsing to a single closest match in step 4, take **all models within a 1 point thinkingScore tolerance band** of the target score as the "qualifying set", then sort that set ascending by estimated cost for the actual prompt. If no model falls inside the band (a lineup with a gap around the target), widen it just enough to include the closest model(s) to the target, so the qualifying set is never empty: turning on `costOptimized` must never remove a provider from the route that would have served it without it.

---

## 8. Minimum Supported Providers

The library must ship with built-in provider adapters for, at minimum:

1. **OpenAI** — `openai`
2. **Anthropic (Claude)** — `anthropic`
3. **Perplexity** — `perplexity`
4. **NVIDIA** (NIM-hosted open models) — `nvidia`
5. **Hugging Face** (Inference API / Inference Endpoints) — `huggingface`
6. **OpenRouter** (multi-vendor model aggregator/router) — `openrouter`

(Canonical provider ID strings per §12.1.)

OpenRouter is itself a router over many upstream vendors' models, addressed by OpenRouter-specific model id strings (typically `vendor/model-name`, e.g. `"anthropic/claude-opus-5"`) — see §12.2. Its Model Capability Table entries (§7) should be populated the same way as any other provider's, and it participates in fallback/cost-optimized ordering identically to the other five; the library does not treat it specially beyond that.

### 8.1 Provider adapter contract (conceptual)

Each provider is implemented behind a common internal adapter interface so additional providers can be added later without touching router core logic:

```
ProviderAdapter {
  id: string                                   // one of the canonical provider IDs, §12.1 (e.g. "anthropic")
  isAvailable(): boolean                       // credential check, §6
  send(model: string, adaptedRequest): RawResponse   // provider-specific call
  sendStreaming(model, adaptedRequest, onText): RawResponse   // optional, §4.2; default: send, then onText(whole text)
  sendStreamingAsync(model, adaptedRequest, onText): future<RawResponse>   // optional, §4.2; default: sendStreaming off the caller's thread
  normalize(rawResponse): Response fragments   // maps provider response → unified Response shape
}
```

Provider adapters are responsible only for translating the unified `Request`/`Response` shapes to/from that provider's wire format. All routing, fallback, capability negotiation, and cost logic lives in the router core and is shared across every provider.

#### 8.1.1 Attachment reuse across calls (optional adapter-internal optimization)

`Request.attachments` is per-call, and `Message` (history) carries no attachment reference — a caller running a multi-turn conversation that keeps the same file relevant across several calls has no protocol-level way to say "this attachment hasn't changed since last time." Rather than growing the public `Request`/`Message` shape to express that (which would force every attachment-bearing history entry to carry provider-agnostic file identity, and raise awkward questions about replaying history against a provider that never saw the file, serializing/rehydrating that identity across process restarts, etc.), an adapter **may** solve this transparently, entirely on its own side of the `ProviderAdapter` boundary, when the underlying provider exposes a suitable mechanism (a file-upload API whose returned handle can be referenced from a later request instead of re-embedding the content, and/or an explicit prompt-caching hint on repeated content). Doing so is invisible to callers: they keep passing the same `Attachment` (same bytes) on `Request.attachments` every call, exactly as documented in §3, and get the optimization for free as long as they reuse the same adapter/router instance across the conversation — which the library already expects (§1, §6: construct once, reuse for the process lifetime).

An adapter implementing this should:
- Key reuse off the attachment's **content** (e.g. a hash of `mediaType` + bytes), never off object identity or a caller-supplied name, since two calls may pass distinct `Attachment` instances with identical bytes.
- Scope the cache to a single adapter instance for a single provider — never share a handle across providers (a fallback from one provider to another must never assume the second provider has seen the file) and never persist it beyond the process, since the public contract gives callers no way to know a handle exists to invalidate.
- Treat the mechanism as a pure optimization: if the upload/reference step itself fails for any reason, fall back to the provider's normal inline embedding for that one attempt rather than failing the request — a caller must see identical success/failure behavior whether or not this optimization is available for their provider.
- Not assume every provider supports this — several providers (or several media types on the same provider) may have no such mechanism at all, in which case the adapter simply keeps re-embedding inline as it always has. This is expected to vary a lot by provider and isn't part of the cross-language contract; document per-adapter which media types/providers it applies to.

---

## 9. Logging

The library integrates with each language's idiomatic logging facility (e.g. SLF4J for Java, standard `logging` module for Python, a common structured logger for Node/TypeScript) rather than inventing its own. Standard levels apply:

- **DEBUG**: resolved route/candidate list for a call, per-attempt request payload shape (not full content by default — avoid leaking prompts at non-debug levels), capability-negotiation decisions.
- **INFO**: which provider/model was ultimately used, token usage/cost summary per call.
- **WARN**: a candidate was skipped (no credentials) or a feature was dropped due to capability mismatch.
- **ERROR**: an attempt failed (with provider/model and error detail); router exhaustion.

No logging is mandatory-on by default beyond what the host application's logging configuration already enables — the library should never force verbose output onto a consuming application.

---

## 10. Error Conditions Summary

| Condition | Behavior |
|---|---|
| No provider credentials detected anywhere | Fail fast on first call with a configuration error explaining no providers are available. |
| Config specifies a route but none of those providers have credentials | All entries skipped → treated as exhaustion (§5.1.1). |
| A requested feature (schema/tools) isn't supported by the current candidate model | Not an error — adapt/drop per §4, continue the attempt, record in `droppedFeatures`. |
| …and that feature is in `RouterConfig.requiredFeatures` | Not an error by itself — the candidate is skipped and recorded in `attempts` (§5.4); if no candidate qualifies, exhaustion (§5.1.1). |
| A `ToolDefinition` breaks the §3.1 rules (bad name, duplicate name, non-object `parameters`) | Raise an `INVALID_REQUEST` error immediately at call time, before any provider is contacted, listing every violation. |
| A provider call fails (network, rate limit, 4xx/5xx, timeout) | Not fatal — recorded as a failed attempt, router advances to next candidate. |
| Every candidate fails or is skipped | Raise `RouterExhaustedError` (or language equivalent) containing the full `attempts` list. |
| Malformed `RouterConfig` (e.g. invalid `thinkingLevel` value) | Raise a configuration error immediately at call time, not buried inside routing logic. |

---

## 11. Explicitly Out of Scope (per-language idiom, not specified here)

- Exact method/function naming and signatures — follow each language's naming conventions.
- Async/sync call variants — follow the language/runtime's normal conventions (e.g. `async`/`await`, `Future`/`CompletableFuture`, goroutines + channels).
- Dependency injection / framework integration patterns.
- Packaging/distribution mechanics (Maven Central, npm, PyPI, NuGet, crates.io, etc.).
- Tool-call *execution* — the library only surfaces requested tool calls; invoking them is the host application's responsibility.
- Retry/backoff timing policy for transient provider errors within a single attempt — implementations may add a small, sensible retry (e.g. one retry on 429/5xx) before treating a candidate as failed, but this is an implementation detail, not a contract.

---

## 12. Canonical Enum & Identifier Values (Cross-Language Contract)

Every language implementation of `llm-router` MUST use the exact, case-sensitive string literals below wherever this spec calls for an enum-like value. This is what keeps a `RouterConfig`, a `Response`, or a config file written against one language's implementation directly portable to another (e.g. a JSON-serialized route or a saved capability table should mean the same thing whether produced by the Java or the Python port). Do not translate, localize, re-case, or abbreviate these values — pass them through verbatim. This section is the single source of truth; other sections cross-reference it rather than redefining these values.

This contract covers only fixed, library-defined string values. It does **not** cover free-form fields such as `model` (§12.2), error messages, log text, or user-supplied prompt/schema content.

### 12.1 Provider IDs

| ID | Provider |
|---|---|
| `anthropic` | Anthropic (Claude) |
| `openai` | OpenAI |
| `perplexity` | Perplexity |
| `nvidia` | NVIDIA (NIM-hosted open models) |
| `huggingface` | Hugging Face |
| `openrouter` | OpenRouter (multi-vendor model aggregator/router) |

Lowercase, single word, no separators — chosen so the same literal is also valid as a bare enum-member name in every target language (e.g. `Provider.HUGGINGFACE` in Java, `Provider.huggingface` in Python). Used for: `RouteEntry.provider`, `ModelEntry.provider`, `ProviderAdapter.id`, `Response.providerUsed`, `AttemptRecord.provider`, and as the implicit key in the §6.1 environment-variable table.

Adding a 6th+ provider later should follow the same convention (lowercase, single word) and be appended here first, before any implementation adopts it.

### 12.2 Model identifiers

**Not a fixed enum.** `model` is always the literal model identifier string as defined by that provider's own API/docs (e.g. `"claude-opus-5"`, `"gpt-5.1"`, `"llama-3.3-70b-instruct"`). Because the Model Capability Table (§7) is user-mutable and provider lineups change independently of this spec, implementations must not hardcode or validate against a closed set of model strings — only the `provider` field is a closed enum.

### 12.3 `thinkingLevel`

`low` | `medium` | `high` | `max` — full tier semantics and the selection heuristic are in §7.2.

### 12.4 `structuredOutputStrategy`

`native` | `promptFallback` | `auto` — full semantics are in §4.

### 12.5 `Message.role`

`system` | `user` | `assistant` | `tool`

### 12.6 `AttemptRecord.outcome`

`success` | `failed` | `skipped`

### 12.7 `droppedFeatures` entries

Exactly the request-field names they refer to: `responseSchema` | `tools` | `attachments` | `temperature` | `topP` (matching the field names in §3's `Request` shape / §5's `RouterConfig` shape, camelCase, no other spellings).

### 12.8 Canonical error codes

Each condition in §10 that results in a raised error should expose a stable, machine-readable code alongside its human-readable message — as a `.code`/`.errorCode` property, an error subtype/enum variant, or whatever discrimination mechanism is idiomatic for the language — using these exact values, so calling code can branch on failure category identically regardless of which language implementation is in use:

| Code | Raised when |
|---|---|
| `ROUTER_EXHAUSTED` | Every candidate in the resolved route failed or was skipped (§5.1.1). |
| `NO_PROVIDERS_CONFIGURED` | No provider credentials were detected anywhere at first use (§5.2, §10). |
| `INVALID_CONFIG` | A malformed `RouterConfig` was supplied, e.g. an invalid `thinkingLevel` value (§10). |
| `INVALID_REQUEST` | The `Request` itself is one every provider would refuse, e.g. a tool definition that breaks §3.1 (§10). |

### 12.9 `requiredFeatures` entries

`tools` | `responseSchema` | `attachments` — the subset of §12.7 that can be required (§5.4). Each is the same literal that would otherwise appear in `droppedFeatures`. `temperature` and `topP` are deliberately excluded: they're sampling preferences rather than capabilities a call depends on, and the §4 mutual-exclusion rule would make requiring both unsatisfiable.

---

## 13. Proposal (not implemented): provider-run tools

> **Status: design only.** Nothing in this section is implemented in any language yet, and it is not part of the cross-language contract until it is. It exists so the shape can be agreed before an implementation commits to one.

`ToolDefinition` (§3) describes a tool the **caller** runs: the model asks for it, the router returns the `ToolCall`, and the caller executes it. Providers also offer tools they run **themselves** within a single API call, such as web search, web fetch, and code execution (Anthropic's and OpenAI's hosted tools, for example). These can't be expressed today. They differ from caller-run tools in every respect the router cares about: the caller never sees a call to execute, the provider has to be told by a provider-specific, often versioned type identifier, availability varies per model, and results come back as content rather than as a request for the caller to act on.

### 13.1 Requesting a provider-run tool

A new, separate `Request` field, kept apart from `tools` so the two kinds can't be confused or name-clash:

```
Request {
  ...
  providerTools: ProviderTool[]?
}

ProviderTool {
  type: string                         // canonical, provider-neutral ID, e.g. "webSearch" | "webFetch" | "codeExecution" (new §12 list)
  options: object?                     // provider-neutral options the router knows how to map, e.g. { maxUses, allowedDomains, blockedDomains }
  providerOptions: map<providerId, object>?  // escape hatch: passed verbatim to that provider's adapter only
}
```

- The caller names a capability (`"webSearch"`), never a provider's wire identifier (e.g. a dated tool type string). Each adapter maps the canonical ID to its provider's current tool type, so a provider bumping its tool version is an adapter change, not a caller change.
- Neutral `options` only covers settings with a clear equivalent across providers. Anything else goes in `providerOptions`, which an adapter ignores unless it's keyed to its own provider. This keeps the common case portable without blocking provider-specific tuning.
- §3.1 validation extends naturally: `type` must be a known canonical ID, and appears at most once per request.

### 13.2 Capability negotiation and fallback

- **Capability data.** `ModelEntry` gains `providerTools: string[]`, the canonical IDs that model supports natively (§7.1). It is a list rather than a boolean per tool so new tool types don't need a schema change. Models with built-in, always-on search (e.g. Perplexity's Sonar models) are *not* listed as supporting `"webSearch"`: the caller can't switch that search on or off, so listing it would let negotiation claim to honor a request it isn't actually controlling. Routing to them for search stays an explicit route choice.
- **Per-item dropping.** Unlike `attachments` (all-or-nothing, §4), provider tools are independent of each other, so each unsupported one is dropped individually. `droppedFeatures` records `"providerTools"` once (keeping §12.7's field-name granularity), and a new `Response.droppedProviderTools: string[]` lists exactly which canonical IDs were dropped.
- **No emulation.** The router never substitutes its own implementation (it doesn't run a search on the caller's behalf). That would break the library's side-effect-free guarantee (§3), for the same reason prompt-based tool faking is ruled out in §4.
- **Requiring them.** `"providerTools"` joins §12.9, so `requiredFeatures` can make candidates that lack any requested provider tool skip rather than drop, with the same skip/exhaustion semantics as §5.4. A finer-grained form (requiring `webSearch` but tolerating loss of `webFetch`) is deferred until there's a concrete need.
- **Fallback.** Negotiation stays per attempt. Falling back from a provider that ran a tool to one that can't is handled exactly like any other dropped feature. Fallback never resumes a partly completed provider-side tool run: each attempt is a fresh call.
- **Adapter prerequisites.** Some providers expose hosted tools only on a different API surface from the one an adapter currently uses (OpenAI's are on the Responses API rather than Chat Completions, for example). Supporting `providerTools` for such a provider means moving that adapter, and until then its table entries simply list no `providerTools`.

### 13.3 Results in `Response`

Provider-run activity is reported, never returned as a `ToolCall` (the caller must not try to execute it):

```
Response {
  ...
  providerToolResults: ProviderToolResult[]?
  droppedProviderTools: string[]?
}

ProviderToolResult {
  type: string                         // canonical ID, e.g. "webSearch"
  input: object?                       // what the model asked for, e.g. { query } or { code }, when the provider exposes it
  output: string?                      // text output, e.g. stdout for codeExecution, when provided
  citations: Citation[]?               // { url, title?, citedText? } for search/fetch results the answer relies on
  error: string?                       // provider-reported failure of this tool run (the overall response can still succeed)
}
```

- `content` stays the model's final answer, with the model's own inline references left as-is. `citations` gives callers a structured, provider-neutral list without having to parse provider-specific annotation formats.
- Files produced by `codeExecution` keep coming back through the existing `generatedFiles` (§3), which already anticipates this.
- `original` still carries the raw provider blocks for anything the neutral shape doesn't capture.
- **Cost.** Hosted tools are often billed per use on top of tokens. `Usage` gains `providerToolUses: map<type, int>`, and the capability table a per-tool unit price, so `estimatedCostUsd` stays meaningful.
- **History.** Some providers expect their own tool blocks to be passed back verbatim on the next turn. `Message` (assistant role) gains an optional `providerToolResults`. When the next call goes to the same provider, the adapter can replay the blocks natively from the stored `original`. When it goes to a different provider, the turn is flattened to text, mirroring how `toolCalls`/`toolCallId` already degrade (§3).

### 13.4 Open questions

- Whether `options` should be validated per tool type up front (§3.1), or passed through with unknown keys ignored.
- Whether `providerToolResults` should also stream incrementally, once streaming is in scope.
- Whether `"providerTools"` in `requiredFeatures` needs per-type granularity from day one (see §13.2).
