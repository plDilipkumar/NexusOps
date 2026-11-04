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


async function testStaleWorkspaceSchemaCannotOverwriteNewSelection() {
  const page = await browser.newPage();
  const requests = { login: [], identity: [], workspaces: [], schema: [] };
  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  let markAnalyticsStarted;
  const analyticsStarted = new Promise(resolve => { markAnalyticsStarted = resolve; });
  let releaseAnalytics;
  const analyticsGate = new Promise(resolve => { releaseAnalytics = resolve; });
  let markAnalyticsCompleted;
  const analyticsCompleted = new Promise(resolve => { markAnalyticsCompleted = resolve; });
  let markReportingStarted;
  const reportingStarted = new Promise(resolve => { markReportingStarted = resolve; });
  let releaseReporting;
  const reportingGate = new Promise(resolve => { releaseReporting = resolve; });
  let markReportingCompleted;
  const reportingCompleted = new Promise(resolve => { markReportingCompleted = resolve; });

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
        body: JSON.stringify([
          { workspaceId: workspaceA, name: "Analytics", slug: "analytics", role: "OWNER" },
          { workspaceId: workspaceB, name: "Reporting", slug: "reporting", role: "MEMBER" },
        ]),
      });
    },
    "/api/schema": async route => {
      const headers = route.request().headers();
      const workspaceId = headers["x-neurosql-workspace-id"];
      requests.schema.push({ authorization: headers.authorization, workspaceId });
      if (workspaceId === workspaceA) {
        markAnalyticsStarted();
        await analyticsGate;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            database: "neurosql_analytics",
            schemas: [{ name: "analytics_schema", tables: [] }],
            tableCount: 0,
            timestamp: "2026-11-04T00:00:00Z",
          }),
        });
        markAnalyticsCompleted();
        return;
      }
      if (workspaceId === workspaceB) {
        markReportingStarted();
        await reportingGate;
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({
            database: "neurosql_reporting",
            schemas: [{ name: "reporting_schema", tables: [] }],
            tableCount: 0,
            timestamp: "2026-11-04T00:00:01Z",
          }),
        });
        markReportingCompleted();
        return;
      }
      await route.fulfill({ status: 400, contentType: "application/json", body: JSON.stringify({ message: "Unknown workspace" }) });
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page);
    const workspaceSelect = page.getByLabel("Active workspace");
    await waitForWorkspaceReady(page, workspaceA);
    await Promise.race([
      analyticsStarted,
      delay(5000).then(() => { throw new Error("The initial workspace schema request did not start"); }),
    ]);
    assert.equal(await workspaceSelect.isEnabled(), true,
      "The user must be able to switch workspaces while schema metadata is loading");

    await workspaceSelect.selectOption(workspaceB);
    await Promise.race([
      reportingStarted,
      delay(5000).then(() => { throw new Error("The new workspace schema request did not start"); }),
    ]);
    assert.equal(await workspaceSelect.inputValue(), workspaceB);
    assert.equal(await workspaceSelect.isEnabled(), true,
      "Workspace selection must remain available while the new schema is loading");

    releaseAnalytics();
    await Promise.race([
      analyticsCompleted,
      delay(5000).then(() => { throw new Error("The stale workspace schema response did not complete"); }),
    ]);
    await delay(100);
    assert.equal(await workspaceSelect.inputValue(), workspaceB,
      "A delayed response for the previous workspace must not change the selected workspace");
    assert.equal(await page.getByText("analytics_schema", { exact: true }).count(), 0,
      "A stale schema payload must never be rendered for the newly selected workspace");
    assert.equal(await page.getByText("reporting_schema", { exact: true }).count(), 0,
      "The new workspace schema must remain pending until its own response arrives");
    const refreshingButton = page.getByRole("button", { name: "Refreshing…", exact: true });
    assert.equal(await refreshingButton.isDisabled(), true,
      "A stale request's finally handler must not clear the active workspace loading state");

    releaseReporting();
    await Promise.race([
      reportingCompleted,
      delay(5000).then(() => { throw new Error("The selected workspace schema response did not complete"); }),
    ]);
    await page.getByText("reporting_schema", { exact: true }).waitFor({ state: "visible" });
    await page.getByRole("button", { name: "Refresh schema", exact: true }).waitFor({ state: "visible" });
    assert.equal(await workspaceSelect.inputValue(), workspaceB);
    assert.equal(await workspaceSelect.isEnabled(), true);
    assert.equal(await page.getByText("analytics_schema", { exact: true }).count(), 0);
    assert.equal(await page.getByText("reporting_schema", { exact: true }).count(), 1);
    assert.deepEqual(requests.schema, [
      { authorization: "Bearer " + token, workspaceId: workspaceA },
      { authorization: "Bearer " + token, workspaceId: workspaceB },
    ], "Each schema request must carry the bearer token and the workspace selected when it started");
    assert.equal(await page.getByRole("alert").count(), 0,
      "A stale schema response must not create an error in the newly selected workspace");
    assert.equal(page.url().includes(token), false);
    await assertNoBrowserPersistence(page);
    assert.equal(unexpected.length, 0, "The workspace schema race must not make unmocked API requests");
    console.log("Delayed schema from the previous workspace cannot replace the new workspace schema or clear its loading state (mock API only)");
  } finally {
    releaseAnalytics();
    releaseReporting();
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


async function assertNoDurableWorkspaceState(page) {
  assert.equal(await page.getByLabel("Active workspace").count(), 0,
    "A failed login bootstrap must not retain an active workspace selector");
  assert.equal(await page.getByText("Signed in as", { exact: false }).count(), 0,
    "A failed login bootstrap must not display an authenticated identity");
  assert.equal(await page.locator(".schema-list").count(), 0,
    "A failed login bootstrap must not display workspace schema data");
  assert.equal(await page.getByLabel("Password", { exact: true }).inputValue(), "",
    "A failed login bootstrap must clear the password field");
  await assertNoBrowserPersistence(page);
}

async function assertLoginAlert(page, expected) {
  const alert = page.getByRole("alert");
  await alert.waitFor({ state: "visible" });
  assert.ok((await alert.textContent() ?? "").includes(expected),
    `Expected login error to contain "${expected}"`);
}

async function testInvalidCredentialsDoNotCreateClientSession() {
  const page = await browser.newPage();
  const requests = { login: 0, email: "", passwordPresent: false, identity: 0, workspaces: 0, schema: 0, logout: 0 };
  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      requests.login += 1;
      const body = route.request().postDataJSON();
      requests.email = body.email;
      requests.passwordPresent = typeof body.password === "string" && body.password.length > 0;
      await route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ message: "unauthorized" }) });
    },
    "/api/auth/me": async route => {
      requests.identity += 1;
      await route.fulfill({ status: 200, contentType: "application/json", body: "{}" });
    },
    "/api/auth/workspaces": async route => {
      requests.workspaces += 1;
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
    },
    "/api/schema": async route => {
      requests.schema += 1;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(schemaPayload("neurosql_test")) });
    },
    "/api/auth/logout": async route => {
      requests.logout += 1;
      await route.fulfill({ status: 204 });
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page);
    await assertLoginAlert(page, "Invalid email or password.");
    assert.equal(requests.login, 1);
    assert.equal(requests.email, "alice@example.com");
    assert.equal(requests.passwordPresent, true, "The password must be submitted only to the login endpoint");
    assert.deepEqual(
      { identity: requests.identity, workspaces: requests.workspaces, schema: requests.schema, logout: requests.logout },
      { identity: 0, workspaces: 0, schema: 0, logout: 0 },
      "Rejected credentials must not start identity, workspace, schema, or revocation requests",
    );
    await assertNoDurableWorkspaceState(page);
    assert.equal(page.url().includes(token), false);
    assert.equal(unexpected.length, 0, "Invalid credentials must not cause unmocked API requests");
    console.log("Invalid credentials leave no client session or workspace state (mock API only)");
  } finally {
    await page.close();
  }
}

