import { existsSync, mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

import { chromium, type FullConfig } from '@playwright/test'

import { DEPLOYED_AUTH_STATE, DEPLOYED_BASE_URL } from '../playwright.deployed.config'

/**
 * Opens a VISIBLE browser on the login page when no saved session exists, waits for a person to
 * sign in, saves the session and closes the window by itself. Nothing here types a credential.
 */
export default async function globalSetup(_config: FullConfig) {
  if (existsSync(DEPLOYED_AUTH_STATE) && !process.env.DEPLOYED_FORCE_LOGIN) return
  mkdirSync(dirname(DEPLOYED_AUTH_STATE), { recursive: true })
  const browser = await chromium.launch({
    headless: false,
    // Set DEPLOYED_BROWSER_CHANNEL=chrome to use the installed Google Chrome instead of the bundled Chromium.
    channel: process.env.DEPLOYED_BROWSER_CHANNEL || undefined,
  })
  const context = await browser.newContext({ viewport: { width: 1280, height: 860 } })
  const page = await context.newPage()
  await page.goto(`${DEPLOYED_BASE_URL}/login`)
  console.log('\n>>> Sign in in the browser window that just opened. It closes on its own.\n')
  await page.waitForURL(
    (url) => !url.pathname.startsWith('/login') && !url.pathname.startsWith('/change-password'),
    {
      timeout: 10 * 60_000,
    },
  )
  await page.waitForLoadState('networkidle')
  await context.storageState({ path: DEPLOYED_AUTH_STATE })
  await browser.close()
}
