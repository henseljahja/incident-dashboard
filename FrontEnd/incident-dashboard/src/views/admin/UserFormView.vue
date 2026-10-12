<script setup lang="ts">
// Imported explicitly, unlike every other PrimeVue component on this screen: the auto-import
// resolver covers the `primevue` package, and Form/FormField live in the separate
// `@primevue/forms` package it does not scan. Without these two lines the template renders
// them as unknown elements and no validation runs at all — silently, with no console error.
import { Form, FormField } from '@primevue/forms'
import { zodResolver } from '@primevue/forms/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { z } from 'zod'

import {
  createUser,
  fetchRoles,
  fetchUser,
  resetPassword,
  updateUser,
  type CreatedUser,
} from '@/apis/admin'
import AppLayout from '@/layout/AppLayout.vue'
import { titleCase } from '@/utils/text'

/**
 * Create / view / edit one account — yob backoffice's `Create.vue`, `Detail.vue` and
 * `Edit.vue` folded into one component.
 *
 * <p>yob keeps three files that differ only in which fields are `:disabled` and which buttons
 * render at the bottom. Three copies of the same form is three places for a validation rule to
 * drift, and yob's own `Detail.vue` shows the cost — it still renders a "Resend Notification"
 * button wired to nothing. One component with a `mode` keeps the field list and the resolver
 * single-sourced.
 *
 * <p>Kept from yob verbatim: `Form` + `zodResolver`, `FormField` with a `Message` beneath each
 * input carrying `$field.error?.message`, the two-column grid, and Cancel/Submit right-aligned
 * at the foot.
 */
const props = defineProps<{ mode: 'create' | 'view' | 'edit' }>()

const route = useRoute()
const router = useRouter()
const queryClient = useQueryClient()
const { t, te } = useI18n()

const username = computed(() => String(route.params.username ?? ''))
const readOnly = computed(() => props.mode === 'view')

const submitting = ref(false)
const submitError = ref<string | null>(null)
/**
 * The one-time password, held only in this component's memory after a create or reset.
 *
 * There is no endpoint that returns it a second time — the row stores a bcrypt hash — so the
 * banner below must say that plainly. Offering a "copy again later" affordance would promise
 * something the server cannot honour.
 */
const issued = ref<CreatedUser | null>(null)

const { data: roles, isPending: rolesPending } = useQuery({
  queryKey: ['admin/roles'],
  queryFn: fetchRoles,
})

const {
  data: existing,
  isPending: userPending,
  isError: userError,
  error: userErrorDetail,
} = useQuery({
  queryKey: computed(() => ['admin/users', username.value]),
  queryFn: () => fetchUser(username.value),
  enabled: computed(() => props.mode !== 'create' && username.value.length > 0),
})

const initialValues = ref({
  username: '',
  name: '',
  email: '',
  roleId: '',
  active: true,
})

// Populated asynchronously: the form mounts before the fetch resolves, so seeding
// `initialValues` at setup time would leave every field blank on a view/edit screen.
watch(existing, (user) => {
  if (!user) return
  initialValues.value = {
    username: user.username,
    name: user.name ?? '',
    email: user.email ?? '',
    roleId: user.roleId ?? '',
    active: user.status !== 'INACTIVE',
  }
})

/**
 * `username` is validated only on create — it is the primary key and the server has no rename
 * path, so on edit it is displayed disabled rather than submitted.
 *
 * The character class is deliberately narrow. The username is interpolated into a URL path by
 * `apis/admin.ts` and read back by `LoginUseCase`; allowing spaces or slashes would produce
 * accounts whose detail page is unreachable.
 */
// Messages are read through `t` INSIDE the computed, so switching language rebuilds the
// resolver and the next validation speaks the new language.
const resolver = computed(() =>
  zodResolver(
    z.object({
      username:
        props.mode === 'create'
          ? z
              .string()
              .min(3, { message: t('admin.validation.usernameMin') })
              .max(64, { message: t('admin.validation.usernameMax') })
              .regex(/^[A-Za-z0-9._-]+$/, {
                message: t('admin.validation.usernamePattern'),
              })
          : z.string().optional(),
      name: z
        .string()
        .min(1, { message: t('admin.validation.nameRequired') })
        .max(128, { message: t('admin.validation.nameMax') }),
      // Optional, but validated when present: an empty string is "no address on file", while
      // "bob@" is a typo the operator wants to know about now rather than at first email.
      email: z
        .string()
        .max(128, { message: t('admin.validation.emailMax') })
        .refine((value) => value === '' || z.string().email().safeParse(value).success, {
          message: t('admin.validation.emailInvalid'),
        }),
      roleId: z.string().min(1, { message: t('admin.validation.roleRequired') }),
      // `active` ONLY when the form actually renders the toggle.
      //
      // This is not tidiness. A schema key with no matching FormField fails validation against
      // `undefined`, but the resulting error is filed under a field that has no state — so
      // `valid` stays TRUE while the resolver returns `values: undefined`, and the submit
      // handler reads `values.username` off nothing. The create form crashed with "Cannot read
      // properties of undefined" and no validation message anywhere to explain it.
      active: props.mode === 'create' ? z.boolean().optional() : z.boolean(),
    }),
  ),
)