async function testRejectedIdentityRevokesProvisionallyIssuedSession() {
  const page = await browser.newPage();
  const issuedToken = "C".repeat(43);
  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  const requests = { login: 0, identity: [], workspaces: 0, schema: 0, logout: [] };
  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      requests.login += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token: issuedToken, expiresAt }),
      });
    },
    "/api/auth/me": async route => {
      requests.identity.push(route.request().headers().authorization);
      await route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ message: "unauthorized" }) });
    },
    "/api/auth/workspaces": async route => {
      requests.workspaces += 1;
      await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
    },
    "/api/schema": async route => {
      requests.schema += 1;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(schemaPayload("neurosql_test")) });
    },
    "/api/auth/logout": async route => {
      requests.logout.push(route.request().headers().authorization);
      await route.fulfill({ status: 204 });
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page);
    await assertLoginAlert(page, "The new session could not be validated.");
    assert.equal(requests.login, 1);
    assert.deepEqual(requests.identity, ["Bearer " + issuedToken]);
    assert.deepEqual(requests.logout, ["Bearer " + issuedToken],
      "An issued session rejected by identity verification must be revoked server-side");
    assert.equal(requests.workspaces, 0, "Workspace loading must not start after identity rejection");
    assert.equal(requests.schema, 0, "Schema loading must not start after identity rejection");
    await assertNoDurableWorkspaceState(page);
    assert.equal(page.url().includes(issuedToken), false);
    assert.equal(unexpected.length, 0, "Identity rejection must not cause unmocked API requests");
    console.log("Rejected identity revokes the provisional session and clears client state (mock API only)");
  } finally {
    await page.close();
  }
}

