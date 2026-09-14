---
name: update-model-capability-table
description: Refresh, extend, or prune model-capability-table.json (provider pricing, model lineups, capability flags), using the scoring heuristics in MODEL_CAPABILITY_HEURISTICS.md. Use when the user asks to update, refresh, add a model to, or clean up the model capability table, or when model pricing/lineup information is known to be stale.
---

# Update Model Capability Table

Maintains `model-capability-table.json` at the project root so it reflects current provider pricing, model lineups, and capability flags. This is the "skill to keep it updated" referenced in `LIBRARY_SPEC.md` §7.1.

**Before doing anything else, read `MODEL_CAPABILITY_HEURISTICS.md` in full.** It defines the scoring methodology (`thinkingScore`/`speedScore` heuristics), the data-source priority order, the scoping decisions (Hugging Face excluded; OpenRouter limited to non-overlapping vendors), and the estimation fallbacks for missing fields. This SKILL.md is the *procedure*; that document is the *methodology* — don't improvise scores that contradict it.

## Arguments

Parse whatever free-text arguments were passed to the skill (e.g. `/update-model-capability-table add openrouter/qwen-3-max`) to pick a mode. Modes are mutually exclusive — match the first one that applies, in this order:

| Invocation | Mode | Behavior |
|---|---|---|
| `add <provider>/<model-id> [<provider>/<model-id> ...]` | **Add** | Research and score only the named model(s) and insert them. Does not touch any other existing row. Still runs the deprecation scan (§3) scoped to that model's provider only, since you're already looking at that provider's current lineup. |
| `prune` or `remove-deprecated` | **Prune** | Runs only the deprecation scan (§3) across every provider already in the table and removes what's confirmed retired/renamed. Does not re-price, re-score, or add anything. |
| `provider:<name>` (e.g. `provider:openai`, `provider:openrouter`) | **Provider refresh** | Runs the full refresh procedure (research, diff, score, deprecation scan) but scoped to that one provider only. Other providers' entries are left untouched. |
| *(no arguments)* | **Full refresh** (default) | Runs the full procedure below across every provider currently in the table. |

If the arguments don't match any of these shapes, ask the user to clarify rather than guessing — in particular, don't silently treat an ambiguous string as a model ID to add.

## Procedure

### 1. Load current state

Read `model-capability-table.json`. Note its `lastUpdated` date and the full existing model list grouped by `provider`. In **Add** mode, also confirm the named model(s) aren't already present (if one is, treat it as an update to that row instead of a duplicate insert).

### 2. Re-research the relevant provider(s)

