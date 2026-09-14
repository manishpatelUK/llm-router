# Model Capability Table — Scoring Heuristics & Update Methodology

> Companion to `LIBRARY_SPEC.md` §7 (Model Capability Table). This document is the source-of-truth methodology for populating and refreshing `model-capability-table.json` at the project root. It exists so that re-scoring done today, or by a different person/session in six months, produces comparable, consistent numbers rather than arbitrary re-guesses.
>
> The actual update workflow is automated as a Claude Code skill: `.claude/skills/update-model-capability-table/SKILL.md`. Run `/update-model-capability-table` to execute it. This document is the methodology that skill follows — read it if you want to understand *why* a number is what it is, or if you're updating the table by hand.

## Scope

Per `LIBRARY_SPEC.md` §8, the table should cover the minimum supported providers. In practice we made two deliberate scoping decisions for the seed table:

- **Hugging Face is excluded entirely.** It hosts far too many models to seed meaningfully, and any fixed snapshot would be stale and arbitrary almost immediately. Users of the library are expected to register their own Hugging Face entries via `registerModel()` (§7.1) for the specific models they use.
- **OpenRouter entries are limited to models NOT already represented under `anthropic`, `openai`, or `perplexity`.** OpenRouter re-exposes most first-party providers' models too, and listing e.g. `openrouter → anthropic/claude-opus-5` alongside the first-party `anthropic → claude-opus-5` entry would be redundant and just another thing to fall out of sync. OpenRouter's entries in this table exist specifically to cover model families the other four providers don't offer at all: Google Gemini, xAI Grok, Meta Llama, DeepSeek, and similar. If you need a first-party-covered model *via* OpenRouter specifically (e.g. for its aggregator-level fallback behavior), add it yourself with `registerModel()`.

## Data sources (in priority order)

1. **The provider's own official pricing/docs page** — always the first choice. For Anthropic, the `claude-api` Claude Code skill bundles current, actively-maintained pricing/capability tables — prefer it over web search for Anthropic models.
2. **The provider's own model/API reference page** for context window, max output tokens, and capability flags (structured output / tool use / vision).
3. **OpenRouter's per-provider model pages** (`openrouter.ai/<vendor>`) when the provider doesn't publish clean self-serve pricing (useful for Google, xAI, Meta, DeepSeek, Mistral, etc.) — OpenRouter passes through the vendor's own rate plus a small markup, so treat its listed price as *approximately* right, not exact, when sourcing a first-party entry from it.
4. **Third-party pricing aggregators** (e.g. tokenando, costbench, benchlm) only as a last resort when 1–3 fail (paywalled, blocked, rate-limited) — cross-check numbers against at least one other source when possible, since these sites vary in freshness and accuracy.

Never fabricate a number. If a field genuinely can't be found after checking the above, use a clearly-reasoned estimate (see "Estimation fallbacks" below) — don't leave the field blank, since every language implementation expects the full `ModelEntry` shape.

## Cost fields

`inputCostPerMillionTokens` / `outputCostPerMillionTokens` — pull directly from the source, in USD, per million tokens, standard (non-batch, non-cached) rate. Don't use batch or cached-input discount rates — the router's cost-optimized ordering (`LIBRARY_SPEC.md` §5.3) reasons about standard-rate cost; a language implementation is free to layer batch/cache-aware pricing on top later, but the seed table should reflect the baseline rate every request pays.

## `thinkingScore` (0–10)

Intended to be roughly comparable *across* providers, even though the §7.2 selection heuristic only ever compares within one provider's own range at call time — comparability across providers still matters for humans auditing the table and for the cost-optimized "qualifying set" tolerance band.

