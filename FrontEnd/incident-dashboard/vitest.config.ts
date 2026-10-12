import { fileURLToPath } from 'node:url'
import { mergeConfig, defineConfig, configDefaults } from 'vitest/config'
import viteConfig from './vite.config'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      // Filter bounds are calendar dates. Under TZ=UTC a toISOString()-based
      // formatter passes while being wrong everywhere else on earth, so the
      // date tests are pinned to a non-zero offset. See src/utils/date.ts.
      env: { TZ: 'Asia/Jakarta' },
      // Playwright suites; vitest cannot run them (`test.describe.configure` throws under it).
      exclude: [...configDefaults.exclude, 'e2e/**', 'e2e-deployed/**'],
      // Installs the app's i18n on every mount and pins it to English — see the file.
      setupFiles: ['./src/__tests__/setup.ts'],
      root: fileURLToPath(new URL('./', import.meta.url)),
    },
  }),
)
