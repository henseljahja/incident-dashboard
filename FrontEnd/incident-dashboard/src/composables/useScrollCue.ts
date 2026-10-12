import { nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref, type WatchSource } from 'vue'

/**
 * Whether a horizontally scrolling box has more content off its right edge.
 *
 * A wide table or matrix inside its own scroll container (so the page never scrolls sideways) has
 * nothing to say that the rest is there. Callers show an edge fade and a "scroll for more" hint
 * while `moreToRight` is true and attach `measure` to the box's `scroll` event. `remeasureOn`
 * names the data that changes the box's content, so the cue follows a new result.
 */
export function useScrollCue(scroller: Ref<HTMLElement | null>, remeasureOn?: WatchSource) {
  const moreToRight = ref(false)

  function measure() {
    const el = scroller.value
    moreToRight.value = !!el && el.scrollLeft + el.clientWidth < el.scrollWidth - 1
  }

  let observer: ResizeObserver | undefined
  onMounted(() => {
    measure()
    // jsdom has no ResizeObserver; a real browser re-measures on resize and on content change.
    if (typeof ResizeObserver !== 'undefined' && scroller.value) {
      observer = new ResizeObserver(measure)
      observer.observe(scroller.value)
    }
  })
  onBeforeUnmount(() => observer?.disconnect())
  if (remeasureOn) watch(remeasureOn, () => void nextTick(measure))

  return { moreToRight, measure }
}
