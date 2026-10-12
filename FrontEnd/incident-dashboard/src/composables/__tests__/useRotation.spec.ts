import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'

import { ROTATION_INTERVAL_KEY, ROTATION_INTERVALS_MS, useRotation } from '../useRotation'

/** `useRotation` hooks onMounted/onBeforeUnmount, so it needs a component around it. */
function setup(screens = 2) {
  let rotation!: ReturnType<typeof useRotation>
  const wrapper = mount(
    defineComponent({
      setup() {
        rotation = useRotation(screens)
        return () => null
      },
    }),
  )
  return { wrapper, rotation }
}

function setHidden(hidden: boolean) {
  Object.defineProperty(document, 'hidden', { configurable: true, get: () => hidden })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => {
  vi.useFakeTimers()
  localStorage.removeItem(ROTATION_INTERVAL_KEY)
  setHidden(false)
})

afterEach(() => {
  vi.useRealTimers()
  localStorage.removeItem(ROTATION_INTERVAL_KEY)
})

/** TM11 */
describe('useRotation', () => {
  it('defaults to one minute and starts on the first screen', () => {
    const { rotation } = setup()

    expect(rotation.intervalMs.value).toBe(60_000)
    expect(rotation.index.value).toBe(0)
    expect(rotation.remainingSeconds.value).toBe(60)
  })

  it('offers 30 s, 1 min, 2 min and 5 min', () => {
    expect([...ROTATION_INTERVALS_MS]).toEqual([30_000, 60_000, 120_000, 300_000])
  })

  it('switches screen after the interval and wraps around', async () => {
    const { rotation } = setup()

    await vi.advanceTimersByTimeAsync(59_000)
    expect(rotation.index.value).toBe(0)
    expect(rotation.remainingSeconds.value).toBe(1)

    await vi.advanceTimersByTimeAsync(1_000)
    expect(rotation.index.value).toBe(1)
    expect(rotation.remainingSeconds.value).toBe(60)

    await vi.advanceTimersByTimeAsync(60_000)
    expect(rotation.index.value).toBe(0)
  })

  it('persists the chosen interval and restores it next time', () => {
    const first = setup()
    first.rotation.setIntervalMs(120_000)

    expect(localStorage.getItem(ROTATION_INTERVAL_KEY)).toBe('120000')
    first.wrapper.unmount()

    expect(setup().rotation.intervalMs.value).toBe(120_000)
  })

  it('restarts the countdown when the interval changes', async () => {
    const { rotation } = setup()
    await vi.advanceTimersByTimeAsync(20_000)

    rotation.setIntervalMs(30_000)

    expect(rotation.remainingSeconds.value).toBe(30)
  })

  it('ignores a stored or chosen value it does not offer', () => {
    localStorage.setItem(ROTATION_INTERVAL_KEY, '7')
    const { rotation } = setup()

    expect(rotation.intervalMs.value).toBe(60_000)
    rotation.setIntervalMs(5)
    expect(rotation.intervalMs.value).toBe(60_000)
    expect(localStorage.getItem(ROTATION_INTERVAL_KEY)).toBe('7')
  })

  it('still works when storage is blocked', () => {
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })

    const { rotation } = setup()
    expect(rotation.intervalMs.value).toBe(60_000)
    rotation.setIntervalMs(30_000)
    expect(rotation.intervalMs.value).toBe(30_000)

    get.mockRestore()
    set.mockRestore()
  })

  it('pauses, keeps its place, and resumes', async () => {
    const { rotation } = setup()
    await vi.advanceTimersByTimeAsync(10_000)

    rotation.pause()
    await vi.advanceTimersByTimeAsync(300_000)
    expect(rotation.index.value).toBe(0)
    expect(rotation.remainingSeconds.value).toBe(50)

    rotation.resume()
    await vi.advanceTimersByTimeAsync(50_000)
    expect(rotation.index.value).toBe(1)
  })

  it('moves on at once with next() and restarts the countdown', async () => {
    const { rotation } = setup()
    await vi.advanceTimersByTimeAsync(40_000)

    rotation.next()

    expect(rotation.index.value).toBe(1)
    expect(rotation.remainingSeconds.value).toBe(60)
    rotation.next()
    expect(rotation.index.value).toBe(0)
  })

  it('stops while the page is hidden and carries on when it is shown again', async () => {
    const { rotation } = setup()
    await vi.advanceTimersByTimeAsync(10_000)

    setHidden(true)
    await vi.advanceTimersByTimeAsync(600_000)
    expect(rotation.index.value).toBe(0)
    expect(rotation.remainingSeconds.value).toBe(50)

    setHidden(false)
    await vi.advanceTimersByTimeAsync(50_000)
    expect(rotation.index.value).toBe(1)
  })

  it('announces only when the screen or the pause state changes, never every second', async () => {
    const { rotation } = setup()
    expect(rotation.announcement.value).toBeNull()

    await vi.advanceTimersByTimeAsync(30_000)
    expect(rotation.announcement.value).toBeNull()

    await vi.advanceTimersByTimeAsync(30_000)
    expect(rotation.announcement.value).toEqual({
      current: 2,
      total: 2,
      paused: false,
      seconds: 60,
    })

    rotation.pause()
    await vi.advanceTimersByTimeAsync(0)
    expect(rotation.announcement.value?.paused).toBe(true)
  })

  it('clears its timer on unmount', async () => {
    const { rotation, wrapper } = setup()
    wrapper.unmount()

    await vi.advanceTimersByTimeAsync(600_000)

    expect(rotation.index.value).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
  })
})
