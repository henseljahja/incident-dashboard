# Design Critique: Incident Dashboard (Yokke)

Reviewed: 2026-08-23 · Stage: post-redesign refinement (`6dc7e52`) · Surfaces: login,
change-password, ops, exec, wallboard · Live at `http://localhost:8090`

## Overall Impression

The redesign did the hard structural work — there is a real shell, a real palette, and the
ops view now reads as a product rather than a component demo. The biggest opportunity is
that the brand palette was adopted **without re-deriving the pairings that depend on it**:
Yokke cyan is a light tint, and every place the old Aura theme assumed a mid-tone primary
now fails contrast. That single root cause produces the three worst visual findings.

The second theme is that the shell was built for one width. Below 768px the layout does not
degrade, it collapses.

## Usability

| Finding | Severity | Recommendation |
|---|---|---|
| Sidebar overlays all content below 768px; nav sits on top of KPI cards | 🔴 Critical | Off-canvas by default under `md`, opened by the hamburger that already exists |
| "User Management" navigates to a blank page | 🔴 Critical | Build the screen or stop seeding the menu — do not special-case the route in the sidebar |
| Any unknown URL is a blank white page | 🔴 Critical | Add a `/:pathMatch(.*)*` 404 view with a link home |
| "Open in Redmine" — the product's only CTA — points at `redmine.example.com` in the demo | 🟡 Moderate | Set `VITE_REDMINE_BASE_URL`; disable the button with an explanatory title when unset |
| KPI cards are clickable but give no signal that they are | 🟡 Moderate | Add a hover/focus affordance and an `aria-description` naming the action |
| Empty-filter chart panels render as blank rectangles | 🟢 Minor | Explicit "No incidents match these filters" empty state, matching the table's |
| Row hit target is a 20px strip of subject text | 🟢 Minor | Make the whole row activatable, pad to >= 44px |

## Visual Hierarchy

- **What draws the eye first:** the KPI number row — correct. 3xl semibold numerals against
  small uppercase labels is the right weight contrast, and the tone rules on the left edge let
  you find BREACHED without reading.
- **Reading flow:** header -> stale banner -> KPI row -> charts -> table. This matches how a
  responder actually triages, and the stale banner sitting above the numbers is a good call —
  it qualifies everything below it before it is read.
- **Emphasis, where it goes wrong:** the heatmap inverts its own hierarchy. The intensity ramp
  darkens the cell as the count rises while the text stays near-black, so the single most
  important cell on the screen (93 incidents) is the least readable at 1.73:1. Emphasis and
  legibility are pulling in opposite directions.
- **Second inversion:** "Open in Redmine" is the only near-black control in a cyan UI. It reads
  as a secondary/cancel action while being the product's only call to action.

## Consistency

| Element | Issue | Recommendation |
|---|---|---|
| Primary button ink | White on cyan everywhere, inherited from Aura's mid-tone assumption | Set `primary.contrastColor` to `#022c38` in the preset — one change, whole surface |
| "Open in Redmine" | `bg-surface-800` while every other primary action is cyan | Use the primary Button component |
| Filter controls | 7 of 8 use PrimeVue's `input-id`; `HAS WORKAROUND` uses `id` and its label points at a `<span>` | Switch to `input-id` — same trap already fixed for the other seven |
| Sentinel labels | `Unknown (999999)` styled identically to a real category | Muted/warning treatment + tooltip; it is a deliberate stale-seed signal (`V1__config_tables.sql:120`) and should read as one |
| Breakpoints | KPI row is responsive (2/3/4/7 cols); the shell is not | Give the shell the same breakpoint discipline the KPI row already has |

## Accessibility

**Colour contrast** — measured against canvas-resolved computed styles (an `oklch()`-naive
first pass produced a false positive and was discarded):