interface SubmitEvent {
  valid: boolean
  /** Undefined when the resolver rejected — see the guard in `onSubmit`. */
  values?: Record<string, unknown>
}

async function onSubmit({ valid, values }: SubmitEvent) {
  if (!valid || submitting.value) return
  // `valid` is computed from FIELD state, while `values` comes from the resolver — so the two
  // can disagree whenever the schema and the rendered fields drift apart (see the `active`
  // note in the resolver). Refusing here turns that class of mistake into a visible message
  // instead of a TypeError from inside the submit path.
  if (!values) {
    submitError.value = t('admin.form.unreadable')
    return
  }
  submitting.value = true
  submitError.value = null
  try {
    if (props.mode === 'create') {
      issued.value = await createUser({
        username: String(values.username ?? '').trim(),
        name: String(values.name ?? ''),
        email: String(values.email ?? ''),
        roleId: String(values.roleId ?? ''),
      })
    } else {
      await updateUser(username.value, {
        name: String(values.name ?? ''),
        email: String(values.email ?? ''),
        roleId: String(values.roleId ?? ''),
        active: Boolean(values.active),
      })
      await queryClient.invalidateQueries({ queryKey: ['admin/users'] })
      await router.push('/admin/users')
    }
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : t('admin.form.saveFailed')
  } finally {
    submitting.value = false
  }
}

async function onResetPassword() {
  if (submitting.value) return
  submitting.value = true
  submitError.value = null
  try {
    issued.value = await resetPassword(username.value)
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : t('admin.form.resetFailed')
  } finally {
    submitting.value = false
  }
}

function onDone() {
  void queryClient.invalidateQueries({ queryKey: ['admin/users'] })
  void router.push('/admin/users')
}

/** Same rule as the table: a known enum is translated, an unknown one is title-cased. */
const statusLabel = computed(() => {
  const status = existing.value?.status ?? ''
  if (!status) return t('admin.status.unknown')
  const key = `admin.status.${status}`
  return te(key) ? t(key) : titleCase(status)
})

const heading = computed(() => {
  if (props.mode === 'create') return t('admin.create')
  if (props.mode === 'edit') return t('admin.form.editHeading', { username: username.value })
  return username.value
})
</script>

