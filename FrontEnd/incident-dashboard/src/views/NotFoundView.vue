<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

/**
 * Catch-all for unknown paths.
 *
 * Before this existed, any URL the router did not recognise rendered a blank white page: no
 * message, no navigation, and no console error to diagnose it with. A stale bookmark or a
 * typo stranded the user with nothing to act on. `/admin/users` was reachable from the
 * sidebar and hit exactly this, so the failure was not hypothetical.
 *
 * The way back is resolved from the caller's OWN permissions rather than hard-coded to `/ops`,
 * because not every role can see the ops view.
 */
const router = useRouter()
const auth = useAuthStore()
const { t } = useI18n()

function goHome() {
  void router.replace(auth.defaultRoute ?? '/')
}
</script>

<template>
  <main
    class="flex min-h-screen flex-col items-center justify-center gap-4 bg-surface-100 p-6 text-center"
    data-testid="not-found"
  >
    <!--
      `<main>`, not `<div>`. This route renders outside AppLayout — it has no sidebar and no
      shell — so nothing else on the page contributes a landmark, and a screen-reader user
      landing here had no region to jump to. Login and Wallboard are standalone in the same way
      and already carry their own `<main>`; this was the one that did not.

      The comment lives INSIDE the element deliberately: a comment node ahead of the root makes
      the component multi-root, and `wrapper.element` then resolves to the fragment's parent
      rather than to `<main>` — which is exactly how the first version of this failed its test.
    -->
    <i class="pi pi-compass text-5xl text-surface-400" aria-hidden="true" />
    <h1 class="text-2xl font-semibold text-surface-900">{{ t('errors.notFound.title') }}</h1>
    <p class="max-w-md text-surface-600">
      {{ t('errors.notFound.body') }}
    </p>
    <Button :label="t('errors.notFound.back')" icon="pi pi-arrow-left" @click="goHome" />
  </main>
</template>
