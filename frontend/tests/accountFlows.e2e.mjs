import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import { chromium } from "playwright";

const baseUrl = "http://127.0.0.1:4174";
const apiOrigin = "https://neurosql-api.onrender.com";
const preview = spawn("npm", ["run", "preview", "--", "--host", "127.0.0.1", "--port", "4174", "--strictPort"], {
  stdio: "ignore",
});
let browser;

async function waitForPreview() {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    try {
      const response = await fetch(baseUrl);
      if (response.ok) return;
    } catch {
      // The preview server may need another short interval to start.
    }
    await delay(200);
  }
  throw new Error("Vite preview did not become ready");
}

async function installApiMocks(page, handlers = {}) {
  const unexpectedApiRequests = [];
  await page.route("**/api/**", async route => {
    unexpectedApiRequests.push(route.request().url());
    await route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({ message: "Unexpected API request in isolated browser test" }),
    });
  });
  await page.route(`${apiOrigin}/api/health/database`, route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ status: "UP", database: "neurosql_test", postgresVersion: "17.11" }),
  }));
  for (const [path, handler] of Object.entries(handlers)) {
    await page.route(`${apiOrigin}${path}`, handler);
  }
  return unexpectedApiRequests;
}

async function openSchemaSignIn(page) {
  await page.goto(baseUrl, { waitUntil: "domcontentloaded" });
  const navigation = page.getByRole("navigation", { name: "Workspace navigation" });
  await navigation.getByRole("button", { name: "Schema explorer", exact: true }).click();
  await page.getByRole("heading", { name: "Sign in to NeuroSQL" }).waitFor({ state: "visible" });
}

async function assertNoBrowserPersistence(page) {
  const storage = await page.evaluate(() => ({
    localStorageEntries: window.localStorage.length,
    sessionStorageEntries: window.sessionStorage.length,
  }));
  assert.deepEqual(storage, { localStorageEntries: 0, sessionStorageEntries: 0 },
    "Account credentials and one-time tokens must not be persisted in browser storage");
}

async function assertStatusContains(page, expected) {
  const status = page.getByRole("status");
  await status.waitFor({ state: "visible" });
  const message = (await status.textContent())?.trim() ?? "";
  assert.ok(message.includes(expected), `Expected status to contain "${expected}", received "${message}"`);
}

async function testRegistrationAndResend() {
  const page = await browser.newPage();
  const requests = { registration: [], resend: [] };
  const unexpected = await installApiMocks(page, {
    "/api/auth/register": async route => {
      requests.registration.push(route.request().postDataJSON());
      await route.fulfill({ status: 202, contentType: "application/json", body: JSON.stringify({ message: "accepted" }) });
    },
    "/api/auth/verification/resend": async route => {
      requests.resend.push(route.request().postDataJSON());
      await route.fulfill({ status: 202, contentType: "application/json", body: JSON.stringify({ message: "accepted" }) });
    },
  });

  try {
    await openSchemaSignIn(page);
    await page.getByRole("button", { name: "Create account", exact: true }).click();
    await page.getByRole("heading", { name: "Create your NeuroSQL account" }).waitFor({ state: "visible" });

    await page.getByLabel("Display name").fill("  Alice Smith  ");
    await page.getByLabel("Email").fill("  ALICE@EXAMPLE.COM ");
    await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-7");
    await page.getByLabel("Confirm password").fill("a-different-password");
    await page.getByRole("button", { name: "Create account", exact: true }).click();
    assert.match((await page.getByRole("alert").textContent()) ?? "", /passwords do not match/i);
    assert.equal(requests.registration.length, 0, "Mismatched passwords must not reach the API");

    await page.getByLabel("Confirm password").fill("correct-horse-battery-7");
    await page.getByRole("button", { name: "Create account", exact: true }).click();
    await assertStatusContains(page, "If the address can receive verification email");
    assert.deepEqual(requests.registration, [{
      displayName: "Alice Smith",
      email: "alice@example.com",
      password: "correct-horse-battery-7",
    }], "Registration should send normalized fields only to the mocked endpoint");

    await page.getByRole("button", { name: "Resend verification email", exact: true }).click();
    await page.getByRole("button", { name: "Send verification email", exact: true }).click();
    await assertStatusContains(page, "If the address can receive verification email");
    assert.deepEqual(requests.resend, [{ email: "alice@example.com" }],
      "Verification resend should use the normalized email and generic accepted response");

    assert.equal(unexpected.length, 0, "Registration flow must not make unmocked API requests");
    assert.equal(page.url().includes("token="), false, "No one-time token should appear in the address bar");
    await assertNoBrowserPersistence(page);
    console.log("Account registration and verification-resend browser flow passed (mock API only)");
  } finally {
    await page.close();
  }
}

