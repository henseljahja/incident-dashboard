# Bug Report — 20260823T140606Z

plan: 2026-08-20-incident-dashboard
qa_run_id: 20260823T140606Z
generated_at: 2026-08-23T14:06:06Z

## Summary

18 findings: **1 blocker, 8 major, 9 minor.**

Top 3 most concerning:

1. **Any viewport below 768px is unusable (Bug 1).** The sidebar overlays the whole page —
   nav items sit on top of the KPI cards, the logo covers the page title. Regressed in
   `6dc7e52`, which is my change.
2. **Every primary button fails contrast at 1.67:1 (Bug 2).** White on Yokke cyan-500 —
   that is Sign in, Export and Change password, i.e. the primary action on every screen.
3. **Five malformed query params return 500 instead of 400 (Bug 4).** Each logs at ERROR
   as an unhandled exception, so real faults are buried in noise from bad input.

### Evidence convention
No PNGs were captured this run — `screenshots/` is empty and no bug below cites one.
Visual findings are evidenced by measured DOM geometry and computed styles taken from the
running demo stack, which is reproducible and more precise than an image.

---

## Bug 1: Sidebar overlays all content below `md` — mobile completely unusable

**Severity:** blocker
**Flow:** any authenticated view under 768px
**Frequency:** always

### Repro
1. Open `http://localhost:8090/ops` at a viewport narrower than 768px (verified at 617px).
2. Observe the page.

### Expected
Sidebar off-canvas, reachable via the hamburger that is already in the header.

### Actual
The sidebar renders at `translate-x-0` while the content's `ml-64` offset is gated on `md`,
so the two occupy the same space. Measured on the running app:

```
innerWidth 617
aside  x=0  y=0  w=256  h=812
       class="... fixed inset-y-0 left-0 z-50 h-screen w-64 ... translate-x-0"
content offset: none (ml-64 is md:-gated)
```

Visually: the Yokke logo lands on top of "Operations", "Operations/Wallboard/Executive/
User Management" sit over the BACKLOG and BREACHED cards, and the signed-in user card
floats over the heatmap. Nothing is readable and the nav cannot be dismissed.

### Evidence
- DOM geometry above, read from the live app.
- Console: none. This is pure layout.

### Suggested fix
Default `isSidebarActive` to collapsed below `md` and show the scrim when it is open. yob
sidesteps this by not rendering the rail at all on mobile (`v-if="!isMobileOnly"`); here the
hamburger already exists, so making it off-canvas is the honest fix rather than hiding nav.

---

## Bug 2: Every primary button fails WCAG AA — white on cyan-500 is 1.67:1

**Severity:** major
**Flow:** all — Sign in, Export, Change password
**Frequency:** always

### Repro
1. Open any screen with a primary Button.
2. Measure foreground `#ffffff` against background `#59d8f8`.

### Expected
>= 4.5:1 for 14px text.

### Actual
**1.67:1.** Yokke's brand cyan is a LIGHT tint; PrimeVue's Aura preset assumes a mid-tone
primary and pairs it with white. Copying the palette from yob without re-checking that
pairing carried the problem across.

### Evidence
Measured against canvas-resolved computed styles so `oklch()` values resolve natively — an
earlier naive parse produced a false positive on the stale banner and was discarded.

Candidates measured:
| Pairing | Ratio | Verdict |
|---|---|---|
| white on cyan-500 (current) | 1.67 | fail |
| white on cyan-700 | 3.14 | fail |
| white on cyan-800 | 6.46 | passes, loses the brand colour |
| **`#022c38` on cyan-500** | **8.88** | **passes AND keeps the brand colour** |

### Suggested fix
Set the preset's `primary.contrastColor` to `#022c38` rather than darkening the button —
keeps Yokke cyan as the button colour, which is the point of having a brand palette.

---

## Bug 3: The heatmap's highest-count cell is dark-on-dark at 1.73:1

**Severity:** major
**Flow:** Ops -> Impact x Urgency
**Frequency:** always

### Repro
1. Go to `/ops`. The busiest cell (93 incidents in the demo data) renders dark navy.

### Expected
The cell carrying the largest number is the one a responder most needs to read.

### Actual
`#171717` on `#1e3a8a` = **1.73:1**. The intensity ramp darkens the background as the count
rises but the text colour never changes, so the MORE important a cell is, the less legible
it gets. White on that navy measures 10.36:1.

---

## Bug 4: Five malformed query params return 500 instead of 400

**Severity:** major
**Flow:** Ops filters / any direct API call
**Frequency:** always

### Repro
```
GET /api/incidents?dateFrom=not-a-date                    -> 500
GET /api/incidents?dateFrom=2026-02-31                    -> 500
GET /api/incidents?dateFrom=9999-01-01&dateTo=9999-12-31  -> 500
GET /api/incidents?size=abc                               -> 500
GET /api/incidents?hasWorkaround=maybe                    -> 500
```

