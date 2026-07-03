## Sverklo — Code Intelligence

This project has the sverklo MCP server installed. Sverklo is a code-intelligence index: ranked search, dependency graph, persistent memory. Use it as the **default** tool for code discovery in this repo.

### Always Do

- **MUST call `sverklo_overview` before exploring an unfamiliar directory.** It returns the PageRank-ranked map of the codebase in one call — much cheaper than `ls` + `Read` loops.
- **MUST use `sverklo_search` instead of Grep for any query that is conceptual or fuzzy** ("how does auth work", "anything related to billing", "where do we handle retries"). Grep is for exact strings only.
- **MUST use `sverklo_lookup` to find a symbol's definition** by name — never grep + Read for this.
- **MUST run `sverklo_impact` before renaming, deleting, or changing the signature of any function/class/method** that may be called from elsewhere. Report the blast radius (callers, depth) to the user before editing.
- **MUST use `sverklo_refs` to enumerate callers of a symbol.**
- **MUST use `sverklo_deps` to see imports + importers of a file** before moving or splitting it.
- **MUST call `sverklo_remember` when the user corrects you** with phrasing like "stop X", "never X", "always Y", "don't Y", "prefer Z", "remember that I want Q", "actually, do W". Save with `category:correction` (stop/never/don't) or `category:preference` (prefer/want/like), `kind:semantic`, and the user's instruction as content. Save before continuing the response. Do not ask permission — corrections are explicit instructions to persist behavior across sessions.
- **MUST call `sverklo_recall` at the start of work** on a non-trivial task to surface prior decisions and corrections.

### Never Do

- **NEVER use Grep when the query is conceptual.** Grep cannot find "the auth flow" — sverklo_search can.
- **NEVER edit a function or class without first running `sverklo_impact`** on it. Silently breaking a caller is the most expensive bug this codebase produces.
- **NEVER ignore HIGH or CRITICAL impact warnings** without surfacing them to the user.
- **NEVER rename symbols with find-and-replace.** Use `sverklo_refs` first; it knows which "foo" is the function and which is a string.
- **NEVER save routine task summaries to memory.** `sverklo_recall` is only useful when hits are signal-dense — save only (a) bugs that took >1h to debug, (b) recurring mistakes, (c) non-obvious architectural decisions, (d) audit findings needing user judgment.
- **NEVER re-read a file sverklo just returned a path for.** Use `sverklo_lookup` for the specific symbol instead.

### When Grep / Read still wins

| Task | Tool |
|---|---|
| Exact string match (`"TODO(alice)"`, error message text) | Grep |
| Read a known file at a known path | Read |
| Inspect a specific line range | Read with offset/limit |

### Exploration order

`sverklo_overview` (1 call) → `sverklo_search` (1 call) → `sverklo_lookup` on the top hit → `sverklo_refs` / `sverklo_impact` only if you need the blast radius. If you've made 5 sverklo calls and still don't have the answer, **stop and ask a clarifying question** — don't burn 10 more.

### Output discipline

No preambles ("Here are the results", "Great question"), no closing affirmations, no em-dashes as conversational pauses. State the finding, show the fix, stop. User instructions override this file.