async function testWorkspaceListFailureRevokesProvisionalSession() {
  const page = await browser.newPage();
  const issuedToken = "D".repeat(43);
  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  const requests = { login: 0, identity: [], workspaces: [], schema: 0, logout: [] };
  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      requests.login += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token: issuedToken, expiresAt }),
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
      await route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ message: "unavailable" }) });
    },
    "/api/schema": async route => {
      requests.schema += 1;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(schemaPayload("neurosql_test")) });
    },
    "/api/auth/logout": async route => {
      requests.logout.push(route.request().headers().authorization);
      await route.fulfill({ status: 204 });
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page);
    await assertLoginAlert(page, "Unable to load workspaces (HTTP 503).");
    assert.equal(requests.login, 1);
    assert.deepEqual(requests.identity, ["Bearer " + issuedToken]);
    assert.deepEqual(requests.workspaces, ["Bearer " + issuedToken]);
    assert.deepEqual(requests.logout, ["Bearer " + issuedToken],
      "A workspace bootstrap failure must revoke the provisionally issued session");
    assert.equal(requests.schema, 0, "Schema loading must not start without a successful workspace list");
    await assertNoDurableWorkspaceState(page);
    assert.equal(page.url().includes(issuedToken), false);
    assert.equal(unexpected.length, 0, "Workspace failure must not cause unmocked API requests");
    console.log("Workspace bootstrap failure revokes the provisional session and clears client state (mock API only)");
  } finally {
    await page.close();
  }
}


async function testProvisionalRevocationFailure({ issuedToken, networkFailure }) {
  const page = await browser.newPage();
  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();
  const logoutAuthorization = [];
  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ token: issuedToken, expiresAt }),
    }),
    "/api/auth/me": async route => route.fulfill({
      status: 401,
      contentType: "application/json",
      body: JSON.stringify({ message: "unauthorized" }),
    }),
    "/api/auth/logout": async route => {
      logoutAuthorization.push(route.request().headers().authorization);
      if (networkFailure) {
        await route.abort("failed");
      } else {
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ message: "internal database detail", debug: "must not reach the browser" }),
        });
      }
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page);
    await assertLoginAlert(page, "The new session could not be validated.");
    await assertLoginAlert(page, "The server could not confirm revocation of the provisional session. It may remain valid until it expires.");
    await assertLoginAlert(page, "It may remain valid until it expires.");
    assert.deepEqual(logoutAuthorization, ["Bearer " + issuedToken],
      "The provisional token must be sent only to the mocked revocation endpoint");
    await assertNoDurableWorkspaceState(page);
    const alertText = await page.getByRole("alert").textContent() ?? "";
    assert.equal(alertText.includes("internal database detail"), false,
      "Revocation response details must not be exposed in the browser");
    assert.equal(alertText.includes(issuedToken), false, "The bearer token must never be shown in the warning");
    assert.equal(page.url().includes(issuedToken), false, "The bearer token must never appear in the URL");
    assert.equal(unexpected.length, 0, "Revocation failure must not cause unmocked API requests");
    console.log(networkFailure
      ? "Network failure during provisional revocation is reported without retaining local credentials (mock API only)"
      : "HTTP failure during provisional revocation is reported without retaining local credentials (mock API only)");
  } finally {
    await page.close();
  }
}


async function testUserInitiatedLogoutFailure({ issuedToken, networkFailure, delayResponse = false }) {
  const page = await browser.newPage();
  const logoutAuthorization = [];
  let releaseLogout;
  const logoutGate = new Promise(resolve => { releaseLogout = resolve; });
  let markLogoutStarted;
  const logoutStarted = new Promise(resolve => { markLogoutStarted = resolve; });
  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ token: issuedToken, expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString() }),
    }),
    "/api/auth/me": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ userId, sessionId, expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString() }),
    }),
    "/api/auth/workspaces": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([{ workspaceId: workspaceA, name: "Analytics", slug: "analytics", role: "OWNER" }]),
    }),
    "/api/schema": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(schemaPayload("neurosql_test")),
    }),
    "/api/auth/logout": async route => {
      logoutAuthorization.push(route.request().headers().authorization);
      markLogoutStarted();
      if (delayResponse) await logoutGate;
      if (networkFailure) {
        await route.abort("failed");
      } else {
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ message: "internal database detail", debug: "must not reach the browser" }),
        });
      }
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page);
    await waitForWorkspaceReady(page, workspaceA);
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await logoutStarted;
    await page.getByRole("heading", { name: "Sign in to NeuroSQL" }).waitFor({ state: "visible" });
    await assertNoDurableWorkspaceState(page);
    assert.deepEqual(logoutAuthorization, ["Bearer " + issuedToken],
      "User-initiated logout must send the active bearer token only to the revocation endpoint");
    assert.equal(unexpected.length, 0, "Logout failure must not cause unmocked API requests");
    if (delayResponse) releaseLogout();
    await assertLoginAlert(page, "Signed out locally, but the server could not confirm session revocation. The session may remain valid until it expires.");
    const alertText = await page.getByRole("alert").textContent() ?? "";
    assert.equal(alertText.includes("internal database detail"), false,
      "Revocation response details must not be exposed in the browser");
    assert.equal(alertText.includes(issuedToken), false, "The bearer token must never be shown in the warning");
    assert.equal(page.url().includes(issuedToken), false, "The bearer token must never appear in the URL");
    await assertNoDurableWorkspaceState(page);
    await assertNoBrowserPersistence(page);
    console.log(networkFailure
      ? "User logout network failure clears local state and reports unconfirmed server revocation (mock API only)"
      : "User logout HTTP failure clears local state immediately and reports unconfirmed server revocation (mock API only)");
  } finally {
    releaseLogout();
    await page.close();
  }
}