### Expected
400 naming the offending parameter. These are all client errors.

### Actual
500 "An internal error occurred", and `GlobalExceptionHandler` logs each at ERROR as an
*unhandled* exception. Anyone watching the error log cannot distinguish a real fault from
someone typing a bad date into a URL.

The 9999 case has a distinct root cause worth keeping separate:
`ORA-17268: Year value is out of range` — the query does `DATE_CREATE < dateTo + 1 day`, and
`9999-12-31 + 1` overflows Oracle's maximum year. That is a genuine boundary bug, not just
a missing validator.

### Evidence
- Server log: `ORA-17268`; `Unhandled exception [correlationId=04c6e54b-...]`

### Suggested fix
Bind these as typed params so Spring raises `MethodArgumentTypeMismatchException`, map that
(plus `DateTimeParseException`) to 400, and clamp `dateTo` below Oracle's max date.

---

## Bug 5: Numeric paging params are silently coerced while text ones 500

**Severity:** minor
**Flow:** Ops table paging
**Frequency:** always

### Actual
`size=0` -> 200 (returns 50). `size=-5` -> 200 (returns 50). `page=-1` -> 200 (page 0).
`size=abc` -> 500. Out-of-range numbers are silently clamped; non-numeric text is a 500. A
caller asking for `size=0` gets 50 rows with no indication the request was altered.

### Expected
One consistent policy — reject all invalid values with 400, or clamp all of them.

### Note
`size=100000` correctly clamps to 200, so the server-side cap does hold. That bounds
deferred follow-up #5 (paging reconstructing an export without an audit row) to 200/page.

---

## Bug 6: `/admin/users` is in the sidebar but renders a blank page

**Severity:** major
**Flow:** navigation
**Frequency:** always

### Repro
1. Log in as `admin`.
2. Click "User Management" in the sidebar.

### Expected
Either a working screen, or no such entry.

### Actual
Completely blank page — `#app` innerHTML is 7 bytes. No 404, no redirect, no console error.

The backend seeds an `incident.admin` menu with `route = /admin/users` in
`V5__seed_rbac.sql`, and the new sidebar faithfully renders every menu the API returns, so
fixing the `PermissionMenu` type in `6dc7e52` exposed a screen that was never built.

### Suggested fix
Either build the screen or stop seeding the menu. Do NOT special-case the route in the
sidebar — the point of the menu payload is that the server decides what exists.

---

## Bug 7: No catch-all route — any unknown URL is a blank white page

**Severity:** major
**Flow:** navigation, stale/shared links
**Frequency:** always

### Repro
1. Visit `http://localhost:8090/totally-made-up-route`.

### Expected
A 404 view with a way back.

### Actual
Blank page, no message, no navigation. A stale bookmark or a typo strands the user with no
recourse but to retype the URL. `router/index.ts` declares no `/:pathMatch(.*)*` route.

---

## Bug 8: `HAS WORKAROUND` label points at a `<span>`, not a form control

**Severity:** major
**Flow:** Ops filters; screen-reader and label-click users
**Frequency:** always

### Repro
```js
const l = [...document.querySelectorAll('label')].find(x => x.htmlFor === 'filter-has-workaround')
document.getElementById(l.htmlFor).tagName   // "SPAN"
```

### Expected
`<label for>` resolves to an `<input>`/`<select>`.

### Actual
Resolves to a `<span>`. This is exactly the PrimeVue 4 `id`-vs-`input-id` trap the earlier
accessibility pass fixed for the other seven filter controls — this Select was missed. All
seven others correctly resolve to `INPUT`; this is the only one that does not.

---

## Bug 9: Incident row hit target is 20px tall

**Severity:** minor
**Flow:** Ops table
**Frequency:** always

### Actual
23 controls measure under 24px; the incident row-select buttons are 20px tall. Only the
subject text is clickable — the rest of the row is dead space, so on a dense table the user
must hit a thin strip.

### Suggested fix
Make the whole row activatable and pad the cell to >= 24px (44px is the comfortable target).

---

## Bug 10: Active nav item is 2.99:1

**Severity:** minor
**Flow:** sidebar
**Actual:** `primary-700` on `primary-50` = 2.99:1, under the 4.5 needed at 14px.
`primary-800` measures 6.17:1 and is visually near-identical.

---

## Bug 11: Sidebar "INCIDENT DASHBOARD" label is 4.35:1

**Severity:** minor
**Actual:** `neutral-500` on `surface-100` = 4.35:1, marginally under 4.5.
`neutral-600` measures 7.17:1.

---

## Bug 12: Empty state leaves chart panels as blank boxes

**Severity:** minor
**Flow:** Ops with a filter matching nothing
**Frequency:** always

