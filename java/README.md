# llm-router (Java)

The Java implementation of [`llm-router`](../README.md) — a single entry point for calling Anthropic, OpenAI, Perplexity, NVIDIA, Hugging Face, or OpenRouter, with automatic fallback between them, capability-aware request adaptation, and optional cost-optimized model selection. Full behavior spec: [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md).

## Installation

Include this in your `pom.xml`:

```xml
<dependency>
  <groupId>io.github.manishpateluk</groupId>
  <artifactId>llm-router</artifactId>
  <version>1.0.0</version>
</dependency>
```

Requires **Java 25+**.

## Configuring credentials

The router detects provider credentials from environment variables on first use — no explicit configuration required. For each provider, it checks a library-specific variable first, then falls back to that provider's common one:

| Provider | Env var (checked first) | Falls back to |
|---|---|---|
| Anthropic | `LLM_ROUTER_ANTHROPIC_API_KEY` | `ANTHROPIC_API_KEY` |
| OpenAI | `LLM_ROUTER_OPENAI_API_KEY` | `OPENAI_API_KEY` |
| Perplexity | `LLM_ROUTER_PERPLEXITY_API_KEY` | `PERPLEXITY_API_KEY` |
| NVIDIA | `LLM_ROUTER_NVIDIA_API_KEY` | `NVIDIA_API_KEY` |
| Hugging Face | `LLM_ROUTER_HUGGINGFACE_API_KEY` | `HF_TOKEN`, then `HUGGINGFACE_API_KEY` |
| OpenRouter | `LLM_ROUTER_OPENROUTER_API_KEY` | `OPENROUTER_API_KEY` |

You only need to set the key(s) for whichever provider(s) you actually want to use — the router routes only among providers it finds credentials for.

## Quick start

```java
import com.manishpateluk.llmrouter.LlmRouter;
import com.manishpateluk.llmrouter.model.Response;

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
import com.manishpateluk.llmrouter.model.Message;
import java.util.List;

List<Message> history = List.of(
    Message.user("What's 2+2?"),
    Message.assistant("4."));

Response response = router.complete("And 2+3?", history);
```

**With a `RouterConfig`** (explicit fallback order, thinking level, cost-optimized routing):

```java
import com.manishpateluk.llmrouter.config.RouteEntry;
import com.manishpateluk.llmrouter.config.RouterConfig;
import com.manishpateluk.llmrouter.config.ThinkingLevel;
import com.manishpateluk.llmrouter.provider.Provider;

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
import com.manishpateluk.llmrouter.model.Request;
import com.manishpateluk.llmrouter.model.ToolDefinition;
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

The router never executes tool calls itself — it only returns what the model requested; running them is your application's job. Attachments (`Request.builder().attachments(...)`) and a `responseSchema` for structured output work the same way — see [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) §3 and §4 for the full shape and how unsupported features get dropped and reported back to you.

**Repeated attachments in a multi-turn conversation are deduplicated automatically.** If you pass the same attachment (byte-identical content) on `Request.attachments` across several calls — the common case for a tool-calling loop that keeps a file in context turn after turn — the Anthropic and OpenAI adapters upload it once via that provider's Files API and reference it by file id on every later call instead of re-encoding and re-sending the bytes. There's nothing to opt into: just keep passing the attachment as you already do, and reuse the same `LlmRouter` instance across the conversation (which you should be doing anyway). A few things worth knowing:

- Anthropic dedupes both images and documents this way, and also tags every attachment content block with an ephemeral `cache_control` hint so Anthropic can reuse the surrounding prompt prefix cheaply.
- OpenAI dedupes documents only — Chat Completions has no file-id path for images, so image attachments always re-embed inline.
- It's a pure optimization: if the upload call itself fails for any reason, that attempt transparently falls back to full inline embedding rather than failing your request.
- The other providers (currently Perplexity, NVIDIA, Hugging Face, OpenRouter) have no equivalent mechanism this library can target generically, so attachments to them are unaffected.

See §8.1.1 of [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) for the full design rationale.

**Handling exhaustion** (every candidate in the route failed or had no credentials):

```java
import com.manishpateluk.llmrouter.error.RouterExhaustedException;

try {
    Response response = router.complete("Hello");
} catch (RouterExhaustedException e) {
    e.attempts().forEach(attempt ->
        System.out.println(attempt.getProvider() + "/" + attempt.getModel() + ": " + attempt.getOutcome()));
}
```

## Learn more

This README only covers installing and calling the library. For the full behavior spec — capability negotiation, the thinking-level model-selection heuristic, cost-optimized ordering, the Model Capability Table, error codes, and everything else — see [`LIBRARY_SPEC.md`](../LIBRARY_SPEC.md) at the repo root.

## Developer Notes on push (because I'm too stupid to remember these steps)

1. Bump the version in java/pom.xml off 1.0.0-SNAPSHOT to a real release, e.g. 1.0.0
2. From java/: mvn clean deploy -Prelease
3. It'll prompt for your GPG passphrase, sign everything, and upload the bundle to Central
4. Go to the Central Portal → Deployments, find it, review the contents, and click Publish — it stays private until you do this
5. It typically takes 15–30 minutes to sync out to Maven Central and search.maven.org after you publish