async function testDelayedLogoutCannotOverwriteNewLogin() {
  const page = await browser.newPage();
  const firstToken = "I".repeat(43);
  const secondToken = "J".repeat(43);
  const issuedTokens = [firstToken, secondToken];
  const schemaAuthorization = [];
  const logoutAuthorization = [];
  let loginCount = 0;
  let releaseLogout;
  const logoutGate = new Promise(resolve => { releaseLogout = resolve; });
  let markLogoutStarted;
  const logoutStarted = new Promise(resolve => { markLogoutStarted = resolve; });
  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      const issuedToken = issuedTokens[loginCount++];
      assert.ok(issuedToken, "Only the two expected login attempts should occur");
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          token: issuedToken,
          expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
        }),
      });
    },
    "/api/auth/me": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        userId,
        sessionId,
        expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
      }),
    }),
    "/api/auth/workspaces": async route => route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify([
        { workspaceId: workspaceA, name: "Analytics", slug: "analytics", role: "OWNER" },
      ]),
    }),
    "/api/schema": async route => {
      schemaAuthorization.push(route.request().headers().authorization);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(schemaPayload("neurosql_test")),
      });
    },
    "/api/auth/logout": async route => {
      logoutAuthorization.push(route.request().headers().authorization);
      markLogoutStarted();
      await logoutGate;
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ message: "stale logout failure must not replace a newer session" }),
      });
    },
  });

  try {
    await openDurableSignIn(page);

    // Establish the first session, then start a server revocation that remains pending.
    await signIn(page);
    await waitForWorkspaceReady(page, workspaceA);
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await logoutStarted;
    await page.getByRole("heading", { name: "Sign in to NeuroSQL" }).waitFor({ state: "visible" });

    // A second login completes before the first session's logout request returns.
    await signIn(page);
    await waitForWorkspaceReady(page, workspaceA);
    await page.getByText("Signed in as", { exact: false }).waitFor({ state: "visible" });
    assert.equal(loginCount, 2, "The user must have completed a fresh login");
    assert.deepEqual(schemaAuthorization, ["Bearer " + firstToken, "Bearer " + secondToken],
      "Schema requests must use the token belonging to each respective login");
    assert.deepEqual(logoutAuthorization, ["Bearer " + firstToken],
      "The delayed revocation must belong only to the earlier session");

    const staleLogoutResponse = page.waitForResponse(response =>
      response.url() === apiOrigin + "/api/auth/logout" && response.status() === 503);
    releaseLogout();
    await staleLogoutResponse;
    await delay(50);

    // The late failure belongs to an obsolete auth operation and must not clear
    // or decorate the newer authenticated workspace.
    await waitForWorkspaceReady(page, workspaceA);
    await page.getByText("Signed in as", { exact: false }).waitFor({ state: "visible" });
    assert.equal(await page.getByRole("button", { name: "Sign out", exact: true }).count(), 1,
      "The newer session must remain signed in after the older logout fails");
    assert.equal(
      await page.getByText("Signed out locally, but the server could not confirm session revocation.", { exact: false }).count(),
      0,
      "A stale logout warning must not overwrite the newer login state",
    );
    await assertNoBrowserPersistence(page);
    assert.equal(unexpected.length, 0, "The concurrency scenario must not make unmocked API requests");
    console.log("A delayed logout failure cannot overwrite a newer successful login (mock API only)");
  } finally {
    releaseLogout();
    await page.close();
  }
}


async function submitSignInWhilePending(page, email) {
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill("synthetic-test-password-42");
  await page.locator("form.schema-login").evaluate(form => {
    if (!(form instanceof HTMLFormElement)) throw new Error("Durable sign-in form was not found");
    form.requestSubmit();
  });
}