**Step 1 — base score by tier**, judged by the model's position within its own provider's current lineup (flagship / mid / small), not by absolute benchmark numbers (which aren't consistently available across every provider):

| Tier | Base range | Examples of tier signals |
|---|---|---|
| Frontier/flagship | 8.5–10 | Top of the provider's pricing table; marketed as the provider's most capable model; typically the most expensive |
| Balanced mid-tier | 6–8 | Provider's "recommended default" or "best balance of cost/capability" model |
| Fast/small | 3–5.5 | Marketed as "mini"/"nano"/"flash"/"lite"/"-8b" or similar; cheapest tier |
| Legacy (superseded but still served) | (tier base) − 1 | Explicitly marked legacy/deprecated-but-available in the provider's own docs |

**Step 2 — adjustments** (apply all that hold, then round to the nearest 0.5):

- **+0.5 to +1.0** if the model has an always-on or default-on deep/extended/adaptive reasoning mode (test-time compute), since this is a direct capability delta over a same-tier model without it.
- **+0.5** if a specific, dated benchmark result you found during research (LMArena/Chatbot Arena, GPQA Diamond, LiveBench, SWE-bench, etc.) places the model at or near the top of its class at time of scoring. Note the benchmark and date if you use this — it's the kind of detail that goes stale fastest.
- **−0.5** if the model is narrow-purpose (a safety/guard classifier, an embedding model, a vision-only or transcription-only model) being scored for general chat/completion use it wasn't primarily built for.

## `speedScore` (0–10)

Inversely related to model size and reasoning depth — a proxy for output tokens/sec and time-to-first-token, not a precise latency benchmark.

**Step 1 — base score by tier** (inverse of the thinkingScore tiering):

| Tier | Base range |
|---|---|
| Frontier/flagship (large, deep reasoning) | 2–4 |
| Balanced mid-tier | 5–7 |
| Fast/small | 8–10 |

**Step 2 — adjustments**:

- **+1.0** if the provider explicitly markets the model with a speed-tier name ("flash", "fast", "turbo", "instant", "mini" combined with an explicit low-latency claim).
- **−1.0** if deep/extended/adaptive reasoning is **on by default** (adds latency even to simple queries — this is the speed-side mirror of the thinkingScore reasoning-mode bonus).
- **+0.5** for a small, dense, distilled open-weight model (roughly <10B parameters) — these run meaningfully faster than their thinkingScore tier alone would suggest.

## Context window, max output tokens

Pull directly from the provider's docs when published. **When max output tokens isn't published** (common for OpenAI/OpenRouter-listed third-party models, which often document context window but not the output cap), use a conservative same-family estimate rather than guessing wildly:

- Flagship/mid-tier models with ≥1M context: estimate 32,768–65,536 max output tokens (large-context frontier models increasingly support large output too; use the higher end if the model family is known for long-form generation, e.g. Gemini).
- Small/fast/open-weight models: estimate 8,192–16,384.
- Tiny models (≤8K context): cap the estimate at the context window itself (e.g. a 4K-context model can't output more than ~4K tokens).

Mark any estimated (non-sourced) field for follow-up the next time the table is refreshed — the skill's report step (see the SKILL.md) should call these out explicitly rather than silently presenting them as sourced.

## Capability flags (`supportsStructuredOutput`, `supportsTools`, `supportsVision`)

Check the provider's own feature/model-comparison page or API reference; these are usually stated plainly (a feature support matrix or a per-model "capabilities" list). Rules of thumb when a specific model isn't individually documented but its provider's *platform* feature is:

- If the provider's tool-calling / structured-output feature is documented as available "for all current models" or similar blanket statement, apply `true` to every current-generation model from that provider, `false` for explicitly legacy/deprecated ones.
- Very small (roughly <5B parameter) open-weight models frequently have unreliable or unsupported tool-calling and JSON-schema-constrained decoding even when the hosting platform technically exposes the parameter — default these to `false` unless the model card explicitly claims support.
- Text-only model families (no documented image input) get `supportsVision: false` even if a same-family vision variant exists under a different model ID — score the exact model ID, not the family.

## Worked example

`claude-sonnet-5`: Anthropic's docs describe it as "the best combination of speed and intelligence" — a mid-tier, balanced positioning (not flagship, not the fast/cheap tier) → thinkingScore base 7.5 (mid-tier midpoint), no reasoning-always-on bonus (adaptive thinking is available but not always-on the way Claude Fable 5.1's is) → **7.5**. Comparative latency is documented as "Fast" (one step better than Opus's "Moderate") → speedScore base 6 (mid-tier), +1 for the explicit "fast" positioning → **7**.

## Updating the table

Run the `update-model-capability-table` skill (`/update-model-capability-table`). At a high level it will, per provider:

1. Re-fetch current pricing/lineup from the priority-ordered sources above.
2. Diff against the existing `model-capability-table.json` — flag any model that's been retired/renamed, and any new model worth adding.
3. Recompute `thinkingScore`/`speedScore` using the heuristic above (not just copy old scores forward — tiers shift as lineups change).
4. Rewrite `model-capability-table.json`, bump `lastUpdated`, and report what changed and what was estimated vs. sourced.

Do this periodically (pricing and lineups move fast — see the "best effort, expected to go stale" note in `LIBRARY_SPEC.md` §7.1) and whenever you notice the table is visibly wrong (a model in the table has been retired, a new flagship has shipped, etc.).
