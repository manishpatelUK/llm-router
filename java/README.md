# llm-router (Java)

The Java implementation of [`llm-router`](../README.md) — a single entry point for calling Anthropic, OpenAI, Perplexity, NVIDIA, Hugging Face, or OpenRouter, with automatic fallback between them, capability-aware request adaptation, and optional cost-optimized model selection. Full behavior spec: [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md).

## Installation

Include this in your `pom.xml`:

```xml
<dependency>
  <groupId>io.github.manishpateluk</groupId>
  <artifactId>llm-router</artifactId>
  <version>1.0.11</version>
</dependency>
```

Requires **Java 25+**.

## Quick start

Set an API key for at least one provider first — see [Configuring credentials](../README.md#configuring-credentials) in the main README for the environment variables the router reads.

```java
import io.github.manishpateluk.llmrouter.LlmRouter;
import io.github.manishpateluk.llmrouter.model.Response;

LlmRouter router = new LlmRouter(); // construct once, reuse everywhere — it's thread-safe

Response response = router.complete("What's the capital of France?");
System.out.println(response.getContent());
System.out.println(response.getProviderUsed() + "/" + response.getModelUsed());
```

## Usage examples

**With system instructions:**

```java
Response response = router.complete(
    "Summarize this in one sentence.",
    "You are a terse technical writer.");
```

**With conversation history:**

```java
import io.github.manishpateluk.llmrouter.model.Message;
import java.util.List;

List<Message> history = List.of(
    Message.user("What's 2+2?"),
    Message.assistant("4."));

Response response = router.complete("And 2+3?", history);
```

**With a `RouterConfig`** (explicit fallback order, thinking level, cost-optimized routing):

```java
import io.github.manishpateluk.llmrouter.config.RouteEntry;
import io.github.manishpateluk.llmrouter.config.RouterConfig;
import io.github.manishpateluk.llmrouter.config.ThinkingLevel;
import io.github.manishpateluk.llmrouter.provider.Provider;

RouterConfig config = RouterConfig.builder()
    .route(List.of(RouteEntry.of(Provider.ANTHROPIC), RouteEntry.of(Provider.OPENAI)))
    .thinkingLevel(ThinkingLevel.HIGH) // low | medium (default) | high | max
    .costOptimized(true)               // cheapest qualifying model first, within each provider
    .build();

Response response = router.complete("Explain quantum entanglement simply.", config);
```

Build a `RouterConfig` once and reuse it across calls — it's not meant to be rebuilt per request. To vary one setting, derive a copy with `toBuilder()`, which keeps every other field:

```java
RouterConfig quick = config.toBuilder().thinkingLevel(ThinkingLevel.LOW).build();
```

**How cost-optimized routing estimates cost.** It prices the whole request on each candidate: prompt, system instructions, history, tool definitions, the response schema, and attachments the model accepts (images and PDF pages at each provider's documented token rate), plus the expected output (`maxOutputTokens`, or 1,000 tokens if unset) at the model's output price. The same input estimate is checked against each model's context window whether or not `costOptimized` is on: a model that can't hold the request is skipped, with a reason in `getAttempts()`, and provider-only entries pick among the models that fit. (Not when you've installed a `RequestInterceptor`: it may shrink the request to fit, so nothing is skipped for size.) See §5.3 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) for the rates.

**Stepping down rather than failing.** By default, once every candidate at the requested thinking level has failed or been skipped, you get a `RouterExhaustedException`. With `FallbackPolicy.FINISH`, the router tries provider-only entries (and the default route) again one level lower, down to `LOW`, before giving up. It never steps up, tries fully-specified entries only once, and never retries a model it has already tried:

```java
import io.github.manishpateluk.llmrouter.config.FallbackPolicy;

RouterConfig finishing = config.toBuilder().fallback(FallbackPolicy.FINISH).build();

Response response = router.complete("Draft the board update.", finishing);
response.getThinkingLevel();                 // the level that answered, e.g. MEDIUM after a HIGH run stepped down
response.getAttempts().get(0).getThinkingLevel(); // the level each earlier attempt was made at
```

**Sending the thinking level to the provider.** `thinkingLevel` always chooses the model. Set `sendThinkingLevel(true)` and it also goes to the chosen model's own reasoning control: Anthropic's `effort` (or a thinking budget on Claude Haiku 4.5), OpenAI's `reasoning_effort`, or OpenRouter's `reasoning.effort`. `MAX` maps to the model's highest level, such as `xhigh` or `max`. It's off by default, so models keep their provider's default reasoning. A thinking budget can't be combined with `temperature`/`topP`, so those are dropped and reported in `getDroppedFeatures()` for that attempt. `getReasoning()` on the response says what was sent:

```java
RouterConfig deep = config.toBuilder().thinkingLevel(ThinkingLevel.MAX).sendThinkingLevel(true).build();
ReasoningSettings sent = router.complete("Find the flaw in this proof.", deep).getReasoning(); // e.g. effort "max", or null
```

See §5.1.2 and §7.4 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md).

**Timeouts, retries and cooldown.** All off by default. A config that sets none of them behaves exactly as before:

```java
import io.github.manishpateluk.llmrouter.config.CooldownPolicy;
import java.time.Duration;

RouterConfig resilient = config.toBuilder()
    .attemptTimeout(Duration.ofSeconds(30))  // give up on one attempt after 30s and fall back
    .callTimeout(Duration.ofSeconds(90))     // and on the whole call after 90s
    .maxRetries(2)                           // retry a 429/5xx/dropped connection on the same model twice first
    .retryDelay(Duration.ofSeconds(1))       // 1s, then 2s (a provider's Retry-After replaces it)
    .cooldown(CooldownPolicy.of(3, Duration.ofMinutes(2))) // after 3 failed attempts in a row, skip that model for 2 minutes
    .build();
```

- **Timeouts.** A timed-out attempt is `FAILED` in `getAttempts()`, with a reason like `attempt timed out after 30s`, and the router moves to the next candidate. When `callTimeout` runs out, you get a `RouterExhaustedException` with the attempts made so far. A streamed attempt that times out is reset (`onReset()`), and none of its later text reaches your listener. With a timeout set, the sync call runs each attempt on a virtual thread, interrupted on timeout. Without one, it runs on your thread as before.
- **Retries.** Only transient failures are retried: HTTP 408, 429, 500, 502, 503, 504 and 529, and I/O errors. Bad requests, bad credentials and timeouts aren't. A provider's `Retry-After` replaces `retryDelay`. If it asks for more than 30 seconds, or the wait would pass `callTimeout`, the router falls back instead. `AttemptRecord.getRetries()` says how many retries a failed candidate got. All retrying is the router's: the Anthropic and OpenAI clients the adapters build have the SDKs' own retries turned off. If you pass your own `AnthropicClient` or `OpenAIClient` to an adapter, its retry setting is kept and `maxRetries` comes on top. A custom adapter decides what's transient by overriding `isTransient(...)` and `retryAfter(...)`.
- **Cooldown.** A model that's cooling down is `SKIPPED` with a reason like `cooling down for another 42s after 3 failed attempt(s) in a row`. A provider-only entry picks another of that provider's models in the meantime. When the time is up, the count resets and the model is tried as normal. A success also resets it. If every candidate is cooling down, you get the usual `RouterExhaustedException`. The counts live in the `LlmRouter` instance, so reuse one router across calls. Calls whose config has no `cooldown` ignore them.

See §5.5 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md).

**Prompt caching.** On by default. On Anthropic, the router marks the parts of a request that repeat across calls (system instructions or tools, and the conversation so far) so later calls read them from Anthropic's cache at a tenth of the input price. OpenAI and the other providers cache on their own. `Usage` says what was cached:

```java
Usage usage = response.getUsage();
usage.getInputTokens();      // every input token, cached or not
usage.getCacheReadTokens();  // of which read from the cache
usage.getCacheWriteTokens(); // of which written to it (Anthropic bills writes at 1.25x the input price)
usage.getReasoningTokens();  // the part of getOutputTokens() spent reasoning, where the provider says
```

The cost estimate prices cached tokens at the cache rates. To send no cache markers, set `.promptCaching(false)` on the config. See §4.3 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md).