async function testSupersededLoginResponseCannotReplaceNewSession({ stage }) {
  const page = await browser.newPage();
  const oldToken = "K".repeat(43);
  const newToken = "L".repeat(43);
  const oldAuthorization = "Bearer " + oldToken;
  const newAuthorization = "Bearer " + newToken;
  const requests = { login: [], identity: [], workspaces: [], schema: [], logout: [] };
  let releaseOldStage;
  const oldStageGate = new Promise(resolve => { releaseOldStage = resolve; });
  let markOldStageStarted;
  const oldStageStarted = new Promise(resolve => { markOldStageStarted = resolve; });
  let markNewSchemaReady;
  const newSchemaReady = new Promise(resolve => { markNewSchemaReady = resolve; });
  let markOldRevocationCompleted;
  const oldRevocationCompleted = new Promise(resolve => { markOldRevocationCompleted = resolve; });
  let markNewLoginStarted;
  const newLoginStarted = new Promise(resolve => { markNewLoginStarted = resolve; });

  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      const email = route.request().postDataJSON().email;
      requests.login.push(email);
      if (email === "old@example.com") {
        if (stage === "login") {
          markOldStageStarted();
          await oldStageGate;
        }
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ token: oldToken, expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString() }),
        });
      } else {
        markNewLoginStarted();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify({ token: newToken, expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString() }),
        });
      }
    },
    "/api/auth/me": async route => {
      const authorization = route.request().headers().authorization;
      requests.identity.push(authorization);
      if (authorization === oldAuthorization && stage === "identity") {
        markOldStageStarted();
        await oldStageGate;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ userId, sessionId, expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString() }),
      });
    },
    "/api/auth/workspaces": async route => {
      const authorization = route.request().headers().authorization;
      requests.workspaces.push(authorization);
      if (authorization === oldAuthorization && stage === "workspaces") {
        markOldStageStarted();
        await oldStageGate;
      }
      const oldSession = authorization === oldAuthorization;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([{
          workspaceId: oldSession ? workspaceA : workspaceB,
          name: oldSession ? "Old workspace" : "New workspace",
          slug: oldSession ? "old-workspace" : "new-workspace",
          role: "OWNER",
        }]),
      });
    },
    "/api/schema": async route => {
      const authorization = route.request().headers().authorization;
      requests.schema.push({
        authorization,
        workspaceId: route.request().headers()["x-neurosql-workspace-id"],
      });
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(schemaPayload("neurosql_test")),
      });
      if (authorization === newAuthorization) markNewSchemaReady();
    },
    "/api/auth/logout": async route => {
      const authorization = route.request().headers().authorization;
      requests.logout.push(authorization);
      await route.fulfill({ status: 204 });
      if (authorization === oldAuthorization) markOldRevocationCompleted();
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page, "old@example.com");
    await oldStageStarted;

    // A newer login starts while the older login/identity/workspace response is still pending.
    await submitSignInWhilePending(page, "new@example.com");
    await newLoginStarted;
    await newSchemaReady;
    await waitForWorkspaceReady(page, workspaceB);
    await page.getByText("Signed in as new@example.com", { exact: false }).waitFor({ state: "visible" });

    releaseOldStage();
    await Promise.race([
      oldRevocationCompleted,
      delay(5000).then(() => { throw new Error("The superseded provisional session was not revoked"); }),
    ]);

    await waitForWorkspaceReady(page, workspaceB);
    assert.deepEqual(requests.login, ["old@example.com", "new@example.com"]);
    assert.deepEqual(requests.schema, [{
      authorization: newAuthorization,
      workspaceId: workspaceB,
    }], "Only the newer session may load workspace schema");
    assert.deepEqual(requests.logout, [oldAuthorization],
      "The superseded session must be revoked without touching the newer session");
    assert.equal(await page.getByText("Signed in as new@example.com", { exact: false }).count(), 1);
    assert.equal(await page.getByText("Signed in as old@example.com", { exact: false }).count(), 0);
    assert.equal(await page.getByRole("button", { name: "Sign out", exact: true }).count(), 1);
    assert.equal(await page.getByText("The server could not confirm revocation of the provisional session.", { exact: false }).count(), 0);
    assert.equal(await page.getByRole("alert").count(), 0, "A stale response must not replace the newer screen with an error");
    await assertNoBrowserPersistence(page);
    assert.equal(unexpected.length, 0, "The superseded-response scenario must not make unmocked API requests");

    const expectedIdentity = stage === "login" ? [newAuthorization] : [oldAuthorization, newAuthorization];
    const expectedWorkspaces = stage === "workspaces" ? [oldAuthorization, newAuthorization] : [newAuthorization];
    assert.deepEqual(requests.identity, expectedIdentity, "Identity requests must stop when the older operation is superseded");
    assert.deepEqual(requests.workspaces, expectedWorkspaces, "Workspace requests must stop when the older operation is superseded");
    console.log("Superseded " + stage + " response cannot replace the newer authenticated workspace (mock API only)");
  } finally {
    releaseOldStage();
    await page.close();
  }
}


