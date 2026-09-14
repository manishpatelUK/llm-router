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
  history: Message[]?                  // optional prior turns: { role: "user"|"assistant"|"system"|"tool", content }
  systemInstructions: string?          // optional system/developer prompt
  responseSchema: Schema?              // optional structured-output schema (JSON-Schema-like)
  tools: ToolDefinition[]?             // optional tool/function definitions the model may call
  config: RouterConfig?                // optional; falls back to the router's default config if omitted
}

ToolDefinition {
  name: string
  description: string
  parameters: Schema                   // JSON-Schema-like description of arguments
}
```

The router does **not** execute tool calls itself — it only requests them from the model and returns them to the caller to execute. This keeps the library side-effect-free and embeddable in any application architecture.

### Response shape

```
Response {
  content: string                      // primary text output
  structuredOutput: object?            // present if a responseSchema was honored natively or via fallback
  toolCalls: ToolCall[]?               // present if the model requested tool invocations
  providerUsed: string                 // e.g. "anthropic"
  modelUsed: string                    // e.g. "claude-opus-5"
  usage: {
    inputTokens: int
    outputTokens: int
    estimatedCostUsd: float
  }
  droppedFeatures: string[]            // e.g. ["responseSchema", "tools"] if the chosen model couldn't support them
  attempts: AttemptRecord[]            // one entry per candidate tried before success (empty if first candidate succeeded)
}