Determine which providers to research from the mode: all providers currently in the table (Full refresh), the one named provider (Provider refresh / Add, using that model's provider), or none (Prune — skip straight to §3 using only what a quick lineup/deprecation check turns up, not a full re-price).

Using the source priority order from `MODEL_CAPABILITY_HEURISTICS.md` § Data sources:

- **`anthropic`**: invoke the `claude-api` skill (or read its bundled model/pricing reference directly) rather than web-searching — it's actively maintained and authoritative for Anthropic model IDs, pricing, and capability flags.
- **`openai`**: WebFetch `https://openai.com/api/pricing/` first. If blocked (403/429 — this happens), fall back to a pricing aggregator (WebSearch for one, e.g. a site that tabulates OpenAI's current per-model rates) and cross-check the numbers look internally consistent (flagship > mid > mini > nano pricing ordering).
- **`perplexity`**: WebFetch `https://docs.perplexity.ai/getting-started/pricing` (or current equivalent — WebSearch if the path has moved).
- **`nvidia`**: WebFetch `https://build.nvidia.com/models` for the current featured/popular model list, then a pricing aggregator or NVIDIA's own pricing docs for per-model rates (NIM pricing is not always published as clean $/1M-token rates — note where you had to estimate).
- **`openrouter`**: WebFetch the relevant per-vendor OpenRouter pages (`https://openrouter.ai/google`, `https://openrouter.ai/x-ai`, `https://openrouter.ai/meta-llama`, `https://openrouter.ai/deepseek`, `https://openrouter.ai/mistralai`, etc.) for whichever vendor families are already represented in the table, plus any the user asked to add. **Skip any model whose vendor is also `anthropic`, `openai`, or `perplexity`** — those stay out of the `openrouter` section per the scoping decision (avoid duplicate listings of the same underlying model).

### 3. Detect and remove deprecated models

Runs in every mode except Add-for-a-different-provider (where it's still worth a quick check of that one provider). For each researched provider, cross-check every existing table row against the provider's **current** model list:

- Check the provider's own deprecation/sunset/legacy-models page or changelog if one exists (Anthropic, OpenAI, and Perplexity all publish these) — this is the most reliable signal, since it states retirement dates explicitly.
- If a model in the table no longer appears in the provider's current model/pricing listing at all, treat it as retired even without an explicit deprecation notice — providers don't always publish sunset notices before quietly dropping a model from the docs.
- If a model was renamed (same underlying weights, new model ID — providers sometimes do this at a version bump), treat it as a rename: remove the old row, add the new ID, and carry forward pricing/scores only if you've confirmed they still apply (re-verify rather than assume).
- A model just moving down the page (no longer the flagship because a newer one shipped) is **not** deprecation — don't remove it on that basis alone, only re-tier its score per §4.

Remove confirmed-deprecated rows. List every removal (model ID, provider, and why you believe it's deprecated) in the final report — don't remove silently.

### 4. Diff against the existing table (Full refresh / Provider refresh / Add)

For each provider in scope, compare what you found against the existing entries:
- **Still current, pricing/specs unchanged** — leave as is.
- **Still current, pricing/specs changed** — update the numeric fields; re-derive `thinkingScore`/`speedScore` per the heuristic (don't just carry the old score forward — re-run Step 1/Step 2 of the heuristic, since the model's relative tier position may have shifted if the provider added a new flagship above it, etc.).
- **New model worth adding** (Full/Provider refresh only — in Add mode this is just the model(s) the user named) — new flagship, new budget tier, or a model the user specifically asked about. Don't try to add every model a provider ships; keep roughly the same shape as the existing seed (a small representative set per provider: flagship / mid / fast-cheap, not an exhaustive catalog).

### 5. Score every changed or new entry

Apply `MODEL_CAPABILITY_HEURISTICS.md` § `thinkingScore` and § `speedScore` exactly: base tier score, then the documented adjustments, rounded to the nearest 0.5. For context window / max output tokens / capability flags, prefer sourced values; when a field genuinely isn't published, use the estimation fallbacks documented there — and track which fields you estimated vs. sourced for the report.

### 6. Rewrite the file

Update `model-capability-table.json`:
- Keep the exact `ModelEntry` shape from `LIBRARY_SPEC.md` §7.1 — don't add or rename fields.
- Bump `lastUpdated` to today's date (`YYYY-MM-DD`) whenever any row changed (Prune mode counts if it removed anything; a no-op run that changed nothing can leave the date as is).
- Keep the `notes` field's scoping explanation intact (edit only if the scoping decision itself changed).
- Validate the result is syntactically valid JSON before finishing (e.g. `node -e "JSON.parse(require('fs').readFileSync('model-capability-table.json','utf8'))"` or equivalent).

### 7. Report

Summarize for the user, scoped to what actually ran: what changed (added/removed/re-priced/re-scored, per provider), which removals were deprecations and why, and — importantly — which fields in new or updated entries were **estimated** rather than sourced from a provider's own docs, so they know what to double-check if precision matters for their use case.

## Notes

- This skill trusts `MODEL_CAPABILITY_HEURISTICS.md` as the methodology source of truth. If you find yourself wanting to score differently than that document says, either follow the document or propose an edit to it — don't silently diverge, since the whole point is that re-scoring stays consistent across runs and people.
- If the user asks to add a provider or model family not currently in the table (e.g. a specific Hugging Face model), that's a normal `registerModel()`-style addition — same as **Add** mode, just a new row rather than an update to an existing one.
