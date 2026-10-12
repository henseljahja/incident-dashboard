<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

import { ApiError } from '@/apis/client'
import LanguageSwitch from '@/components/common/LanguageSwitch.vue'
import { CHANGE_PASSWORD_ROUTE, useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const router = useRouter()
const route = useRoute()
const { t } = useI18n()

/**
 * Set by ChangePasswordView, which sends the user back here after a rotation because the server
 * revokes every outstanding token at that point. Without this the redirect reads as "my password
 * change failed and bounced me to login" — the opposite of what actually happened.
 */
const passwordChanged = computed(() => route.query.passwordChanged === '1')

/** Set by App.vue when a live session dies mid-use, so the bounce to login is explained. */
const sessionExpired = computed(() => route.query.sessionExpired === '1')

const username = ref('')
const password = ref('')
const errorMessage = ref('')
const submitting = ref(false)

async function onSubmit() {
  errorMessage.value = ''
  submitting.value = true
  try {
    await authStore.login(username.value, password.value)
    // RBAC decides the landing view — the role's own DEFAULT_ROUTE, never a fixed path.
    const target = authStore.mustChangePassword
      ? CHANGE_PASSWORD_ROUTE
      : (authStore.defaultRoute ?? '/')
    await router.push(target)
  } catch (err) {
    errorMessage.value = err instanceof ApiError ? err.message : t('auth.loginFailed')
  } finally {
    submitting.value = false
  }
}
</script>

<!--
  Sign-in, restyled against yob's login.

  The previous version was a bare form on a transparent body. Combined with the missing
  background token in main.css that meant a machine set to dark rendered near-black labels on
  a near-black page — not merely plain, actually unreadable. It also stated no product
  identity at all, which matters here: this is the screen that tells a Yokke operator they
  are in the right place.

  Structure follows yob: brand mark, product name, a short statement of what the tool is, then
  the form in an elevated card on a soft gradient.
-->

<template>
  <div
    class="flex min-h-screen items-center justify-center bg-linear-to-br from-primary-100 via-surface-50 to-primary-200 p-4"
  >
    <!--
      `<main>`, even though there is no nav here to skip past. A landmark is how assistive
      technology answers "where does the content start" on ANY page, and a login screen with no
      landmark at all is the one place a first-time user cannot afford to be lost.
    -->
    <main class="w-full max-w-md">
      <div class="mb-6 flex flex-col items-start gap-3 px-1">
        <!-- The language is choosable BEFORE there is a session: this screen is the first one. -->
        <div class="flex w-full items-center justify-between gap-3">
          <img src="/yokke.svg" width="96" alt="Yokke" />
          <LanguageSwitch />
        </div>
        <div>
          <h1 class="text-2xl font-bold text-surface-900">{{ t('common.appName') }}</h1>
          <p class="mt-1 text-sm font-semibold text-primary-800">
            {{ t('auth.tagline') }}
          </p>
          <p class="mt-1 text-sm text-surface-600">
            {{ t('auth.readOnly') }}
          </p>
        </div>
      </div>

      <Card :pt="{ root: 'rounded-lg', body: 'p-6' }">
        <template #content>
          <h2 class="text-lg font-bold text-surface-900">{{ t('auth.welcome') }}</h2>
          <p class="mt-1 mb-5 text-sm text-surface-600">
            {{ t('auth.signInPrompt') }}
          </p>

          <form class="flex flex-col gap-4" @submit.prevent="onSubmit">
            <div class="flex flex-col gap-1.5">
              <label for="username" class="text-sm font-medium text-surface-700">{{
                t('auth.username')
              }}</label>
              <InputText
                id="username"
                v-model="username"
                autocomplete="username"
                :placeholder="t('auth.usernamePlaceholder')"
                required
                fluid
              />
            </div>

            <div class="flex flex-col gap-1.5">
              <label for="password" class="text-sm font-medium text-surface-700">{{
                t('auth.password')
              }}</label>
              <Password
                input-id="password"
                v-model="password"
                :feedback="false"
                toggle-mask
                autocomplete="current-password"
                :placeholder="t('auth.passwordPlaceholder')"
                required
                fluid
              />
            </div>

            <Message
              v-if="sessionExpired && !errorMessage"
              severity="warn"
              :closable="false"
              role="status"
              data-testid="session-expired-notice"
            >
              {{ t('auth.sessionExpired') }}
            </Message>

            <Message
              v-if="passwordChanged && !errorMessage"
              severity="success"
              :closable="false"
              role="status"
              data-testid="password-changed-notice"
            >
              {{ t('auth.passwordChanged') }}
            </Message>

            <Message v-if="errorMessage" severity="error" :closable="false" role="alert">
              {{ errorMessage }}
            </Message>

            <Button
              type="submit"
              :label="t('auth.signIn')"
              class="mt-1 w-full"
              :loading="submitting"
            />
          </form>
        </template>
      </Card>
    </main>
  </div>
</template>