### Repro
1. Add institusi chip `Bank BNI` (no matches in the demo set).

### Actual
The table shows a proper empty state, but "Impact x Urgency" collapses to a bare `Total: 0`
in an otherwise empty card and "SLA Buckets" likewise. Two large blank rectangles read as
"failed to load", not "nothing matched".

---

## Bug 13: `Unknown (999999)` is presented as if it were an ordinary category

**Severity:** minor
**Flow:** Ops -> Impact x Urgency

### This is NOT a data bug — correcting an earlier assessment
`V1__config_tables.sql:120` documents the behaviour as deliberate: an enum id with no `REF_`
row resolves to `Unknown (<id>)` rather than NULL **specifically so a stale seed is visible
in the report**. The mechanism is correct and must not be "fixed" away.

### Actual
The UI renders it as a plain column header, visually identical to "Impact - Low". A signal
meaning "your reference data is stale, re-run the extract" is presented as an ordinary
classification, so the person who could act on it has no reason to.

### Suggested fix
Give `Unknown (...)` labels a muted/warning treatment and a tooltip naming the cause. Do not
hide it — being visible is the entire point.

---

## Bug 14: Demo stack deep-links to `redmine.example.com`

**Severity:** major
**Flow:** Ops -> incident detail -> "Open in Redmine"
**Frequency:** always in the demo stack

### Actual
`VITE_REDMINE_BASE_URL` is unset in `deploy/docker-compose.yml`, so the drawer falls back to
`https://redmine.example.com/issues/<id>`. "Open in Redmine" is the ONLY call to action in a
read-only product — the single thing a responder is meant to do — and in the demo it points
at a placeholder domain.

`rel="noopener noreferrer"` is correctly set, so there is no tab-nabbing exposure.

### Suggested fix
Set the build arg in the compose file, and render the button disabled with an explanatory
title when the base URL is unconfigured rather than linking somewhere fake.

---

## Bug 15: Drawer stays open showing a row that no longer matches the filters

**Severity:** minor
**Flow:** Ops -> open detail -> change filters
**Frequency:** intermittent (2 of 2 clean attempts; a third pass could not be driven reliably)

### Actual
Opening an incident and then applying a filter that excludes it leaves the drawer open with
the excluded incident still displayed. The data is not wrong — it is the row the user asked
for — but it is inconsistent with a table that no longer contains it.

### Note on confidence
Automation could not apply the filter reliably on a third pass, so this is logged at the
observed frequency rather than as "always". Worth one manual confirmation.

---

## Bug 16: "Open in Redmine" is styled near-black, off the brand palette

**Severity:** minor
**Flow:** incident detail drawer
**Actual:** `bg-surface-800`. It is the primary call to action of the product and the only
near-black control in the UI; every other primary action is cyan.

---

## Bug 17: KPI cards are buttons that neither look nor read like buttons

**Severity:** minor
**Flow:** Ops KPI row
**Actual:** each card is a `<button>` that scrolls to the table (and Workaround Debt also
applies a filter), but nothing signals that. No affordance beyond a hover tint, and a screen
reader announces "BACKLOG 95, button" with no hint of what activating it does. Workaround
Debt behaves differently from the other six with no visual distinction.

---

## Bug 18: No automated coverage of the new shell

**Severity:** minor
**Flow:** n/a — test-suite gap

### Actual
`AppSidebar`, `AppLayout` and `useLayout` landed in `6dc7e52` with no unit test, and the
Playwright suite predates the redesign entirely and asserts nothing about navigation. Bug 1
(mobile overlay) and Bug 6 (dead nav entry) were therefore both invisible to CI. The mobile
breakpoint in particular has no regression guard.

---

## Flow matrix

| Flow | Happy | Adversarial | Stress | Result |
|---|---|---|---|---|
| Login | pass | pass (bad creds -> visible error) | pass | contrast only (Bug 2) |
| Change password | pass | pass | not run | shares Bug 2 |
| Ops KPI + charts | pass | pass (empty set) | pass | Bugs 3, 12, 13, 17 |
| Ops filters | pass | pass (16 hostile inputs) | pass | Bugs 4, 5, 8 |
| Ops table + paging | pass | pass (page/size boundaries) | pass | Bugs 5, 9 |
| Incident drawer | pass | pass | pass (Esc closes) | Bugs 14, 15, 16 |
| Exec | pass | pass (empty month) | n/a | none new |
| Wallboard | pass | pass (stale banner) | n/a | none new |
| Navigation | **fail** | **fail** | n/a | Bugs 1, 6, 7 |

## Settings census
No Settings/Config/Preferences surface exists in this product — it is strictly read-only and
the only writable surfaces are login and change-password. Recorded here rather than silently
skipped, per §4.5.

