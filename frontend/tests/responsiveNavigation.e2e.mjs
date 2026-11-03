import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import { chromium } from "playwright";

const baseUrl = "http://127.0.0.1:4173";
const preview = spawn("npm", ["run", "preview", "--", "--host", "127.0.0.1", "--port", "4173", "--strictPort"], {
  stdio: "ignore",
});
let browser;

try {
  let ready = false;
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      const response = await fetch(baseUrl);
      if (response.ok) {
        ready = true;
        break;
      }
    } catch {
      // The preview server may need another short interval to start.
    }
    await delay(200);
  }
  assert.ok(ready, "Vite preview did not become ready");

  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.route("https://neurosql-api.onrender.com/api/health/database", route =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ status: "UP", database: "neurosql_test", postgresVersion: "17.11" }),
    }),
  );
  await page.route("**/api/**", route =>
    route.fulfill({ status: 404, contentType: "application/json", body: "{}" }),
  );

  const viewports = [
    { name: "desktop", width: 1440, height: 900 },
    { name: "tablet-wide", width: 851, height: 900 },
    { name: "tablet-compact", width: 850, height: 900 },
    { name: "mobile", width: 390, height: 844 },
    { name: "narrow-mobile", width: 320, height: 740 },
  ];
  const destinations = ["Workspace", "Schema explorer", "Query history", "Saved queries", "Performance lab"];

  for (const viewport of viewports) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto(baseUrl, { waitUntil: "domcontentloaded" });
    const navigation = page.getByRole("navigation", { name: "Workspace navigation" });
    await navigation.waitFor({ state: "visible" });
    assert.equal(await navigation.getByRole("button").count(), destinations.length,
      `${viewport.name}: all workspace destinations should be rendered`);

    for (const destination of destinations) {
      const button = navigation.getByRole("button", { name: destination, exact: true });
      assert.ok(await button.isVisible(), `${viewport.name}: ${destination} should be visible`);
      const bounds = await button.boundingBox();
      assert.ok(bounds && bounds.width >= 32 && bounds.height >= 32,
        `${viewport.name}: ${destination} should have a usable hit target`);
      await button.click();
      assert.equal((await page.locator(".crumb b").textContent())?.trim(), destination,
        `${viewport.name}: ${destination} should navigate to its page`);
      assert.equal(await button.getAttribute("aria-current"), "page",
        `${viewport.name}: active destination should expose aria-current`);
    }

    const dimensions = await page.evaluate(() => ({
      viewportWidth: window.innerWidth,
      documentWidth: document.documentElement.scrollWidth,
    }));
    assert.ok(dimensions.documentWidth <= dimensions.viewportWidth,
      `${viewport.name}: horizontal overflow (${dimensions.documentWidth}px > ${dimensions.viewportWidth}px)`);
    console.log(`Responsive navigation passed: ${viewport.name} (${viewport.width}x${viewport.height})`);
  }
} finally {
  await browser?.close();
  preview.kill("SIGTERM");
}
