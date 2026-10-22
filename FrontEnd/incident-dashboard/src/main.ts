import './main.css'
import 'primeicons/primeicons.css'

import { definePreset } from '@primeuix/themes'
import Aura from '@primeuix/themes/aura'
import { VueQueryPlugin } from '@tanstack/vue-query'
import { createPinia } from 'pinia'
import PrimeVue, { usePrimeVue } from 'primevue/config'
import ConfirmationService from 'primevue/confirmationservice'
import Ripple from 'primevue/ripple'
import ToastService from 'primevue/toastservice'
import Tooltip from 'primevue/tooltip'
import { createApp } from 'vue'

import App from './App.vue'
import { i18n } from './i18n'
import { queryClient } from './queryClient'
import router from './router'
import { useLocaleStore } from './stores/locale'

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)
app.use(i18n)
app.use(router)

/**
 * Yokke house palette, kept byte-for-byte in step with `yob/FrontEnd/backoffice/src/main.ts`.
 *
 * This dashboard shipped on STOCK Aura for its whole first pass, which is why it read as
 * "a PrimeVue demo" rather than as a Yokke product: every accent — buttons, focus rings,
 * links, chart defaults, active nav — came out Aura's emerald green instead of Yokke cyan.
 * The palette is the single highest-leverage file in the UI; changing nothing else already
 * moves most of the surface.
 *
 * Do NOT drift these values independently. If the brand cyan changes, it changes in yob
 * first and is copied here, so the two consoles never disagree about what Yokke looks like.
 */
const YokkePreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '#f0fcfe',
      100: '#ddf7fe',
      200: '#baeffc',
      300: '#9de8fb',
      400: '#7be0f9',
      500: '#59d8f8',
      600: '#19c9f5',
      700: '#089ec4',
      800: '#05677f',
      900: '#033340',
      950: '#011c22',
    },
    colorScheme: {
      light: {
        /**
         * The ink on a primary button, NOT the button colour.
         *
         * Aura pairs its primary with white, which is right for a mid-tone emerald and wrong
         * for Yokke cyan: `#ffffff` on `#59d8f8` measures **1.67:1**, so Sign in, Export and
         * Change password — the primary action on every screen — all failed WCAG AA. The
         * palette was copied from yob without re-deriving this pairing.
         *
         * Darkening the button to cyan-800 would also pass (6.46:1) but throws away the brand
         * colour, which defeats the point of having one. Near-black ink on the unchanged
         * cyan-500 measures **8.88:1** and keeps the button visibly Yokke.
         *
         * If the brand cyan ever changes, re-measure this pairing — do not assume it carries.
         */
        primary: {
          color: '{primary.500}',
          contrastColor: '#022c38',
          hoverColor: '{primary.600}',
          activeColor: '{primary.700}',
        },
        surface: {
          0: '#ffffff',
          50: '{neutral.50}',
          100: '{neutral.100}',
          200: '{neutral.200}',
          300: '{neutral.300}',
          400: '{neutral.400}',
          500: '{neutral.500}',
          600: '{neutral.600}',
          700: '{neutral.700}',
          800: '{neutral.800}',
          900: '{neutral.900}',
          950: '{neutral.950}',
        },
      },
      /**
       * Dark is the wallboard only (`.app-dark`). On a dark ground the primary lightens to
       * cyan-400, so the ink darkens to match — same reasoning as the light pairing above,
       * re-measured rather than inherited.
       */
      dark: {
        primary: {
          color: '{primary.400}',
          contrastColor: '#011c22',
          hoverColor: '{primary.300}',
          activeColor: '{primary.200}',
        },
      },
    },
  },
  components: {
    /**
     * Aura pairs every Message tint with `{x.600}` ink, and that pairing does not reach AA.
     * Measured against the rendered pill backgrounds, at 16px, where 4.5:1 is required:
     *
     *     Active   green.600  #16a34a on #f1fdf5  ->  3.16
     *     Pending  yellow.600 #ca8a04 on #fefce9  ->  2.84
     *     Locked   red.600    #dc2626 on #fef3f3  ->  4.45
     *
     * These are the account-status pills on User Management — the column an admin scans to
     * find who is locked out — so they are body text by every definition that matters. Moving
     * the ink two steps down the ramp keeps the same hue and the same tint and lands at 6.92,
     * 6.63 and 6.02 respectively.
     *
     * The background and border are deliberately untouched: the pill is still recognisably the
     * same chip, it is only the text that changes. `simple` and `outlined` carry the same ink
     * because they render the same words on a lighter ground.
     */
    message: {
      colorScheme: {
        light: {
          success: {
            color: '{green.800}',
            simple: { color: '{green.800}' },
            outlined: { color: '{green.800}' },
          },
          warn: {
            color: '{yellow.800}',
            simple: { color: '{yellow.800}' },
            outlined: { color: '{yellow.800}' },
          },
          error: {
            color: '{red.700}',
            simple: { color: '{red.700}' },
            outlined: { color: '{red.700}' },
          },
        },
      },
    },
    /**
     * `surface.500` (#737373) on the `surface.100` panels these buttons sit in measures 4.35 —
     * just under AA, and it is the label on Clear, on the paginator arrows and on the DataTable
     * row toggles. `surface.600` (#525252) is the same neutral one step darker and measures
     * 7.69, which is also what the rest of the app's muted text now uses.
     */
    button: {
      colorScheme: {
        light: {
          // Outlined/text PRIMARY would fall back to the brand cyan (#59d8f8) as the label
          // colour: 1.67:1 on white. primary.800 is the same hue at 6.5:1.
          outlined: {
            primary: { color: '{primary.800}' },
            secondary: { color: '{surface.600}' },
          },
          text: { primary: { color: '{primary.800}' }, secondary: { color: '{surface.600}' } },
        },
      },
    },
  },
})

app.use(PrimeVue, {
  theme: {
    preset: YokkePreset,
    options: {
      /**
       * Layer order matters and is the same as yob's. PrimeVue's generated theme CSS is
       * declared LAST, so it beats anything in `base` regardless of selector specificity —
       * which is why the handful of component overrides in main.css are deliberately
       * UNLAYERED. Only unlayered CSS outranks a named layer.
       */
      cssLayer: {
        name: 'primevue',
        order: 'theme, base, primevue',
      },
      /**
       * OPT-IN dark mode, and only one surface opts in: `WallboardLayout` sets `.app-dark`
       * because it renders on a TV in an ops room where a white screen is the wrong choice.
       * Every other view is light.
       *
       * This selector was never the bug people hit. The bug was that main.css painted NO
       * background on `body`, so the page fell through to the browser's
       * `prefers-color-scheme` while PrimeVue rendered its light theme regardless. On a
       * machine set to dark that produced near-black text on a near-black page and the login
       * form was genuinely unreadable. main.css now paints an explicit surface background,
       * which is the actual fix — do not "fix" it again by removing this selector, or the
       * wallboard silently reverts to a white screen.
       */
      darkModeSelector: '.app-dark',
    },
  },
})

/**
 * Boot the UI language before the first render: the stored choice (or English) is applied to the
 * catalog, `<html lang>` and PrimeVue's own labels in one place. Inside `runWithContext` because
 * `usePrimeVue` is an injection, and there is no component on the stack yet to inject from.
 */
app.runWithContext(() => useLocaleStore(pinia).attachPrimeVue(usePrimeVue()))

app.use(ToastService)
app.use(ConfirmationService)
app.use(VueQueryPlugin, { queryClient })

app.directive('tooltip', Tooltip)
app.directive('ripple', Ripple)

app.mount('#app')