async function testAccountFlowSwitchSupersedesPendingBootstrap({ stage, buttonName, heading, issuedToken, revocationFailure = null }) {
  assert.ok(stage === "identity" || stage === "workspaces", "Only identity and workspace bootstrap stages are supported");
  const page = await browser.newPage();
  const authorization = "Bearer " + issuedToken;
  const requests = { login: [], identity: [], workspaces: [], schema: [], logout: [] };
  let markStageStarted;
  const stageStarted = new Promise(resolve => { markStageStarted = resolve; });
  let releaseStage;
  const stageGate = new Promise(resolve => { releaseStage = resolve; });
  let markRevocationCompleted;
  const revocationCompleted = new Promise(resolve => { markRevocationCompleted = resolve; });
  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();

  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      requests.login.push(route.request().postDataJSON());
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token: issuedToken, expiresAt }),
      });
    },
    "/api/auth/me": async route => {
      requests.identity.push(route.request().headers().authorization);
      if (stage === "identity") {
        markStageStarted();
        await stageGate;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ userId, sessionId, expiresAt }),
      });
    },
    "/api/auth/workspaces": async route => {
      requests.workspaces.push(route.request().headers().authorization);
      if (stage === "workspaces") {
        markStageStarted();
        await stageGate;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify([{
          workspaceId: workspaceA,
          name: "Analytics",
          slug: "analytics",
          role: "OWNER",
        }]),
      });
    },
    "/api/schema": async route => {
      requests.schema.push(route.request().headers().authorization);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(schemaPayload("neurosql_test")),
      });
    },
    "/api/auth/logout": async route => {
      requests.logout.push(route.request().headers().authorization);
      if (revocationFailure === "network") {
        await route.abort("failed");
        markRevocationCompleted();
        return;
      }
      if (revocationFailure === "http") {
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ message: "sensitive server failure detail" }),
        });
        markRevocationCompleted();
        return;
      }
      await route.fulfill({ status: 204 });
      markRevocationCompleted();
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page, "bootstrap-navigation@example.com");
    await Promise.race([
      stageStarted,
      delay(5000).then(() => { throw new Error("The " + stage + " request did not start"); }),
    ]);

    // Navigate away while the already-issued session is waiting on identity/workspace bootstrap.
    await page.getByRole("button", { name: buttonName, exact: true }).click();
    await page.getByRole("heading", { name: heading, exact: true }).waitFor({ state: "visible" });

    releaseStage();
    await Promise.race([
      revocationCompleted,
      delay(5000).then(() => { throw new Error("The provisional session was not revoked after " + stage + " navigation"); }),
    ]);

    await page.getByRole("heading", { name: heading, exact: true }).waitFor({ state: "visible" });
    assert.deepEqual(requests.login, [{
      email: "bootstrap-navigation@example.com",
      password: "synthetic-test-password-42",
    }]);
    assert.deepEqual(requests.identity, [authorization], "Identity verification must use the issued provisional token");
    assert.deepEqual(requests.workspaces, stage === "workspaces" ? [authorization] : [],
      "Workspace loading must stop if navigation supersedes identity verification");
    assert.deepEqual(requests.schema, [], "A superseded bootstrap must never load workspace schema");
    assert.deepEqual(requests.logout, [authorization], "The issued provisional session must be revoked after navigation");
    assert.equal(await page.getByText("Signed in as bootstrap-navigation@example.com", { exact: false }).count(), 0,
      "A superseded bootstrap must not install an authenticated identity");
    if (revocationFailure) {
      const alert = page.getByRole("alert");
      await alert.waitFor({ state: "visible" });
      const warning = await alert.innerText();
      assert.ok(warning.includes("The selected account flow remains open, but the server could not confirm revocation of the provisional session. It may remain valid until it expires."));
      assert.equal(warning.includes(issuedToken), false, "The provisional bearer token must never appear in the warning");
      assert.equal(warning.includes("sensitive server failure detail"), false, "Server response details must never be rendered");
    } else {
      assert.equal(await page.getByRole("alert").count(), 0, "A successfully revoked stale bootstrap must not show a warning");
    }
    assert.equal(await page.getByRole("heading", { name: "Sign in to NeuroSQL", exact: true }).count(), 0,
      "The late bootstrap response must not return the user to sign-in");
    assert.equal(page.url().includes(issuedToken), false);
    await assertNoBrowserPersistence(page);
    assert.equal(unexpected.length, 0, "Account-flow navigation during " + stage + " must not make unmocked API requests");
    console.log("Account-flow navigation supersedes pending " + stage + " bootstrap and revokes its provisional session (mock API only)");
  } finally {
    releaseStage();
    await page.close();
  }
}


