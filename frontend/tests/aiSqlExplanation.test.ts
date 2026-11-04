import assert from "node:assert/strict";
import test from "node:test";
import {
  createAiSqlExplanationRequestGuard,
  explainAiSql,
  parseAiSqlExplanationResponse,
} from "../src/aiSqlExplanation.ts";

const token = "a".repeat(43);
const workspaceId = "6c4f8d4c-1b4a-4f45-9e2d-2d4b1e5a3a11";
const sql = "SELECT id FROM public.orders LIMIT 10";
const headers = {
  Accept: "application/json",
  Authorization: "Bearer " + token,
  "X-NeuroSQL-Workspace-Id": workspaceId,
};
const validPayload = {
  explanation: "Reads recent orders and returns their identifiers.",
  statementType: "SELECT",
  warnings: ["This is an AI-generated explanation, not a verified database result."],
};

function response(payload: unknown, status = 200, retryAfter: string | null = null): Response {
  const headers = new Headers({ "Content-Type": "application/json" });
  if (retryAfter) headers.set("Retry-After", retryAfter);
  return new Response(JSON.stringify(payload), { status, headers });
}

test("parses a bounded plain-text explanation and warnings", () => {
  assert.deepEqual(parseAiSqlExplanationResponse(validPayload), validPayload);
  assert.deepEqual(parseAiSqlExplanationResponse({
    ...validPayload,
    statementType: "WITH",
    explanation: "Uses a common table expression.\nThen reads the result.",
  }).statementType, "WITH");
});

test("rejects malformed, oversized, or unsafe explanation responses", () => {
  assert.throws(() => parseAiSqlExplanationResponse(null), /invalid AI explanation/);
  assert.throws(() => parseAiSqlExplanationResponse({ ...validPayload, statementType: "DELETE" }), /invalid AI explanation/);
  assert.throws(() => parseAiSqlExplanationResponse({ ...validPayload, explanation: " " }), /invalid AI explanation/);
  assert.throws(() => parseAiSqlExplanationResponse({ ...validPayload, explanation: "x".repeat(4_001) }), /invalid AI explanation/);
  assert.throws(() => parseAiSqlExplanationResponse({ ...validPayload, explanation: "safe\u0000unsafe" }), /invalid AI explanation/);
  assert.throws(() => parseAiSqlExplanationResponse({ ...validPayload, warnings: [""] }), /invalid AI explanation/);
  assert.throws(() => parseAiSqlExplanationResponse({ ...validPayload, warnings: ["x".repeat(501)] }), /invalid AI explanation/);
  assert.throws(() => parseAiSqlExplanationResponse({ ...validPayload, warnings: Array(9).fill("warning") }), /invalid AI explanation/);
});

test("AI explanation sends only SQL text with the active durable workspace and no browser credentials", async () => {
  let capturedUrl = "";
  let capturedInit: RequestInit | undefined;
  const result = await explainAiSql({
    apiBaseUrl: "https://api.example.test/",
    sql,
    token,
    workspaceId,
  }, async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return response(validPayload);
  });

  assert.equal(result.explanation, validPayload.explanation);
  assert.equal(capturedUrl, "https://api.example.test/api/sql/ai/explain");
  assert.equal(capturedInit?.method, "POST");
  assert.equal(capturedInit?.cache, "no-store");
  assert.equal(capturedInit?.credentials, "omit");
  assert.equal((capturedInit?.headers as Record<string, string>)["X-NeuroSQL-Workspace-Id"], workspaceId);
  assert.equal((capturedInit?.headers as Record<string, string>).Authorization, "Bearer " + token);
  assert.deepEqual(JSON.parse(String(capturedInit?.body)), { sql });
  assert.equal(String(capturedInit?.body).includes("schema"), false);
  assert.equal(String(capturedInit?.body).includes("rows"), false);
});

test("AI explanation forwards AbortSignal and preserves cancellation", async () => {
  const controller = new AbortController();
  const pending = explainAiSql({
    apiBaseUrl: "https://api.example.test",
    sql,
    token,
    workspaceId,
    signal: controller.signal,
  }, async (_input, init) => new Promise<Response>((_resolve, reject) => {
    assert.equal(init?.signal, controller.signal);
    controller.signal.addEventListener("abort", () => {
      reject(Object.assign(new Error("aborted"), { name: "AbortError" }));
    }, { once: true });
  }));

  controller.abort();
  await assert.rejects(pending, (error: unknown) => (error as { name?: string }).name === "AbortError");
});

test("AI explanation API errors use fixed messages and never expose provider details", async () => {
  const cases: Array<[number, RegExp]> = [
    [400, /request was invalid/],
    [401, /sign-in is no longer valid/],
    [403, /cannot request explanations/],
    [404, /not enabled/],
    [422, /read-only safety checks/],
    [429, /Try again in 30 seconds/],
    [503, /not configured or is temporarily unavailable/],
  ];
  for (const [status, expected] of cases) {
    await assert.rejects(
      explainAiSql({ apiBaseUrl: "https://api.example.test", sql, token, workspaceId },
        async () => response({ message: "private provider URL and API key" }, status, status === 429 ? "30" : null)),
      (error: unknown) => {
        const message = (error as Error).message;
        assert.match(message, expected);
        assert.equal(message.includes("private provider"), false);
        assert.equal(message.includes("API key"), false);
        return true;
      },
    );
  }
});

test("invalid SQL or missing/invalid workspace authentication is rejected before network access", async () => {
  let called = false;
  const fetcher = async () => {
    called = true;
    return response(validPayload);
  };
  await assert.rejects(
    explainAiSql({ apiBaseUrl: "https://api.example.test", sql: " ", token, workspaceId }, fetcher),
    /Enter a SQL statement/,
  );
  await assert.rejects(
    explainAiSql({ apiBaseUrl: "https://api.example.test", sql, token: "", workspaceId }, fetcher),
    /session token is invalid/,
  );
  await assert.rejects(
    explainAiSql({ apiBaseUrl: "https://api.example.test", sql, token, workspaceId: "not-a-uuid" }, fetcher),
    /valid workspace/,
  );
  await assert.rejects(
    explainAiSql({ apiBaseUrl: "https://api.example.test", sql: "x".repeat(10_001), token, workspaceId }, fetcher),
    /10,000 characters/,
  );
  assert.equal(called, false);
});

test("request guard invalidates stale explanations after a workspace or session change", () => {
  const guard = createAiSqlExplanationRequestGuard();
  const oldRequest = guard.begin();
  assert.equal(guard.isCurrent(oldRequest), true);
  guard.invalidate();
  const currentRequest = guard.begin();
  assert.equal(guard.isCurrent(oldRequest), false);
  assert.equal(guard.isCurrent(currentRequest), true);
});
