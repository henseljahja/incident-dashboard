<script setup lang="ts">
import { computed, useId } from 'vue'
import { useI18n } from 'vue-i18n'

import type { AppLocale } from '@/i18n'
import { useLocaleStore } from '@/stores/locale'

/**
 * EN / ID switch. Sits in the AppLayout topbar, the wallboard chrome and on the login screen —
 * the last one matters because the language has to be choosable BEFORE there is a session.
 *
 * A two-segment SelectButton rather than a dropdown: with two languages the whole choice fits in
 * the space a closed dropdown would take, and the active segment states the current language at
 * a glance. `allow-empty=false`, because "no language" is not a state the UI can render.
 */
const { t } = useI18n()
const localeStore = useLocaleStore()
const labelId = useId()

interface LocaleOption {
  value: AppLocale
  short: string
  /** Each language names itself in its own language — a reader who cannot read the current UI can still find theirs. */
  name: string
}

const options: LocaleOption[] = [
  { value: 'en', short: 'EN', name: 'English' },
  { value: 'id', short: 'ID', name: 'Bahasa Indonesia' },
]

const selected = computed({
  get: () => localeStore.locale,
  set: (value: AppLocale | null) => {
    if (value) localeStore.setLocale(value)
  },
})
</script>

<template>
  <div class="flex items-center" data-testid="language-switch">
    <span :id="labelId" class="sr-only">{{ t('common.language') }}</span>
    <SelectButton
      v-model="selected"
      :options="options"
      option-label="short"
      option-value="value"
      :allow-empty="false"
      :aria-labelledby="labelId"
      size="small"
      :pt="{ pcToggleButton: { root: { class: 'min-h-11 min-w-11' } } }"
    >
      <template #option="{ option }">
        <span aria-hidden="true" :data-testid="`language-option-${option.value}`">{{
          option.short
        }}</span>
        <span class="sr-only" :lang="option.value">{{ option.name }}</span>
      </template>
    </SelectButton>
  </div>
</template>
