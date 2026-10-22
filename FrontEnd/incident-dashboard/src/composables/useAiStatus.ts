import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'

import { fetchAiStatus } from '@/apis/insights'

/**
 * Whether the server has a real AI narrator behind it. The "Explain this" controls render only
 * when it does: with AI off (the default) the pages look exactly as they did before, and a
 * failed or slow status call also resolves to "no buttons" rather than to a button that cannot
 * work. The server enforces the same gate; this just avoids offering what it would refuse.
 */
export function useAiStatus() {
  const query = useQuery({
    queryKey: ['insights', 'ai-status'],
    queryFn: fetchAiStatus,
    staleTime: 5 * 60_000,
    retry: false,
  })
  return { narrating: computed(() => query.data.value?.narrating === true), query }
}
