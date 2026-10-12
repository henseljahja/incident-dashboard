# forms

## Intent

**This system has no data-entry forms.** It is strictly read-only over the Redmine mirror;
every action path deep-links back to Redmine, which remains the permanent write side. The only
forms that exist are credential forms.

**Surfaces:** the SPA's login view and change-password view. Nothing else.

## Required Checks

- **A new form is a scope change.** If a task introduces a form that writes incident data, stop
  — that contradicts the read-only charter and needs a plan, not an implementation.
- Filter controls are **not** forms: they drive query parameters on GET routes and hold no
  server state. Keep them in the Pinia filter store.
- The two credential forms never persist input, never autofill from anything but the browser's
  own password manager, and never log their contents (see [logging](logging.md)).
- The change-password form is the **only** route reachable by a `scope="change-password"`
  token; it must be usable when every other route is refused.
- Tier 3 covers login and the forced-rotation change-password path as real-browser walkthroughs.