**Usage listener.** To track spend, tokens or latency without wrapping every call, give the router a `UsageListener`. It hears every attempt (succeeded, failed or skipped) and every call, including async, streaming and embedding calls:

```java
import io.github.manishpateluk.llmrouter.UsageListener;
import io.github.manishpateluk.llmrouter.model.AttemptUsage;
import io.github.manishpateluk.llmrouter.model.CallUsage;

LlmRouter router = new LlmRouter().withUsageListener(new UsageListener() {
    @Override
    public void onAttempt(AttemptUsage attempt) {
        metrics.timer("llm.attempt", "provider", attempt.getProvider().toString(), "outcome", attempt.getOutcome().toString())
               .record(attempt.getLatency());
    }

    @Override
    public void onCall(CallUsage call) {
        if (call.isSucceeded()) {
            metrics.counter("llm.cost.micros", "model", call.getModelUsed()).increment(call.getUsage().getEstimatedCostUsdMicros());
        }
    }
});
```

`withUsageListener` returns a new router that shares the original's adapters and cooldown state; the original stays silent. The listener runs on the thread that finished the attempt, so make it thread-safe and quick. If it throws, the router logs a warning and carries on. See §5.6 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md).

**Async:**

```java
router.completeAsync("What's the weather like on Mars?")
    .thenAccept(response -> System.out.println(response.getContent()));
```

