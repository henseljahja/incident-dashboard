<script setup lang="ts">
import { computed } from 'vue'

import { redmineIssueUrl } from '@/utils/redmine'

/**
 * A ticket number as a deep link into Redmine — this product's one "act on it" path — or as
 * plain text when the deployment has no Redmine base URL configured (never a placeholder link).
 */
const props = defineProps<{ id: number }>()
const url = computed(() => redmineIssueUrl(props.id))
</script>

<template>
  <a
    v-if="url"
    :href="url"
    target="_blank"
    rel="noopener noreferrer"
    class="text-primary-800 hover:underline"
    data-testid="ticket-link"
    >{{ id }}</a
  >
  <span v-else data-testid="ticket-link">{{ id }}</span>
</template>