## Untested / under-tested
- **Role-scoped sidebars:** only `admin` (ADMIN) was exercised. VIEWER/EXEC/WALLBOARD
  accounts exist only on the test classpath and are absent from the demo stack, so a real
  "fewer menus" pass was not possible.
- Concurrent sessions and two-tab behaviour.
- The export download itself (verified at the API layer, not through a real browser save).
- Bug 15 wants one manual confirmation.
- Dark mode was spot-checked only on the wallboard, the one surface that opts into
  `.app-dark`; the other views are light-only by design.

---

HANDOFF: 9 blocker/major bug(s) found. To fix as a tracked plan, run:

  /straventa-plan fix bugs from /Users/henseljahja/Project/mti/incident-dashboard/.agents/exploratory-qa/20260823T140606Z/bug-report.md

---

# Resolution — 2026-08-23

All 18 findings were fixed in the same session, at the user's explicit instruction ("fix
everything"). This section is appended rather than editing the findings above, so the report
still reads as what QA actually found.

| # | Severity | Fix | Verified by |
|---|---|---|---|
| 1 | blocker | `useLayout` now separates the desktop collapse preference from a session-only mobile overlay; `sidebarHidden` drives both the rail and the content offset | 8 unit tests + live DOM at 375px (`aside.x = -256`, `inert`) |
| 2 | major | `primary.contrastColor: #022c38` in the preset — keeps Yokke cyan | live: **1.67 → 8.88** |
| 3 | major | `RAMP_INK` paired with `RAMP` by construction | live: **1.73 → 10.36** |
| 4 | major | `TypeMismatchException` added to the 400 bucket; `IncidentFilter` clamps dates to Oracle's range | 6 new IT cases + live: all five now 400/200, none 500 |
| 5 | minor | Left as-is, deliberately — see note below | — |
| 6 | major | `V6__remove_unbuilt_admin_menu.sql` drops the menu the server had no screen for | live: nav is Operations/Wallboard/Executive |
| 7 | major | `/:pathMatch(.*)*` → `NotFoundView` | 6 router tests + live 404 page |
| 8 | major | `aria-labelledby` instead of `<label for>` on the Select | live: all 7 remaining labels → INPUT, Select correctly named |
| 9 | minor | Padding moved into the row button | live: **20px → 56px**, elements under 24px **23 → 0** |
| 10 | minor | Active nav `primary-800` | live: **2.99 → 6.17** |
| 11 | minor | Sidebar label `neutral-600` | live: **4.35 → 7.17** |
| 12 | minor | Empty states on the heatmap and SLA panels | live, with a filter matching nothing |
| 13 | minor | `Unknown (…)` renders amber/italic with a warning icon and a tooltip naming the cause | live screenshot |
| 14 | major | Fallback removed; `VITE_REDMINE_BASE_URL` plumbed as a build arg; unset renders a disabled explanatory control | live: placeholder link gone |
| 15 | minor | `watch` on the filter params closes the drawer; paging deliberately does not | 2 unit tests, both confirmed RED without the fix |
| 16 | minor | Primary Button styling | live |
| 17 | minor | `actionLabel` announces and shows what each card does | — |
| 18 | minor | 22 new tests across `useLayout`, `AppSidebar`, the router and `OpsView` | suite: 91 → 113 |

## Bug 5 was deliberately NOT "fixed"

Clamping an out-of-range `size` is a documented security control, not an oversight:
`IncidentFilter` explains that an unbounded page would turn `/api/incidents` into an
unprivileged export, and that clamping bounds the egress without turning an over-eager client
into an error page. Once the unparseable values became 400s (Bug 4), the policy is coherent —
**a value we can parse but not honour is clamped; a value we cannot parse is refused** — so the
inconsistency the bug describes is gone without weakening the control. The asymmetry is now
stated explicitly in `GlobalExceptionHandler`.

## Correction to Bug 13

An earlier note in this session called `Unknown (999999)` a data-labelling bug. It is not:
`V1__config_tables.sql:112-128` documents it as deliberate, so that a stale reference seed is
visible rather than silently NULL. The fix is presentational only — the resolver is untouched.

## Verification after the fixes

- Frontend: **113 tests pass** (was 91), type-check clean, lint clean.
- Backend Tier 1: **45 tests pass**.
- Backend Tier 2: **IncidentQueryIT 21/21** against real Oracle via Testcontainers.
- Demo stack rebuilt; V6 applied cleanly (`now at version v6`).

## Still open
- Bug 15 was proven by unit test rather than in the browser: PrimeVue's Drawer unmounts on a
  CSS transition end, which a throttled/background tab never fires, so the rendered DOM was not
  a usable witness. The unit test fails without the fix and passes with it.
- Role-scoped sidebars remain untested — VIEWER/EXEC/WALLBOARD accounts still exist only on the
  test classpath.