**Callback style**, if you'd rather not work with `CompletableFuture` directly:

```java
router.completeAsync(
    request,
    response -> System.out.println(response.getContent()),
    error -> System.err.println("Failed: " + error.getMessage()));
```

**Tools, structured output, and file attachments** — combine them via `Request.builder()` (this is also how you reach every option shown above, all at once, if you need to):

```java
import io.github.manishpateluk.llmrouter.model.Request;
import io.github.manishpateluk.llmrouter.model.ToolDefinition;
import java.util.Map;

Request request = Request.builder()
    .prompt("What's the weather in Paris?")
    .tools(List.of(ToolDefinition.builder()
        .name("get_weather")
        .description("Look up current weather for a city")
        .parameters(Map.of(
            "type", "object",
            "properties", Map.of("city", Map.of("type", "string"))))
        .build()))
    .build();

Response response = router.complete(request);
response.getToolCalls().forEach(call ->
    System.out.println(call.getName() + " " + call.getArguments()));
```

The router never executes tool calls itself — it only returns what the model requested; running them is your application's job.

Tool definitions are validated before any provider is called. Each `name` must match `^[a-zA-Z0-9_-]{1,64}$` and be unique within the request, and `parameters` must be a JSON Schema object with `"type": "object"`. Use `Map.of("type", "object")` for a tool that takes no arguments. A violation throws `InvalidRequestException` (code `INVALID_REQUEST`) listing every problem at once. Attachments (`Request.builder().attachments(...)`) and a `responseSchema` for structured output work the same way — see [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) §3 and §4 for the full shape and how unsupported features get dropped and reported back to you.

**Large schemas on Anthropic.** Anthropic rejects a `responseSchema` whose compiled grammar is too large (roughly 60+ required fields, or 25+ arrays of strings, on Claude Sonnet 5.5) with a 400. Rather than fail over to another provider, the Anthropic adapter retries once on the same model with the schema in the prompt, as the prompt-fallback strategy does. It then checks that the reply is JSON matching the schema, and fails the attempt if it isn't. It logs a warning the first time it does this for a schema, and later calls with that schema skip the native attempt. With `StructuredOutputStrategy.NATIVE` there's no retry and the 400 fails the attempt as before.

