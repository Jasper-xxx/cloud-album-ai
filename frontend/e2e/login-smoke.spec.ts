import { expect, test } from '@playwright/test'

test('anonymous users can reach the login screen', async ({ page }) => {
  await page.goto('/login')

  await expect(page.locator('#app')).toBeVisible()
  await expect(page.locator('.auth-form')).toBeVisible()
  await expect(page.locator('input').first()).toBeVisible()
})
