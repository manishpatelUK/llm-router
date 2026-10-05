# llm-router (Java)

The Java implementation of [`llm-router`](../README.md) — a single entry point for calling Anthropic, OpenAI, Perplexity, NVIDIA, Hugging Face, or OpenRouter, with automatic fallback between them, capability-aware request adaptation, and optional cost-optimized model selection. Full behavior spec: [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md).

## Installation

Include this in your `pom.xml`:

```xml
<dependency>
  <groupId>io.github.manishpateluk</groupId>
  <artifactId>llm-router</artifactId>
  <version>1.0.5</version>
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

Build a `RouterConfig` once and reuse it across calls — it's not meant to be rebuilt per request.

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
- Models that aren't in the capability table are still attempted, because there's no data to rule them out.

See §5.4 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) for the full rules.

**Repeated attachments in a multi-turn conversation are deduplicated automatically.** If you pass the same attachment (byte-identical content) on `Request.attachments` across several calls — the common case for a tool-calling loop that keeps a file in context turn after turn — the Anthropic and OpenAI adapters upload it once via that provider's Files API and reference it by file id on every later call instead of re-encoding and re-sending the bytes. There's nothing to opt into: just keep passing the attachment as you already do, and reuse the same `LlmRouter` instance across the conversation (which you should be doing anyway). A few things worth knowing:

- Anthropic dedupes both images and documents this way, and also tags every attachment content block with an ephemeral `cache_control` hint so Anthropic can reuse the surrounding prompt prefix cheaply.
- OpenAI dedupes documents only — Chat Completions has no file-id path for images, so image attachments always re-embed inline.
- It's a pure optimization: if the upload call itself fails for any reason, that attempt transparently falls back to full inline embedding rather than failing your request.
- The other providers (currently Perplexity, NVIDIA, Hugging Face, OpenRouter) have no equivalent mechanism this library can target generically, so attachments to them are unaffected.

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

1. Bump the version in java/pom.xml off 1.0.5-SNAPSHOT to a real release, e.g. 1.0.5
2. From java/: mvn clean deploy -Prelease
3. It'll prompt for your GPG passphrase, sign everything, and upload the bundle to Central
4. Go to the Central Portal → Deployments, find it, review the contents, and click Publish — it stays private until you do this
5. It typically takes 15–30 minutes to sync out to Maven Central and search.maven.org after you publish
