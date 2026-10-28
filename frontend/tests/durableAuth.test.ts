import assert from "node:assert/strict";
import test from "node:test";
import {
  buildDurableSchemaHeaders,
  isCanonicalWorkspaceId,
  parseIssuedSession,
  parseSessionIdentity,
  parseWorkspaceList,
} from "../src/durableAuth.ts";

const now = Date.parse("2026-10-28T12:00:00.000Z");
const token = "A".repeat(43);
const userId = "123e4567-e89b-12d3-a456-426614174000";
const sessionId = "123e4567-e89b-12d3-a456-426614174001";
const workspaceId = "123e4567-e89b-12d3-a456-426614174002";
const future = "2026-10-28T13:00:00.000Z";

test("accepts an opaque URL-safe token and future ISO expiry", () => {
  assert.deepEqual(parseIssuedSession({ token, expiresAt: future }, now), { token, expiresAt: future });
});

test("rejects malformed, missing, or expired login sessions", () => {
  assert.throws(() => parseIssuedSession({ token: "short", expiresAt: future }, now), /invalid session response/);
  assert.throws(() => parseIssuedSession({ token, expiresAt: "not-a-date" }, now), /invalid session response/);
  assert.throws(() => parseIssuedSession({ token, expiresAt: "2026-10-28T12:00:00.000Z" }, now), /invalid session response/);
  assert.throws(() => parseIssuedSession(null, now), /invalid session response/);
});

test("accepts only UUID identity fields and a future expiry", () => {
  assert.deepEqual(
    parseSessionIdentity({ userId, sessionId, expiresAt: future }, now),
    { userId, sessionId, expiresAt: future },
  );
  assert.throws(() => parseSessionIdentity({ userId: "user-1", sessionId, expiresAt: future }, now), /invalid session identity/);
  assert.throws(() => parseSessionIdentity({ userId, sessionId, expiresAt: "2020-01-01T00:00:00Z" }, now), /invalid session identity/);
});

test("accepts a well-formed workspace list and known roles", () => {
  assert.deepEqual(parseWorkspaceList([
    { workspaceId, name: "Analytics", slug: "analytics", role: "VIEWER" },
  ]), [{ workspaceId, name: "Analytics", slug: "analytics", role: "VIEWER" }]);
  assert.deepEqual(parseWorkspaceList([]), []);
});

test("rejects malformed workspace fields, unknown roles, and duplicate IDs", () => {
  assert.throws(() => parseWorkspaceList([{ workspaceId: "not-a-uuid", name: "Analytics", slug: "analytics", role: "VIEWER" }]), /unexpected workspace list/);
  assert.throws(() => parseWorkspaceList([{ workspaceId, name: " ", slug: "analytics", role: "VIEWER" }]), /unexpected workspace list/);
  assert.throws(() => parseWorkspaceList([{ workspaceId, name: "Analytics", slug: "analytics", role: "SUPERUSER" }]), /unexpected workspace list/);
  assert.throws(() => parseWorkspaceList([
    { workspaceId, name: "One", slug: "one", role: "OWNER" },
    { workspaceId: workspaceId.toUpperCase(), name: "Duplicate", slug: "duplicate", role: "VIEWER" },
  ]), /unexpected workspace list/);
  assert.throws(() => parseWorkspaceList({ workspaceId, name: "Analytics", slug: "analytics", role: "VIEWER" }), /unexpected workspace list/);
});

test("workspace IDs must use the canonical UUID shape", () => {
  assert.equal(isCanonicalWorkspaceId(workspaceId), true);
  assert.equal(isCanonicalWorkspaceId(workspaceId.toUpperCase()), true);
  assert.equal(isCanonicalWorkspaceId("123e4567-e89b-12d3-a456-42661417400"), false);
  assert.equal(isCanonicalWorkspaceId("123e4567e89b12d3a456426614174002"), false);
});

test("schema request headers validate token and workspace before building auth headers", () => {
  assert.deepEqual(buildDurableSchemaHeaders(token, workspaceId), {
    Accept: "application/json",
    Authorization: `Bearer ${token}`,
    "X-NeuroSQL-Workspace-Id": workspaceId,
  });
  assert.throws(() => buildDurableSchemaHeaders("invalid", workspaceId), /session token is invalid/);
  assert.throws(() => buildDurableSchemaHeaders(token, ""), /valid workspace/);
});
