import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { setTimeout as delay } from "node:timers/promises";
import { chromium } from "playwright";

const baseUrl = "http://127.0.0.1:4175";
const apiOrigin = "https://neurosql-api.onrender.com";
const preview = spawn("npm", ["run", "preview", "--", "--host", "127.0.0.1", "--port", "4175", "--strictPort"], {
  stdio: "ignore",
});
let browser;

const userId = "123e4567-e89b-12d3-a456-426614174000";
const sessionId = "123e4567-e89b-12d3-a456-426614174001";
const workspaceA = "123e4567-e89b-12d3-a456-426614174002";
const workspaceB = "123e4567-e89b-12d3-a456-426614174003";
const token = "A".repeat(43);

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
  await page.route(apiOrigin + "/api/health/database", route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({ status: "UP", database: "neurosql_test", postgresVersion: "17.11" }),
  }));
  for (const [path, handler] of Object.entries(handlers)) {
    await page.route(apiOrigin + path, handler);
  }
  return unexpectedApiRequests;
}

async function openDurableSignIn(page) {
  await page.goto(baseUrl, { waitUntil: "domcontentloaded" });
  const navigation = page.getByRole("navigation", { name: "Workspace navigation" });
  await navigation.getByRole("button", { name: "Schema explorer", exact: true }).click();
  await page.getByRole("heading", { name: "Sign in to NeuroSQL" }).waitFor({ state: "visible" });
}

async function signIn(page, email = "ALICE@EXAMPLE.COM") {
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("synthetic-test-password-42");
  await page.getByRole("button", { name: "Sign in and load workspaces", exact: true }).click();
}

async function waitForWorkspaceReady(page, expectedId) {
  await page.waitForFunction(id => {
    const select = document.querySelector('select[aria-label="Active workspace"]');
    return select && !select.disabled && select.value === id;
  }, expectedId);
}

async function assertNoBrowserPersistence(page) {
  const storage = await page.evaluate(() => ({
    localStorageEntries: window.localStorage.length,
    sessionStorageEntries: window.sessionStorage.length,
  }));
  assert.deepEqual(storage, { localStorageEntries: 0, sessionStorageEntries: 0 },
    "Bearer tokens and account credentials must not be persisted in browser storage");
}

function schemaPayload(database) {
  return {
    database,
    schemas: [],
    tableCount: 0,
    timestamp: "2026-11-03T16:00:00Z",
  };
}

async function testLoginWorkspaceSwitchAndLogout() {
  const page = await browser.newPage();
  const requests = { login: [], identity: [], workspaces: [], schema: [], logout: [] };
  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  const workspaces = [
    { workspaceId: workspaceA, name: "Analytics", slug: "analytics", role: "OWNER" },
    { workspaceId: workspaceB, name: "Reporting", slug: "reporting", role: "MEMBER" },
  ];
  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      requests.login.push(route.request().postDataJSON());
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token, expiresAt }),
      });
    },
    "/api/auth/me": async route => {
      requests.identity.push(route.request().headers().authorization);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ userId, sessionId, expiresAt }),
      });
    },
    "/api/auth/workspaces": async route => {
      requests.workspaces.push(route.request().headers().authorization);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(workspaces),
      });
    },
    "/api/schema": async route => {
      const headers = route.request().headers();
      requests.schema.push({
        authorization: headers.authorization,
        workspaceId: headers["x-neurosql-workspace-id"],
      });
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(schemaPayload("neurosql_test")),
      });
    },
    "/api/auth/logout": async route => {
      requests.logout.push(route.request().headers().authorization);
      await route.fulfill({ status: 204 });
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page);
    await waitForWorkspaceReady(page, workspaceA);

    assert.deepEqual(requests.login, [{
      email: "alice@example.com",
      password: "synthetic-test-password-42",
    }], "Login must normalize the email and send credentials only to the mocked API");
    assert.deepEqual(requests.identity, ["Bearer " + token],
      "Identity verification must use the newly issued bearer token");
    assert.deepEqual(requests.workspaces, ["Bearer " + token],
      "Workspace loading must use the newly issued bearer token");
    assert.equal(requests.schema.length, 1);
    assert.deepEqual(requests.schema[0], {
      authorization: "Bearer " + token,
      workspaceId: workspaceA,
    }, "The initial schema request must be scoped to the first returned workspace");

    const workspaceSelect = page.getByLabel("Active workspace");
    await workspaceSelect.selectOption(workspaceB);
    await waitForWorkspaceReady(page, workspaceB);
    assert.equal(requests.schema.length, 2, "Switching workspaces must load schema again");
    assert.deepEqual(requests.schema[1], {
      authorization: "Bearer " + token,
      workspaceId: workspaceB,
    }, "The switched schema request must carry the selected workspace ID");

    assert.equal(page.url().includes(token), false, "The bearer token must never appear in the URL");
    await assertNoBrowserPersistence(page);

    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await page.getByRole("heading", { name: "Sign in to NeuroSQL" }).waitFor({ state: "visible" });
    assert.deepEqual(requests.logout, ["Bearer " + token],
      "Logout must request server-side revocation with the active bearer token");
    await assertNoBrowserPersistence(page);
    assert.equal(unexpected.length, 0, "The login/workspace/logout flow must not make unmocked API requests");
    console.log("Durable login, identity, workspace switching, and logout passed (mock API only)");
  } finally {
    await page.close();
  }
}

async function testFixedSessionExpiry() {
  const page = await browser.newPage();
  const expiryToken = "B".repeat(43);
  let expiresAt = "";
  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      expiresAt = new Date(Date.now() + 6000).toISOString();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token: expiryToken, expiresAt }),
      });
    },
    "/api/auth/me": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ userId, sessionId, expiresAt }),
    }),
    "/api/auth/workspaces": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        { workspaceId: workspaceA, name: "Analytics", slug: "analytics", role: "OWNER" },
      ]),
    }),
    "/api/schema": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(schemaPayload("neurosql_test")),
    }),
  });

  try {
    await openDurableSignIn(page);
    await signIn(page);
    await waitForWorkspaceReady(page, workspaceA);
    await page.waitForFunction(() => {
      const alert = document.querySelector('[role="alert"]');
      return alert && alert.textContent.includes("Your session expired. Sign in again.");
    }, null, { timeout: 12000 });
    await page.getByRole("heading", { name: "Sign in to NeuroSQL" }).waitFor({ state: "visible" });
    assert.equal(await page.getByLabel("Active workspace").count(), 0,
      "Expired sessions must clear the active workspace selector");
    assert.equal(page.url().includes(expiryToken), false, "The expired token must never appear in the URL");
    await assertNoBrowserPersistence(page);
    assert.equal(unexpected.length, 0, "Session expiry must not make unmocked API requests");
    console.log("Fixed session expiry and local credential clearing passed (mock API only)");
  } finally {
    await page.close();
  }
}

try {
  await waitForPreview();
  browser = await chromium.launch({ headless: true });
  await testLoginWorkspaceSwitchAndLogout();
  await testFixedSessionExpiry();
} finally {
  await browser?.close();
  preview.kill("SIGTERM");
}