async function testEmailVerification() {
  const page = await browser.newPage();
  const token = "V".repeat(43);
  const requests = [];
  const unexpected = await installApiMocks(page, {
    "/api/auth/verify-email": async route => {
      requests.push(route.request().postDataJSON());
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ message: "verified" }) });
    },
  });

  try {
    await page.goto(`${baseUrl}/?campaign=welcome&flow=email-verification&token=${token}#verify`, {
      waitUntil: "domcontentloaded",
    });
    await page.getByRole("heading", { name: "Verify your email" }).waitFor({ state: "visible" });
    await page.waitForFunction(() => !window.location.search.includes("token=") && !window.location.search.includes("flow="));
    assert.equal(page.url().includes(token), false, "Verification token must be removed from the address bar");

    await page.getByRole("button", { name: "Verify email", exact: true }).click();
    await assertStatusContains(page, "Email verified. You can now sign in.");
    assert.deepEqual(requests, [{ token }], "Verification token should be sent only in the mocked POST body");
    assert.equal(unexpected.length, 0, "Email verification must not make unmocked API requests");
    await assertNoBrowserPersistence(page);
    console.log("Email-verification browser flow passed (mock API only)");
  } finally {
    await page.close();
  }
}

async function testPasswordResetRequest() {
  const page = await browser.newPage();
  const requests = [];
  const unexpected = await installApiMocks(page, {
    "/api/auth/password-reset/request": async route => {
      requests.push(route.request().postDataJSON());
      await route.fulfill({ status: 202, contentType: "application/json", body: JSON.stringify({ message: "accepted" }) });
    },
  });

  try {
    await openSchemaSignIn(page);
    await page.getByRole("button", { name: "Forgot password?", exact: true }).click();
    await page.getByRole("heading", { name: "Reset your password" }).waitFor({ state: "visible" });
    await page.getByLabel("Email").fill("  ALICE@EXAMPLE.COM ");
    await page.getByRole("button", { name: "Send reset link", exact: true }).click();
    await assertStatusContains(page, "If an account exists for that address");
    assert.deepEqual(requests, [{ email: "alice@example.com" }],
      "Password-reset request should normalize email before sending it to the mocked endpoint");
    assert.equal(unexpected.length, 0, "Password-reset request must not make unmocked API requests");
    await assertNoBrowserPersistence(page);
    console.log("Password-reset request browser flow passed (mock API only)");
  } finally {
    await page.close();
  }
}

async function testPasswordResetConfirmation() {
  const page = await browser.newPage();
  const token = "R".repeat(43);
  const password = "new-correct-horse-battery-9";
  const requests = [];
  const unexpected = await installApiMocks(page, {
    "/api/auth/password-reset/confirm": async route => {
      requests.push(route.request().postDataJSON());
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ message: "reset" }) });
    },
  });

  try {
    await page.goto(`${baseUrl}/?flow=password-reset&token=${token}`, { waitUntil: "domcontentloaded" });
    await page.getByRole("heading", { name: "Choose a new password" }).waitFor({ state: "visible" });
    await page.waitForFunction(() => !window.location.search.includes("token=") && !window.location.search.includes("flow="));
    assert.equal(page.url().includes(token), false, "Password-reset token must be removed from the address bar");

    await page.getByLabel("New password", { exact: true }).fill(password);
    await page.getByLabel("Confirm new password", { exact: true }).fill("different-password");
    await page.getByRole("button", { name: "Update password", exact: true }).click();
    assert.match((await page.getByRole("alert").textContent()) ?? "", /passwords do not match/i);
    assert.equal(requests.length, 0, "Mismatched reset passwords must not reach the API");

    await page.getByLabel("Confirm new password", { exact: true }).fill(password);
    await page.getByRole("button", { name: "Update password", exact: true }).click();
    await assertStatusContains(page, "Your password has been reset. Please sign in again.");
    assert.deepEqual(requests, [{ token, newPassword: password }],
      "Password-reset token and new password should be sent only in the mocked POST body");
    assert.equal(unexpected.length, 0, "Password-reset confirmation must not make unmocked API requests");
    await assertNoBrowserPersistence(page);

    await page.getByRole("button", { name: "Return to sign in", exact: true }).click();
    await page.getByRole("heading", { name: "Sign in to NeuroSQL" }).waitFor({ state: "visible" });
    console.log("Password-reset confirmation browser flow passed (mock API only)");
  } finally {
    await page.close();
  }
}

try {
  await waitForPreview();
  browser = await chromium.launch({ headless: true });
  await testRegistrationAndResend();
  await testEmailVerification();
  await testPasswordResetRequest();
  await testPasswordResetConfirmation();
} finally {
  await browser?.close();
  preview.kill("SIGTERM");
}
