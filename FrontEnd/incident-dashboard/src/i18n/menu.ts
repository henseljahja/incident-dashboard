import { useI18n } from 'vue-i18n'

import type { PermissionMenu } from '@/apis/auth'

/**
 * A nav/menu label in the UI language.
 *
 * Menus come from the server (`APP_MENU.LABEL`), in English. A known menu id is translated
 * through `nav.menu.<id>` — `incident.ops` -> `nav.menu.ops` — whose English entry is the seeded
 * label itself, so English output is unchanged. An id the catalog does not know (an admin added
 * a menu) keeps the server's label: a new menu must still render, just untranslated.
 */
export function useMenuLabel() {
  const { t, te } = useI18n()
  return (menu: Pick<PermissionMenu, 'menuId' | 'label'>): string => {
    const key = `nav.menu.${menu.menuId.replace(/^incident\./, '')}`
    return te(key) ? t(key) : menu.label
  }
}