async function testOldBootstrapRevocationFailureDoesNotOverwriteNewLogin() {
  const page = await browser.newPage();
  const oldToken = "S".repeat(43);
  const newToken = "T".repeat(43);
  const oldAuthorization = "Bearer " + oldToken;
  const newAuthorization = "Bearer " + newToken;
  const requests = { login: [], identity: [], workspaces: [], schema: [], logout: [] };
  let loginCount = 0;
  let markOldIdentityStarted;
  const oldIdentityStarted = new Promise(resolve => { markOldIdentityStarted = resolve; });
  let releaseOldIdentity;
  const oldIdentityGate = new Promise(resolve => { releaseOldIdentity = resolve; });
  let markOldRevocationCompleted;
  const oldRevocationCompleted = new Promise(resolve => { markOldRevocationCompleted = resolve; });
  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();

  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      requests.login.push(route.request().postDataJSON());
      loginCount += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token: loginCount === 1 ? oldToken : newToken, expiresAt }),
      });
    },
    "/api/auth/me": async route => {
      const authorization = route.request().headers().authorization;
      requests.identity.push(authorization);
      if (authorization === oldAuthorization) {
        markOldIdentityStarted();
        await oldIdentityGate;
      }
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
        body: JSON.stringify([{
          workspaceId: workspaceA,
          name: "Analytics",
          slug: "analytics",
          role: "OWNER",
        }]),
      });
    },
    "/api/schema": async route => {
      requests.schema.push(route.request().headers().authorization);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(schemaPayload("neurosql_test")),
      });
    },
    "/api/auth/logout": async route => {
      requests.logout.push(route.request().headers().authorization);
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ message: "sensitive server failure detail" }),
      });
      markOldRevocationCompleted();
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page, "old@example.com");
    await Promise.race([
      oldIdentityStarted,
      delay(5000).then(() => { throw new Error("The old identity request did not start"); }),
    ]);

    await page.getByRole("button", { name: "Forgot password?", exact: true }).click();
    await page.getByRole("heading", { name: "Reset your password", exact: true }).waitFor({ state: "visible" });
    await page.getByRole("button", { name: "Back to sign in", exact: true }).click();
    await page.getByRole("heading", { name: "Sign in to NeuroSQL", exact: true }).waitFor({ state: "visible" });

    await signIn(page, "new@example.com");
    await waitForWorkspaceReady(page, workspaceA);
    releaseOldIdentity();
    await Promise.race([
      oldRevocationCompleted,
      delay(5000).then(() => { throw new Error("The old provisional session revocation did not finish"); }),
    ]);

    assert.deepEqual(requests.login, [
      { email: "old@example.com", password: "synthetic-test-password-42" },
      { email: "new@example.com", password: "synthetic-test-password-42" },
    ]);
    assert.deepEqual(requests.identity, [oldAuthorization, newAuthorization]);
    assert.deepEqual(requests.workspaces, [newAuthorization],
      "The superseded identity response must not continue into old workspace loading");
    assert.deepEqual(requests.schema, [newAuthorization],
      "Only the newer login may load workspace schema");
    assert.deepEqual(requests.logout, [oldAuthorization],
      "The old provisional session must be revoked without affecting the newer session");
    assert.equal(await page.getByText("Signed in as new@example.com", { exact: false }).count(), 1);
    assert.equal(await page.getByRole("button", { name: "Sign out", exact: true }).count(), 1);
    assert.equal(await page.getByRole("alert").count(), 0,
      "A failed revocation from the superseded login must not overwrite the newer authenticated workspace");
    await assertNoBrowserPersistence(page);
    assert.equal(page.url().includes(oldToken), false);
    assert.equal(page.url().includes(newToken), false);
    assert.equal(unexpected.length, 0, "The stale-revocation/new-login race must not make unmocked API requests");
    console.log("Failed revocation of a superseded provisional session cannot overwrite a newer login (mock API only)");
  } finally {
    releaseOldIdentity();
    await page.close();
  }
}


async function testAccountFlowSwitchSupersedesPendingLogin({ buttonName, heading, issuedToken, returnToSignIn = false }) {
  const page = await browser.newPage();
  const requests = { login: [], identity: [], workspaces: [], schema: [], logout: [] };
  let markLoginStarted;
  const loginStarted = new Promise(resolve => { markLoginStarted = resolve; });
  let releaseLogin;
  const loginGate = new Promise(resolve => { releaseLogin = resolve; });
  let markRevocationCompleted;
  const revocationCompleted = new Promise(resolve => { markRevocationCompleted = resolve; });
  const expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString();

  const unexpected = await installApiMocks(page, {
    "/api/auth/login": async route => {
      requests.login.push(route.request().postDataJSON());
      markLoginStarted();
      await loginGate;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({ token: issuedToken, expiresAt }),
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
        body: JSON.stringify([{
          workspaceId: workspaceA,
          name: "Analytics",
          slug: "analytics",
          role: "OWNER",
        }]),
      });
    },
    "/api/schema": async route => {
      requests.schema.push(route.request().headers().authorization);
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(schemaPayload("neurosql_test")),
      });
    },
    "/api/auth/logout": async route => {
      requests.logout.push(route.request().headers().authorization);
      await route.fulfill({ status: 204 });
      markRevocationCompleted();
    },
  });

  try {
    await openDurableSignIn(page);
    await signIn(page, "navigation@example.com");
    await Promise.race([
      loginStarted,
      delay(5000).then(() => { throw new Error("The pending login request did not start"); }),
    ]);

    if (returnToSignIn) {
      await page.getByRole("button", { name: "Forgot password?", exact: true }).click();
      await page.getByRole("heading", { name: "Reset your password", exact: true }).waitFor({ state: "visible" });
      await page.getByRole("button", { name: "Back to sign in", exact: true }).click();
      await page.getByRole("heading", { name: "Sign in to NeuroSQL", exact: true }).waitFor({ state: "visible" });
    } else {
      await page.getByRole("button", { name: buttonName, exact: true }).click();
      await page.getByRole("heading", { name: heading, exact: true }).waitFor({ state: "visible" });
    }

    // The old login response is deliberately released only after the account-flow navigation.
    releaseLogin();
    await Promise.race([
      revocationCompleted,
      delay(5000).then(() => { throw new Error("The superseded provisional login session was not revoked"); }),
    ]);

    if (returnToSignIn) {
      await page.getByRole("heading", { name: "Sign in to NeuroSQL", exact: true }).waitFor({ state: "visible" });
      const submit = page.getByRole("button", { name: "Sign in and load workspaces", exact: true });
      assert.equal(await submit.isDisabled(), false, "Returning to sign-in must clear the stale loading state");
      assert.equal(await page.getByLabel("Password", { exact: true }).inputValue(), "",
        "Leaving the account flow must clear the previous login password");
    } else {
      await page.getByRole("heading", { name: heading, exact: true }).waitFor({ state: "visible" });
      assert.equal(await page.getByRole("button", { name: "Back to sign in", exact: true }).count(), 1);
      assert.equal(await page.getByRole("heading", { name: "Sign in to NeuroSQL", exact: true }).count(), 0,
        "A late login response must not replace the selected account-flow screen");
    }

    assert.deepEqual(requests.login, [{
      email: "navigation@example.com",
      password: "synthetic-test-password-42",
    }]);
    assert.deepEqual(requests.logout, ["Bearer " + issuedToken],
      "The late-issued provisional session must be revoked after navigation supersedes login");
    assert.deepEqual(
      { identity: requests.identity, workspaces: requests.workspaces, schema: requests.schema },
      { identity: [], workspaces: [], schema: [] },
      "A superseded login must stop before identity, workspace, or schema requests",
    );
    assert.equal(await page.getByRole("alert").count(), 0, "A stale login must not show an error over the selected screen");
    assert.equal(page.url().includes(issuedToken), false);
    await assertNoBrowserPersistence(page);
    assert.equal(unexpected.length, 0, "Account-flow navigation must not make unmocked API requests");
    console.log(returnToSignIn
      ? "Returning to sign-in supersedes a pending durable login (mock API only)"
      : buttonName + " supersedes a pending durable login (mock API only)");
  } finally {
    releaseLogin();
    await page.close();
  }
}