**Requiring features instead of letting them drop.** By default, if the model routed to can't support tools, a schema, or an attachment, the router drops that feature, records it in `getDroppedFeatures()`, and sends the call anyway. When your code depends on a feature — an agent loop whose control flow lives in its tools, say — mark it required so incapable candidates are skipped instead:

```java
import io.github.manishpateluk.llmrouter.config.Feature;
import java.util.Set;

RouterConfig agentConfig = RouterConfig.builder()
    .route(List.of(RouteEntry.of(Provider.ANTHROPIC), RouteEntry.of(Provider.OPENAI)))
    .requiredFeatures(Set.of(Feature.TOOLS)) // also RESPONSE_SCHEMA, ATTACHMENTS
    .build();
```

- A skipped candidate shows up in `getAttempts()` as `SKIPPED`, with a reason such as `model does not support required feature(s) [tools]`.
- For provider-only entries (and the default route), the router picks only among that provider's models that support the required features. This works with `costOptimized(true)` too.
- If nothing qualifies, you get the usual `RouterExhaustedException`, and its `attempts()` list says why each candidate was skipped.
- A required feature the request doesn't use is ignored, so one config can serve turns with and without tools.
- `RESPONSE_SCHEMA` counts the prompt-fallback strategy as honoring the schema. Add `.structuredOutputStrategy(StructuredOutputStrategy.NATIVE)` if you need native structured output.
- A model that isn't in the capability table is skipped when the request uses a required feature, because there's no data to show it can honor it. When nothing required is in use, it's still attempted.

See §5.4 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) for the full rules.

**Attachments the model can't take.** Each attachment is checked on its own against the model's accepted media types (`ModelEntry.getInputMediaTypes()`) and its published limits (images per request, PDF pages and size). Only the ones it can't take are left out; `getDroppedFeatures()` contains `"attachments"` and `getDroppedAttachments()` says which and why. With `requiredFeatures(Set.of(Feature.ATTACHMENTS))`, a model must take every attachment to be tried, so a PDF only ever goes to models that read PDFs:

```java
Response response = router.complete(Request.builder()
    .prompt("What does this slide say?")
    .attachments(List.of(Attachment.builder().mediaType("application/pdf").data(pageBytes).filename("deck-p3.pdf").build()))
    .config(config.toBuilder().requiredFeatures(Set.of(Feature.ATTACHMENTS)).build())
    .build());
```

`ModelEntry.isSupportsVision()` and `isSupportsFileInput()` still work, derived from the media types. A `ModelEntry` you register with only those two flags keeps their old meaning.

**Repeated attachments in a multi-turn conversation are deduplicated automatically.** If you pass the same attachment (byte-identical content) on `Request.attachments` across several calls — the common case for a tool-calling loop that keeps a file in context turn after turn — the Anthropic and OpenAI adapters upload it once via that provider's Files API and reference it by file id on every later call instead of re-encoding and re-sending the bytes. There's nothing to opt into: just keep passing the attachment as you already do, and reuse the same `LlmRouter` instance across the conversation (which you should be doing anyway). A few things worth knowing:

- Anthropic dedupes both images and documents this way, and also tags every attachment content block with an ephemeral `cache_control` hint so Anthropic can reuse the surrounding prompt prefix cheaply.
- OpenAI dedupes documents only — Chat Completions has no file-id path for images, so image attachments always re-embed inline.
- It's a pure optimization: if the upload call itself fails for any reason, that attempt transparently falls back to full inline embedding rather than failing your request.
- The other providers (currently Perplexity, NVIDIA, Hugging Face, OpenRouter) have no equivalent mechanism this library can target generically, so attachments to them are re-sent inline on every call.

See §8.1.1 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) for the full design rationale.

**Intercepting requests right before they're sent** — an optional last-chance hook to inspect or modify the fully negotiated request for each attempt, e.g. to compress conversation history against that exact model's context window:

```java
import io.github.manishpateluk.llmrouter.RequestInterceptor;

RequestInterceptor interceptor = (provider, model, request) ->
    request.toBuilder().history(trimHistoryFor(model, request.getHistory())).build();

LlmRouter router = new LlmRouter(interceptor);
// or, combined with a custom adapter list: new LlmRouter(adapters, interceptor)
```

