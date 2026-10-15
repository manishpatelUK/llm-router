# Roadmap

Planned work that isn't implemented yet. Each item says what's missing and the direction agreed so
far. Remove an item once it ships.

## Providers

- **Move Perplexity to its Agent API.** Perplexity ended support for Sonar Chat Completions on
  27 September 2026. Synchronous and streaming calls still work for now, but Perplexity is gradually
  rewriting them as Agent API requests (`POST /v1/agent`), so `PerplexityAdapter`'s current endpoint
  and the `sonar`, `sonar-pro` and `sonar-reasoning-pro` table rows are on borrowed time. Moving
  means:
  - **Adapter:** a Perplexity adapter for the Agent API instead of the OpenAI-compatible
    `chat/completions` base. Files go as `input_file` parts (`file_data` base64 or `file_url`; 30
    files and 50 MiB per request), images as `input_image` parts, and reasoning effort as
    `reasoning: {effort}`. Check how citations and search results come back there, so
    `Response.citations` keeps working.
  - **Table:** replace the Sonar rows with whatever the Agent API addresses. Perplexity maps
    `sonar`/`sonar-pro` to `preset: "fast"` and `sonar-reasoning-pro` to `preset: "low"`, so decide
    whether a row is a preset or a model. Then set `reasoningControl`, `inputMediaTypes` and limits
    per the update skill.
  - **Spec:** update the citations notes in `LIBRARY_SPEC.md` §3 and the streaming notes in §4.2
    if the Agent API's shapes differ.
