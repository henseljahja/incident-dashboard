import { useI18n } from 'vue-i18n'

/**
 * A stale reason in the UI language.
 *
 * `/api/freshness` reports a machine code (`etl-never-run`, `etl-stalled`, `mirror-lagging`,
 * see the backend's `FreshnessUseCase`). An operator reads a sentence, not a code; a code the
 * catalog does not know yet is shown as-is rather than hidden.
 */
export function useStaleReason() {
  const { t, te } = useI18n()
  return (code: string): string => {
    const key = `common.freshness.reason.${code}`
    return te(key) ? t(key) : code
  }
}
