import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

/** `localStorage` key for the chosen rotation interval, in milliseconds. */
export const ROTATION_INTERVAL_KEY = 'incident.wallboard.interval'

/** The intervals the wallboard offers: 30 s, 1 min, 2 min, 5 min. */
export const ROTATION_INTERVALS_MS = [30_000, 60_000, 120_000, 300_000] as const

export const DEFAULT_ROTATION_INTERVAL_MS = 60_000

/** The saved interval if it is one we offer, else the default. Storage may be blocked or stale. */
function readStoredInterval(): number {
  try {
    const stored = Number(localStorage.getItem(ROTATION_INTERVAL_KEY))
    return (ROTATION_INTERVALS_MS as readonly number[]).includes(stored)
      ? stored
      : DEFAULT_ROTATION_INTERVAL_MS
  } catch {
    // Private mode or a policy that blocks storage: the default is fine, the choice just won't stick.
    return DEFAULT_ROTATION_INTERVAL_MS
  }
}

/** A snapshot for the live region: the facts, formatted (and translated) by the view. */
export interface RotationAnnouncement {
  current: number
  total: number
  paused: boolean
  seconds: number
}

/**
 * Screen rotation for an unattended display: which screen is showing, how long until the next,
 * and the pause / next / interval controls.
 *
 * Counted in one-second ticks rather than one long timeout, so the countdown shown to people and
 * the switch itself can never disagree, and so pausing keeps the time already spent on a screen.
 *
 * Rotation STOPS while the tab is hidden: a background tab throttles timers, and a display that
 * silently jumps screens the moment someone returns to it is disorienting. It resumes where it
 * left off.
 *
 * `announcement` is what a screen reader hears (`null` until something changes). It changes only when the screen changes or the
 * rotation is paused/resumed — never per second — so the live region stays quiet.
 */
export function useRotation(screens: number) {
  const intervalMs = ref(readStoredInterval())
  const index = ref(0)
  const paused = ref(false)
  /** Time spent on the current screen. */
  const elapsedMs = ref(0)
  const hidden = ref(typeof document !== 'undefined' && document.hidden)
  const announcement = ref<RotationAnnouncement | null>(null)
  let timer: ReturnType<typeof setInterval> | undefined

  const remainingSeconds = computed(() =>
    Math.max(0, Math.ceil((intervalMs.value - elapsedMs.value) / 1000)),
  )
  /** 0 to 1, for the progress bar. */
  const progress = computed(() => Math.min(1, elapsedMs.value / intervalMs.value))
  const running = computed(() => !paused.value && !hidden.value)

  function next() {
    index.value = (index.value + 1) % screens
    elapsedMs.value = 0
  }

  function tick() {
    elapsedMs.value += 1000
    if (elapsedMs.value >= intervalMs.value) next()
  }

  function stop() {
    if (timer) clearInterval(timer)
    timer = undefined
  }

  function start() {
    stop()
    timer = setInterval(tick, 1000)
  }

  function setIntervalMs(value: number) {
    if (!(ROTATION_INTERVALS_MS as readonly number[]).includes(value)) return
    intervalMs.value = value
    elapsedMs.value = 0
    try {
      localStorage.setItem(ROTATION_INTERVAL_KEY, String(value))
    } catch {
      // Not persisted; the choice still applies until the page is closed.
    }
  }

  function pause() {
    paused.value = true
  }

  function resume() {
    paused.value = false
  }

  function toggle() {
    paused.value = !paused.value
  }

  function onVisibilityChange() {
    hidden.value = document.hidden
  }

  // The timer exists exactly while the rotation is running; a paused or hidden display has none.
  watch(
    running,
    (isRunning) => {
      if (isRunning) start()
      else stop()
    },
    { immediate: false },
  )

  watch([index, paused], () => {
    announcement.value = {
      current: index.value + 1,
      total: screens,
      paused: paused.value,
      seconds: remainingSeconds.value,
    }
  })

  onMounted(() => {
    document.addEventListener('visibilitychange', onVisibilityChange)
    hidden.value = document.hidden
    if (running.value) start()
  })

  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', onVisibilityChange)
    stop()
  })

  return {
    intervalMs,
    index,
    paused,
    hidden,
    elapsedMs,
    remainingSeconds,
    progress,
    announcement,
    setIntervalMs,
    pause,
    resume,
    toggle,
    next,
  }
}