| Pair | Ratio | Verdict | Fix measured |
|---|---|---|---|
| Primary button — white on cyan-500 | 1.67 | ❌ fail | `#022c38` ink → **8.88** |
| Heatmap max cell — `#171717` on `#1e3a8a` | 1.73 | ❌ fail | white ink → **10.36** |
| Active nav — primary-700 on primary-50 | 2.99 | ❌ fail | primary-800 → **6.17** |
| Sidebar label — neutral-500 on surface-100 | 4.35 | ❌ marginal fail | neutral-600 → **7.17** |

Every proposed fix keeps the Yokke palette rather than retreating to a darker brand colour.

**Touch targets:** 23 elements measure under 24px; the worst are the 20px row-select controls.
Below the AA 24x24 minimum and well below the 44px comfortable target.

**Text readability:** body and label sizing are fine. `text-xs uppercase tracking-wide` on the
KPI labels is legible at the sizes used. No issues found.

**Structure:** one broken `label[for]` (above). The remainder of the a11y surface — landmark
roles, heatmap cell `aria-label`s ("Impact X, urgency Y, N incidents"), `aria-hidden` on
decorative icons, drawer labelling — is genuinely good and was clearly built deliberately.

## What Works Well

- **The tone system on the KPI cards.** A severity rule plus an icon, sourced from the SLA
  ramp rather than the categorical chart palette, is the correct distinction — severity is a
  scale, not a set. An unspecified tone still renders a deliberate-looking neutral card.
- **The trend arrow carries its meaning in the glyph, not only in colour.** ▲/▼/— is readable
  without colour vision, and "up is bad" is consistent across all seven cards.
- **The stale-data banner.** Positioned above the numbers so it qualifies them before they are
  read, and it names the actual mirror timestamp rather than saying "data may be stale".
- **The heatmap's aria labels.** Every cell announces impact, urgency and count — better than
  most production dashboards manage.
- **Honest empty state on the table**, with a real message rather than an empty grid. The
  charts should copy it.

## Priority Recommendations

1. **Fix the shell below `md`.** It is the only finding that makes a screen unusable rather
   than unattractive. Off-canvas sidebar + scrim, driven by the hamburger already present.
2. **Set `primary.contrastColor` in the preset.** One line fixes the primary action on every
   screen and keeps the brand cyan. The other three contrast failures are three more one-line
   token changes in the same pass.
3. **Close the two dead navigation paths** — build or unseed `/admin/users`, and add a 404
   route. A blank white page is the worst possible failure mode because it gives the user
   nothing to act on and produces no console error to diagnose.

## Note on provenance

Findings 1, 6 and 18 in the QA bug report are regressions from `6dc7e52`, which is my own
change. The contrast failures predate it in origin (they arrived with the palette copy) but
were made far more visible by it, since stock Aura's emerald was never paired with white this
way. Recorded here so the redesign is not credited without its costs.

---

## Status — all three priority recommendations applied, 2026-08-23

| Recommendation | Outcome |
|---|---|
| 1. Fix the shell below `md` | Rail is off-canvas and `inert` below 768px, opens as an overlay with a scrim, dismisses on navigation. 8 unit tests guard the breakpoint. |
| 2. Set `primary.contrastColor` | Done, plus the three other contrast failures. Every measured pair now passes AA, and the brand cyan is unchanged. |
| 3. Close the dead navigation paths | `incident.admin` unseeded in `V6`; catch-all 404 route added. |

Measured after, in the running app:

| Pair | Before | After |
|---|---|---|
| Primary button | 1.67 | **8.88** |
| Heatmap max cell | 1.73 | **10.36** |
| Active nav | 2.99 | **6.17** |
| Sidebar label | 4.35 | **7.17** |
| Elements under 24px | 23 | **0** |

The consistency and usability tables were also worked through in full — empty states on both
chart panels, the Select's accessible name, the row hit target, the Redmine CTA, the
`Unknown (…)` warning treatment, and the KPI cards' announced action.