<template>
  <AppLayout>
    <div class="flex flex-col gap-6" data-testid="user-form">
      <!-- `h2`, not `h1`: the shell owns the page `h1` ("User Management"); this names the
           record being worked on beneath it. A `div` here left the form with no heading at all. -->
      <h2 class="text-xl font-semibold text-surface-900">{{ heading }}</h2>

      <Message
        v-if="userError"
        severity="error"
        :closable="false"
        role="alert"
        data-testid="user-form-load-error"
      >
        {{ userErrorDetail?.message ?? t('admin.form.loadError') }}
      </Message>

      <!--
        The one-time password. Rendered as a blocking panel rather than a toast on purpose: a
        toast that auto-dismisses would take with it the only copy of a credential that exists
        nowhere else, and the operator would have to reset the account to get another.
      -->
      <Message
        v-if="issued"
        severity="success"
        :closable="false"
        role="status"
        data-testid="user-temp-password"
      >
        <div class="flex flex-col gap-2">
          <span class="font-medium">
            {{ t('admin.form.tempPasswordTitle', { username: issued.username }) }}
          </span>
          <code class="rounded-lg bg-surface-0 px-3 py-2 font-mono text-lg tracking-wide">{{
            issued.temporaryPassword
          }}</code>
          <span class="text-sm">
            {{ t('admin.form.tempPasswordBody') }}
          </span>
          <div>
            <Button
              :label="t('admin.form.done')"
              severity="primary"
              class="mt-1 rounded-xl"
              data-testid="user-temp-password-done"
              @click="onDone"
            />
          </div>
        </div>
      </Message>

      <Message
        v-if="submitError"
        severity="error"
        :closable="false"
        role="alert"
        data-testid="user-form-error"
      >
        {{ submitError }}
      </Message>

      <div
        v-if="mode !== 'create' && userPending"
        class="flex items-center gap-2 text-sm text-surface-600"
        data-testid="user-form-loading"
        aria-live="polite"
      >
        <i class="pi pi-spin pi-spinner" aria-hidden="true" />
        {{ t('admin.form.loading') }}
      </div>

      <Form
        v-else-if="!issued"
        v-slot="$form"
        :resolver="resolver"
        :initial-values="initialValues"
        class="flex flex-1 flex-col gap-6"
        @submit="onSubmit"
      >
        <div class="flex flex-col gap-4">
          <div class="text-md">{{ t('admin.form.account') }}</div>
          <div class="grid gap-4 md:grid-cols-2">
            <FormField v-slot="$field" as="section" name="username" class="flex flex-col gap-1">
              <label for="username">{{ t('admin.col.username') }}</label>
              <InputText
                id="username"
                type="text"
                fluid
                autocomplete="off"
                :placeholder="t('admin.form.usernamePlaceholder')"
                class="rounded-xl"
                :disabled="mode !== 'create'"
                data-testid="field-username"
              />
              <Message v-if="$field?.invalid" severity="error" size="small" variant="simple">
                {{ $field.error?.message }}
              </Message>
              <small v-if="mode === 'create'" class="text-surface-600">
                {{ t('admin.form.usernameHint') }}
              </small>
            </FormField>

            <FormField v-slot="$field" as="section" name="roleId" class="flex flex-col gap-1">
              <label id="roleId-label" for="roleId">{{ t('admin.col.role') }}</label>
              <!--
                `aria-labelledby`, not `<label for>`: PrimeVue's Select renders `input-id` onto
                a `<span role="combobox">`, which a label's `for` cannot address, so the field
                would be announced as unlabelled.
              -->
              <Select
                input-id="roleId"
                aria-labelledby="roleId-label"
                :options="roles ?? []"
                :loading="rolesPending"
                option-label="name"
                option-value="roleId"
                :placeholder="t('admin.form.rolePlaceholder')"
                class="rounded-xl"
                :disabled="readOnly"
                data-testid="field-role"
              />
              <Message v-if="$field?.invalid" severity="error" size="small" variant="simple">
                {{ $field.error?.message }}
              </Message>
            </FormField>

            <FormField v-slot="$field" as="section" name="name" class="flex flex-col gap-1">
              <label for="name">{{ t('admin.col.name') }}</label>
              <InputText
                id="name"
                type="text"
                fluid
                autocomplete="off"
                :placeholder="t('admin.form.namePlaceholder')"
                class="rounded-xl"
                :disabled="readOnly"
                data-testid="field-name"
              />
              <Message v-if="$field?.invalid" severity="error" size="small" variant="simple">
                {{ $field.error?.message }}
              </Message>
            </FormField>

            <FormField v-slot="$field" as="section" name="email" class="flex flex-col gap-1">
              <label for="email">{{ t('admin.col.email') }}</label>
              <InputText
                id="email"
                type="email"
                fluid
                autocomplete="off"
                :placeholder="t('admin.form.emailPlaceholder')"
                class="rounded-xl"
                :disabled="readOnly"
                data-testid="field-email"
              />
              <Message v-if="$field?.invalid" severity="error" size="small" variant="simple">
                {{ $field.error?.message }}
              </Message>
            </FormField>
          </div>
        </div>

        <div v-if="mode !== 'create'" class="flex flex-col gap-4">
          <div class="text-md">{{ t('admin.col.status') }}</div>
          <FormField as="section" name="active" class="flex flex-col gap-2">
            <div class="flex items-center gap-3">
              <ToggleSwitch input-id="active" :disabled="readOnly" data-testid="field-active" />
              <label for="active">
                {{ t('admin.form.activeLabel', { status: statusLabel }) }}
              </label>
            </div>
            <small class="text-surface-600">
              {{ t('admin.form.deactivateHint') }}
            </small>
          </FormField>
        </div>

        <div class="mt-auto flex flex-wrap justify-end gap-3">
          <Button
            type="button"
            severity="secondary"
            variant="outlined"
            :label="readOnly ? t('admin.form.back') : t('admin.form.cancel')"
            class="rounded-xl"
            data-testid="user-form-cancel"
            @click="router.push('/admin/users')"
          />
          <Button
            v-if="mode === 'edit'"
            type="button"
            severity="secondary"
            :label="t('admin.form.resetPassword')"
            icon="pi pi-key"
            class="rounded-xl"
            :loading="submitting"
            data-testid="user-reset-password"
            @click="onResetPassword"
          />
          <Button
            v-if="!readOnly"
            type="submit"
            severity="primary"
            :label="mode === 'create' ? t('admin.form.create') : t('admin.form.save')"
            class="rounded-xl"
            :disabled="!$form.valid || submitting"
            :loading="submitting"
            data-testid="user-form-submit"
          />
        </div>
      </Form>
    </div>
  </AppLayout>
</template>