async function testAccountFlowBackSupersedesPendingLogin() {
  await testAccountFlowSwitchSupersedesPendingLogin({
    issuedToken: "L".repeat(43),
    returnToSignIn: true,
  });
}

try {
  await waitForPreview();
  browser = await chromium.launch({ headless: true });
  await testLoginWorkspaceSwitchAndLogout();
  await testStaleWorkspaceSchemaCannotOverwriteNewSelection();
  await testFixedSessionExpiry();
  await testInvalidCredentialsDoNotCreateClientSession();
  await testRejectedIdentityRevokesProvisionallyIssuedSession();
  await testWorkspaceListFailureRevokesProvisionalSession();
  await testProvisionalRevocationFailure({ issuedToken: "E".repeat(43), networkFailure: false });
  await testProvisionalRevocationFailure({ issuedToken: "F".repeat(43), networkFailure: true });
  await testUserInitiatedLogoutFailure({ issuedToken: "G".repeat(43), networkFailure: false, delayResponse: true });
  await testUserInitiatedLogoutFailure({ issuedToken: "H".repeat(43), networkFailure: true });
  await testDelayedLogoutCannotOverwriteNewLogin();
  await testSupersededLoginResponseCannotReplaceNewSession({ stage: "login" });
  await testSupersededLoginResponseCannotReplaceNewSession({ stage: "identity" });
  await testSupersededLoginResponseCannotReplaceNewSession({ stage: "workspaces" });
  await testAccountFlowSwitchSupersedesPendingLogin({
    buttonName: "Forgot password?",
    heading: "Reset your password",
    issuedToken: "I".repeat(43),
  });
  await testAccountFlowSwitchSupersedesPendingLogin({
    buttonName: "Create account",
    heading: "Create your NeuroSQL account",
    issuedToken: "J".repeat(43),
  });
  await testAccountFlowSwitchSupersedesPendingLogin({
    buttonName: "Resend verification email",
    heading: "Resend verification email",
    issuedToken: "K".repeat(43),
  });
  await testAccountFlowBackSupersedesPendingLogin();
  await testAccountFlowSwitchSupersedesPendingBootstrap({
    stage: "identity",
    buttonName: "Forgot password?",
    heading: "Reset your password",
    issuedToken: "M".repeat(43),
  });
  await testAccountFlowSwitchSupersedesPendingBootstrap({
    stage: "workspaces",
    buttonName: "Create account",
    heading: "Create your NeuroSQL account",
    issuedToken: "N".repeat(43),
  });
  await testOldBootstrapRevocationFailureDoesNotOverwriteNewLogin();
  await testAccountFlowSwitchSupersedesPendingBootstrap({
    stage: "identity",
    buttonName: "Forgot password?",
    heading: "Reset your password",
    issuedToken: "O".repeat(43),
    revocationFailure: "http",
  });
  await testAccountFlowSwitchSupersedesPendingBootstrap({
    stage: "identity",
    buttonName: "Forgot password?",
    heading: "Reset your password",
    issuedToken: "P".repeat(43),
    revocationFailure: "network",
  });
  await testAccountFlowSwitchSupersedesPendingBootstrap({
    stage: "workspaces",
    buttonName: "Create account",
    heading: "Create your NeuroSQL account",
    issuedToken: "Q".repeat(43),
    revocationFailure: "http",
  });
  await testAccountFlowSwitchSupersedesPendingBootstrap({
    stage: "workspaces",
    buttonName: "Create account",
    heading: "Create your NeuroSQL account",
    issuedToken: "R".repeat(43),
    revocationFailure: "network",
  });
} finally {
  await browser?.close();
  preview.kill("SIGTERM");
}