AttemptRecord {
  provider: string
  model: string
  outcome: "success" | "failed" | "skipped"
  reason: string?                      // error message or skip reason (e.g. "no API key detected")
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

This negotiation happens **per attempt**, not once — if the router falls back from a model that supports structured output to one that doesn't, the second attempt correctly drops it and reports that in `droppedFeatures`/`attempts`.

---

## 5. `RouterConfig`

A `RouterConfig` is a reusable, long-lived object. Applications typically build one (or a small number of named ones, e.g. "fast", "high-quality") at startup and pass it on calls that need non-default behavior. It is deliberately **not** rebuilt per-request.

```
RouterConfig {
  route: RouteEntry[]?              // ordered fallback preference; if omitted, use the computed default (§6)
  thinkingLevel: ThinkingLevel?     // "low" | "medium" | "high" | "max"; default "medium"
  costOptimized: boolean?           // default false — see §5.3
  structuredOutputStrategy: string? // "native" | "promptFallback" | "auto"; default "auto"
}

RouteEntry =
    { provider: string, model: string }   // fully specified — this exact model is tried
  | { provider: string }                  // provider only — resolved to a model at call time via thinkingLevel (§7.2)
```

### 5.1 Routing & fallback

`route` is an ordered list of candidates. The router tries each in order (after capability negotiation) until one succeeds. A candidate is skipped without being attempted (recorded as `"skipped"` in `attempts`) if no credentials are available for its provider.

Provider-only entries (`{ provider: "openai" }`) are expanded at call time into a specific model using the config's `thinkingLevel` and the Model Capability Table's selection heuristic (§7.2). Fully-specified entries (`{ provider, model }`) are used exactly as given, regardless of `thinkingLevel`.

### 5.1.1 Exhaustion

If every candidate in the resolved route fails or is skipped, the router raises a single, descriptive error (exception or error-return, per language convention) — e.g. `RouterExhaustedError` — whose payload is the full `attempts` list, so the caller can see exactly what was tried and why each attempt didn't succeed. This is not a generic error; the message should be human-readable and list each attempt.

### 5.2 Default route

If `RouterConfig.route` is omitted entirely (or no config is passed at all), the router uses a **computed default route**: the built-in provider preference order (§6.2) filtered down to only providers for which credentials were detected at startup, each expanded via the default `thinkingLevel` ("medium"). If *no* provider credentials are detected at all, the first call raises a configuration error immediately (fail fast, don't silently no-op).

### 5.3 Cost-optimized ordering

`costOptimized: true` changes how **provider-only route entries expand**, and how the **default route** expands: instead of picking a single model per provider via the thinking-level heuristic, the router gathers *all* models from that provider whose capability score qualifies for the requested `thinkingLevel` tier (§7.2) and orders them ascending by estimated cost (using the prompt's approximate token count against the Model Capability Table's per-token pricing), trying the cheapest qualifying model first.

Fully-specified `{ provider, model }` entries are never reordered by cost — an explicit model choice is always honored as given. `costOptimized` only affects how ambiguity (provider-only entries, or the default route) is resolved. When `costOptimized` is absent or `false`, it has no effect on ordering at all.

---

## 6. Environment & Credential Detection

Credential detection happens **once**, lazily, on first use of the router in the process, and is cached for the process lifetime (not re-read per call). This keeps calls fast and behavior deterministic within a run.

### 6.1 Environment variable convention

For each supported provider, the router checks a library-namespaced variable first, then falls back to that provider's common convention variable, so the library plays nicely alongside other tools that already expect the common name:

| Provider | Primary env var | Fallback env var |
|---|---|---|
| Anthropic (Claude) | `LLM_ROUTER_ANTHROPIC_API_KEY` | `ANTHROPIC_API_KEY` |
| OpenAI | `LLM_ROUTER_OPENAI_API_KEY` | `OPENAI_API_KEY` |
| Perplexity | `LLM_ROUTER_PERPLEXITY_API_KEY` | `PERPLEXITY_API_KEY` |
| NVIDIA (NIM) | `LLM_ROUTER_NVIDIA_API_KEY` | `NVIDIA_API_KEY` |
| Hugging Face | `LLM_ROUTER_HUGGINGFACE_API_KEY` | `HF_TOKEN`, then `HUGGINGFACE_API_KEY` |

A provider is considered "available" if either variable resolves to a non-empty value.

### 6.2 Default provider preference order

Used to build the default route (§5.2) once filtered to available providers:

1. Anthropic (Claude)
2. OpenAI
3. Perplexity
4. NVIDIA
5. Hugging Face

This order is a reasonable general-purpose default (frontier general-purpose models first, specialized/search-oriented and open-model providers after) and should be easy for a caller to override entirely via `RouterConfig.route`.

---

## 7. Model Capability Table

The router ships with a built-in table describing every model it knows about across supported providers. This table drives capability negotiation (§4), thinking-level resolution (§7.2), and cost calculations (§5.3). It is **mutable at runtime** — callers can register new models, update pricing/scores on existing ones, or remove entries — because pricing and model lineups change faster than the library can be re-released.

### 7.1 Schema

```
ModelEntry {
  provider: string
  model: string
  inputCostPerMillionTokens: float
  outputCostPerMillionTokens: float
  thinkingScore: number        // 0-10, relative reasoning/capability strength within this table
  speedScore: number           // 0-10, relative latency/throughput (higher = faster)
  contextWindowTokens: int
  maxOutputTokens: int
  supportsStructuredOutput: boolean
  supportsTools: boolean
  supportsVision: boolean
}
```

Library API surface for the table (name per language convention): `registerModel(entry)`, `updateModel(provider, model, partialFields)`, `removeModel(provider, model)`, `listModels(provider?)`. Updates take effect immediately for subsequent calls; no restart required.

The library should ship with a reasonable seed table covering current flagship, mid, and lightweight models from each of the providers in §8, populated with approximate real-world pricing and relative capability/speed scores at time of writing. Treat these seed values as "best effort, expected to go stale" — this is exactly why the table is user-updatable.

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

For cost-optimized expansion (§5.3), instead of collapsing to a single closest match in step 4, take **all models within a small tolerance band** of the target score (e.g. within 1.0 `thinkingScore` point, or the single closest if none fall within the band) as the "qualifying set," then sort that set ascending by estimated cost for the actual prompt.

---

## 8. Minimum Supported Providers

The library must ship with built-in provider adapters for, at minimum:

1. **OpenAI**
2. **Anthropic (Claude)**
3. **Perplexity**
4. **NVIDIA** (NIM-hosted open models)
5. **Hugging Face** (Inference API / Inference Endpoints)

### 8.1 Provider adapter contract (conceptual)

Each provider is implemented behind a common internal adapter interface so additional providers can be added later without touching router core logic:

```
ProviderAdapter {
  id: string                                   // e.g. "anthropic"
  isAvailable(): boolean                       // credential check, §6
  send(model: string, adaptedRequest): RawResponse   // provider-specific call
  normalize(rawResponse): Response fragments   // maps provider response → unified Response shape
}
```

Provider adapters are responsible only for translating the unified `Request`/`Response` shapes to/from that provider's wire format. All routing, fallback, capability negotiation, and cost logic lives in the router core and is shared across every provider.

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
