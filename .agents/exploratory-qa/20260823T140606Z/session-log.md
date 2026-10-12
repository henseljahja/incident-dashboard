# Session Log — 20260823T140606Z

plan: 2026-08-20-incident-dashboard
target: demo stack at http://localhost:8090 (api :8081, oracle :1523)
account: `admin` / ADMIN role — the only account seeded into the demo stack

## Honesty note on timestamps
Passes below are logged in execution order. Per-pass wall-clock stamps were not captured at
the moment each pass ran, so rather than back-fill invented `date -u` values this log records
the run window and the true ordering. The skill asks for per-pass stamps; this run does not
meet that bar and the gap is recorded rather than papered over.

Run window: 2026-08-23T14:06:06Z -> 2026-08-23T14:5xZ (single continuous session).

## Passes

| # | Flow | Type | Outcome |
|---|---|---|---|
| 1 | login | happy | pass — lands on /ops, menus render |
| 2 | login | adversarial | pass — wrong password shows a visible error, no stack trace |
| 3 | login | stress | pass — double-submit does not double-post |
| 4 | ops KPI | happy | pass — 7 cards, values match the API |
| 5 | ops KPI | adversarial | Bug 17 — cards are buttons with no affordance |
| 6 | ops charts | happy | Bug 3 (heatmap contrast), Bug 13 (Unknown sentinel presentation) |
| 7 | ops charts | adversarial (empty result set) | Bug 12 — panels collapse to blank boxes |
| 8 | ops filters | happy | pass — all 8 controls apply |
| 9 | ops filters | adversarial | Bugs 4, 5 — 16 hostile inputs, 5 x 500, 3 silent coercions |
| 10 | ops filters | a11y sweep | Bug 8 — one label resolves to a SPAN |
| 11 | ops table | happy | pass |
| 12 | ops table | adversarial (page/size boundaries) | Bug 5; size=100000 correctly clamps to 200 |
| 13 | ops table | hit-target sweep | Bug 9 — 23 controls under 24px |
| 14 | drawer | happy | pass |
| 15 | drawer | adversarial (filter out the open row) | Bug 15 (2 of 2) |
| 16 | drawer | stress (Esc, repeated open/close) | pass |
| 17 | drawer | config review | Bugs 14, 16 |
| 18 | exec | happy + empty month | pass |
| 19 | wallboard | happy + stale banner | pass; dark mode spot-checked here |
| 20 | navigation | happy | Bug 6 — /admin/users blank |
| 21 | navigation | adversarial (unknown URL) | Bug 7 — no catch-all |
| 22 | responsive | 617px viewport | **Bug 1 (blocker)** — sidebar overlays content |
| 23 | contrast sweep | computed-style pass | Bugs 2, 10, 11 |
| 24 | test-suite review | static | Bug 18 — no coverage of the new shell |

## Corrections made during the run
- A first contrast script reported the stale banner at 1.12:1. That was a **false positive**:
  the script could not parse `oklch()`. Rewritten to resolve colours natively via canvas; the
  banner then measured fine and was dropped. Only the four surviving failures are reported.
- `Unknown (999999)` was initially written up as a data-labelling bug. Reading
  `V1__config_tables.sql:112-128` showed it is deliberate — the sentinel exists so a stale
  `REF_` seed is visible. Bug 13 was rewritten as a presentation finding.
- InputChips was nearly reported as broken because typing + Enter created no chip through
  automation. A synthetic `KeyboardEvent` with `keyCode:13` did create the chip and apply the
  filter, so the component is fine and the automation was at fault. Not reported as a bug.

## Blocked / not run
- Role-scoped sidebar comparison: VIEWER/EXEC/WALLBOARD accounts exist only on the test
  classpath, not in the demo stack.
- Real browser file-save of the Excel export (verified at the API layer instead).
- Concurrent-session / two-tab behaviour.
