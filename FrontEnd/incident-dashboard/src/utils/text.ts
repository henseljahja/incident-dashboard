/**
 * `SNAKE_CASE` / `UPPERCASE` → `Title Case`. Ported from yob backoffice's `utils/index.ts`,
 * where it renders the user-status column.
 *
 * Exists so a status enum can be shown to a human without a per-value label map: the server
 * sends `PENDING`, the screen shows `Pending`, and adding a status server-side does not need a
 * matching client-side entry to avoid rendering as a shout.
 */
export function titleCase(input?: string | null): string {
  if (!input) {
    return ''
  }
  // `split('_').join(' ')` rather than `replaceAll`: this file is compiled under a config
  // whose lib target predates ES2021, and `replaceAll` fails type-check there.
  return input
    .split('_')
    .join(' ')
    .toLowerCase()
    .split(' ')
    .map((word: string) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}
