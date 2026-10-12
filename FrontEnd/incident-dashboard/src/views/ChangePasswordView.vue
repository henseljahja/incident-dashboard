<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import * as authApi from '@/apis/auth'
import { ApiError } from '@/apis/client'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const router = useRouter()
const { t } = useI18n()

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const errorMessage = ref('')
const submitting = ref(false)

async function onSubmit() {
  errorMessage.value = ''

  if (newPassword.value !== confirmPassword.value) {
    errorMessage.value = t('auth.changePassword.mismatch')
    return
  }

  submitting.value = true
  try {
    await authApi.changePassword(currentPassword.value, newPassword.value)

    /*
     * Back to sign-in, NOT to the dashboard — the session is genuinely gone by this point.
     *
     * `POST /auth/change-password` revokes every outstanding refresh token and clears the
     * refresh cookie, both deliberately (see LoginUseCase.changePassword and AuthController).
     * The access token still held here is the `change-password`-scoped one, which AuthFilter
     * refuses on every /api/** route. Pushing to defaultRoute therefore landed the user on a
     * dashboard where every request 403'd and nothing could re-establish a session: the ops
     * view showed empty KPIs and the wallboard showed "Cannot reach the dashboard service".
     * It hit every first login, because the bootstrap admin is seeded with
     * MUST_CHANGE_PASSWORD=Y and this is the only route out of that state.
     *
     * Signing in again with the new password is also the correct end to a rotation: the whole
     * point of revoking the tokens is that nothing from before the change stays usable.
     */
    authStore.clearSessionLocally()
    await router.push({ path: '/login', query: { passwordChanged: '1' } })
  } catch (err) {
    errorMessage.value = err instanceof ApiError ? err.message : t('auth.changePassword.failed')
  } finally {
    submitting.value = false
  }
}
</script>

<!--
  Matches LoginView: same gradient ground, same brand mark, same elevated card. This screen
  sits between sign-in and the dashboard for a first-time admin, so a different visual
  treatment here reads as "something went wrong" at exactly the moment a user is least sure
  they did the right thing.
-->

<template>
  <div
    class="flex min-h-screen items-center justify-center bg-linear-to-br from-primary-100 via-surface-50 to-primary-200 p-4"
  >
    <div class="w-full max-w-md">
      <div class="mb-6 flex flex-col items-start gap-3 px-1">
        <img src="/yokke.svg" width="96" alt="Yokke" />
        <div>
          <h1 class="text-2xl font-bold text-surface-900">{{ t('auth.changePassword.title') }}</h1>
          <p class="mt-1 text-sm text-surface-600">
            {{ t('auth.changePassword.prompt') }}
          </p>
        </div>
      </div>

      <Card :pt="{ root: 'rounded-lg', body: 'p-6' }">
        <template #content>
          <form class="flex flex-col gap-4" @submit.prevent="onSubmit">
            <div class="flex flex-col gap-1.5">
              <label for="current-password" class="text-sm font-medium text-surface-700">
                {{ t('auth.changePassword.current') }}
              </label>
              <Password
                input-id="current-password"
                v-model="currentPassword"
                :feedback="false"
                toggle-mask
                autocomplete="current-password"
                required
                fluid
              />
            </div>

            <div class="flex flex-col gap-1.5">
              <label for="new-password" class="text-sm font-medium text-surface-700">
                {{ t('auth.changePassword.new') }}
              </label>
              <Password
                input-id="new-password"
                v-model="newPassword"
                :feedback="false"
                toggle-mask
                autocomplete="new-password"
                required
                fluid
              />
            </div>

            <div class="flex flex-col gap-1.5">
              <label for="confirm-password" class="text-sm font-medium text-surface-700">
                {{ t('auth.changePassword.confirm') }}
              </label>
              <Password
                input-id="confirm-password"
                v-model="confirmPassword"
                :feedback="false"
                toggle-mask
                autocomplete="new-password"
                required
                fluid
              />
            </div>

            <Message v-if="errorMessage" severity="error" :closable="false" role="alert">
              {{ errorMessage }}
            </Message>

            <Button
              type="submit"
              :label="t('auth.changePassword.submit')"
              class="mt-1 w-full"
              :loading="submitting"
            />
          </form>
        </template>
      </Card>
    </div>
  </div>
</template>