It runs after capability negotiation, so `request` is the exact form about to be sent for that attempt — and it runs once per candidate, so on fallback you see each candidate's own `provider`/`model` in turn. Returning `request` unchanged is a no-op, and supplying no interceptor at all (the default) behaves identically to today. If the hook itself throws, that attempt is recorded as failed and the router falls back to the next candidate rather than failing the whole call. See §4.1 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) for the full contract.

**Streaming** the response's text as it's generated, e.g. to show an answer while it's written:

```java
import io.github.manishpateluk.llmrouter.StreamListener;

Response response = router.completeStreaming(request, new StreamListener() {
    @Override
    public void onText(String delta) {
        ui.append(delta);
    }

    @Override
    public void onReset() {   // an attempt failed mid-stream; the next candidate starts over
        ui.clear();
    }
});
// response.getContent() is the full text; tool calls, usage etc. are on the response as usual.
```

Everything else is the same as `complete(request)`: routing, negotiation, the interceptor, fallback. All six built-in providers stream natively. A custom `ProviderAdapter` that doesn't override `sendStreaming` delivers its text in one piece, so the call works with any adapter. If your listener itself throws, the call ends with that exception straight away; the router doesn't fall back to another model.

`completeStreamingAsync(request, listener)` is the non-blocking form. It returns a `CompletableFuture<Response>` and follows the same rules. The listener is called from a background thread, one piece at a time and in order, so hand the text to your UI thread if your toolkit requires it:

```java
router.completeStreamingAsync(request, delta -> ui.appendLater(delta))
    .thenAccept(response -> ui.done(response));
```

See §4.2 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md).

**Citations.** When a provider reports the sources its answer draws on, they come back on the response, in a provider-neutral shape. Today that's Perplexity: the router switches on Perplexity's web search for every Perplexity call, whichever model it routes to:

```java
import io.github.manishpateluk.llmrouter.model.Citation;

RouterConfig perplexityConfig = RouterConfig.builder()
    .route(List.of(RouteEntry.of(Provider.PERPLEXITY)))
    .build();

Response response = router.complete("Who won the 2026 World Cup?", perplexityConfig);
for (int i = 0; i < response.getCitations().size(); i++) {
    Citation source = response.getCitations().get(i);
    System.out.println("[" + (i + 1) + "] " + source.getTitle() + " " + source.getUrl());
}
```

- **Numbering.** The list follows Perplexity's own numbering of its search results, so a `[1]` (or `[web:1]`) marker in `getContent()` refers to `getCitations().get(0)`. Whether the answer carries markers at all is up to the model. `perplexity/sonar` and the presets add them; ask for them in your system instructions if another model leaves them out.
- **Fields.** `getUrl()` is always set. `getTitle()` and `getSnippet()` are `null` when the provider doesn't give them.
- **Streaming.** Streamed calls fill `getCitations()` too. Citations arrive on the returned `Response`, not through the listener.
- **Other providers.** The list is empty for providers that don't report citations.

**Perplexity** runs on its [Agent API](https://docs.perplexity.ai/docs/agent-api/quickstart), which serves Perplexity's own models and other vendors' models through one endpoint. Route entries name a model by its Agent API id:

- **Seeded models.** `perplexity/sonar` (the grounded search model), `perplexity/kimi-k3`, `perplexity/glm-5.3`, `perplexity/glm-5.3-flash` and `perplexity/nemotron-3.5-super-120b-a12b` are in the capability table. A provider-only `RouteEntry.of(Provider.PERPLEXITY)` picks among them like any other provider.
- **Other vendors through Perplexity.** Ids such as `openai/gpt-6-luna` or `anthropic/claude-sonnet-5-5` work as explicit route entries too. They aren't in the seed table, so register them with `ModelCapabilityTable.registerModel(...)` if you want capability negotiation and cost-ordering for them.
- **Presets.** `RouteEntry.of(Provider.PERPLEXITY, "preset:fast")` (also `low`, `medium`, `high`, `xhigh`) runs one of Perplexity's [presets](https://docs.perplexity.ai/docs/agent-api/presets). A preset brings its own model, tools and output limit, and has no table row.
- **Cost.** Perplexity reports each call's actual cost, including its per-search fee (about $0.0025 per search). `getUsage()` carries that figure rather than the table's token-price estimate. Cost-optimized ordering, which happens before the call, still uses the table's token prices only.

**Upgrading from 1.0.x (Perplexity).** Perplexity retired Sonar Chat Completions, so this release moves the adapter to the Agent API. Code that uses only `Provider.PERPLEXITY` without naming a model needs no change. Otherwise:

- `sonar` becomes `perplexity/sonar`. `sonar-pro` and `sonar-reasoning-pro` no longer exist: use `preset:fast` and `preset:low` (Perplexity's own suggested replacements), or a seeded model. The old ids aren't aliased. Perplexity rejects them, and the router falls back to your next route entry.
- `PerplexityAdapter` moved from `provider.compatible` to `provider.perplexity`. This only matters if you construct adapters yourself.
- Some Perplexity models now support tools (`perplexity/kimi-k3`, `perplexity/glm-5.3`, `perplexity/glm-5.3-flash`, `perplexity/nemotron-3.5-super-120b-a12b`), so a `requiredFeatures(TOOLS)` route no longer skips Perplexity outright.

**Embeddings**, for semantic search:

```java
import io.github.manishpateluk.llmrouter.model.EmbeddingRequest;
import io.github.manishpateluk.llmrouter.model.EmbeddingResponse;

EmbeddingResponse response = router.embed(EmbeddingRequest.builder()
    .text("Acme pays net 30")
    .text("Globex pays on receipt")
    .build());
response.getVectors();     // one float[] per text, in order
response.getModelUsed();   // store this with the vectors: only same-model vectors are comparable
```

- **Which model.** With no `route`, the first available provider that offers embeddings is used, with its default model. Today that's OpenAI's `text-embedding-3-small`.
- **No silent fallback.** It doesn't fall back to other models unless you list them in `route`, because different models' vectors can't be compared.
- **Cost.** `response.getUsage()` reports input tokens and an estimated cost from the table's embedding prices (OpenAI's three embedding models are seeded). Read `getEstimatedCostUsdMicros()`, in millionths of a dollar: a typical embedding call costs far less than a cent, so `getEstimatedCostUsdCents()` is usually 0. To price a model the table doesn't list, register it with `ModelCapabilityTable.registerEmbeddingModel(...)`.
- **Custom adapters.** An adapter adds embeddings by overriding `defaultEmbeddingModel()` and `embed(...)`. See §3.2 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md).

**Handling exhaustion** (every candidate in the route failed or had no credentials):

```java
import io.github.manishpateluk.llmrouter.error.RouterExhaustedException;

try {
    Response response = router.complete("Hello");
} catch (RouterExhaustedException e) {
    e.attempts().forEach(attempt ->
        System.out.println(attempt.getProvider() + "/" + attempt.getModel() + ": " + attempt.getOutcome()));
}
```

## Learn more

This README only covers installing and calling the library; credential setup lives in the [main README](../README.md#configuring-credentials). For the full behavior spec — capability negotiation, the thinking-level model-selection heuristic, cost-optimized ordering, the Model Capability Table, error codes, and everything else — see [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) at the repo root.

## Developer Notes on push (because I'm too stupid to remember these steps)

1. Bump the version in java/pom.xml off 1.0.9-SNAPSHOT to a real release, e.g. 1.0.9
2. From java/: mvn clean deploy -Prelease
3. It'll prompt for your GPG passphrase, sign everything, and upload the bundle to Central
4. Go to the Central Portal → Deployments, find it, review the contents, and click Publish — it stays private until you do this
5. It typically takes 15–30 minutes to sync out to Maven Central and search.maven.org after you publish
