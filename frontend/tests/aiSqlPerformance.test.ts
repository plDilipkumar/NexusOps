import assert from "node:assert/strict";
import test from "node:test";
import {
  createAiSqlPerformanceRequestLifecycle,
  isAiSqlPerformanceRequestContextCurrent,
  parseAiSqlPerformanceResponse,
  recommendAiSqlPerformance,
} from "../src/aiSqlPerformance.ts";

const token = "a".repeat(43);
const workspaceId = "6c4f8d4c-1b4a-4f45-9e2d-2d4b1e5a3a11";
const sql = "SELECT customer_id, COUNT(*) FROM public.orders GROUP BY customer_id";
const validPayload = {
  recommendations: "The plan estimates a sequential scan. Compare its estimated row count with current table statistics before considering an index.",
  statementType: "SELECT",
  planTruncated: false,
  warnings: [
    "This uses a non-ANALYZE EXPLAIN plan; the SQL query was not executed.",
    "Planner costs and estimated rows are not measured runtime or actual row counts.",
  ],
};

function response(payload: unknown, status = 200, retryAfter: string | null = null): Response {
  const headers = new Headers({ "Content-Type": "application/json" });
  if (retryAfter) headers.set("Retry-After", retryAfter);
  return new Response(JSON.stringify(payload), { status, headers });
}

test("parses bounded recommendations and planner-estimate warnings", () => {
  assert.deepEqual(parseAiSqlPerformanceResponse(validPayload), validPayload);
  assert.equal(parseAiSqlPerformanceResponse({
    ...validPayload,
    statementType: "WITH",
    planTruncated: true,
  }).planTruncated, true);
});

test("rejects malformed, oversized, or unsafe recommendation responses", () => {
  assert.throws(() => parseAiSqlPerformanceResponse(null), /invalid AI performance recommendation/);
  assert.throws(() => parseAiSqlPerformanceResponse({ ...validPayload, statementType: "DELETE" }), /invalid AI performance recommendation/);
  assert.throws(() => parseAiSqlPerformanceResponse({ ...validPayload, planTruncated: "false" }), /invalid AI performance recommendation/);
  assert.throws(() => parseAiSqlPerformanceResponse({ ...validPayload, recommendations: " " }), /invalid AI performance recommendation/);
  assert.throws(() => parseAiSqlPerformanceResponse({ ...validPayload, recommendations: "x".repeat(4_001) }), /invalid AI performance recommendation/);
  assert.throws(() => parseAiSqlPerformanceResponse({ ...validPayload, recommendations: "safe\u0000unsafe" }), /invalid AI performance recommendation/);
  assert.throws(() => parseAiSqlPerformanceResponse({ ...validPayload, warnings: [""] }), /invalid AI performance recommendation/);
  assert.throws(() => parseAiSqlPerformanceResponse({ ...validPayload, warnings: ["x".repeat(501)] }), /invalid AI performance recommendation/);
  assert.throws(() => parseAiSqlPerformanceResponse({ ...validPayload, warnings: Array(9).fill("warning") }), /invalid AI performance recommendation/);
});

test("posts only the SQL draft to NeuroSQL with the active durable workspace", async () => {
  let capturedUrl = "";
  let capturedInit: RequestInit | undefined;
  const result = await recommendAiSqlPerformance({
    apiBaseUrl: "https://api.example.test/",
    sql,
    token,
    workspaceId,
  }, async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return response(validPayload);
  });

  assert.equal(result.recommendations, validPayload.recommendations);
  assert.equal(capturedUrl, "https://api.example.test/api/sql/ai/performance");
  assert.equal(capturedInit?.method, "POST");
  assert.equal(capturedInit?.cache, "no-store");
  assert.equal(capturedInit?.credentials, "omit");
  assert.equal((capturedInit?.headers as Record<string, string>).Authorization, "Bearer " + token);
  assert.equal((capturedInit?.headers as Record<string, string>)["X-NeuroSQL-Workspace-Id"], workspaceId);
  assert.deepEqual(JSON.parse(String(capturedInit?.body)), { sql });
  assert.equal(String(capturedInit?.body).includes("rows"), false);
  assert.equal(String(capturedInit?.body).includes("schema"), false);
});

test("forwards AbortSignal to the performance request", async () => {
  const controller = new AbortController();
  const pending = recommendAiSqlPerformance({
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

test("maps API errors to fixed messages without exposing provider details", async () => {
  const cases: Array<[number, RegExp]> = [
    [400, /request was invalid/],
    [401, /sign-in is no longer valid/],
    [403, /cannot request performance recommendations/],
    [404, /not enabled/],
    [422, /read-only validation/],
    [429, /Try again in 25 seconds/],
    [503, /restricted read-only database role/],
  ];
  for (const [status, expected] of cases) {
    await assert.rejects(
      recommendAiSqlPerformance({ apiBaseUrl: "https://api.example.test", sql, token, workspaceId },
        async () => response({ message: "private provider endpoint and secret API key" }, status, status === 429 ? "25" : null)),
      (error: unknown) => {
        const message = (error as Error).message;
        assert.match(message, expected);
        assert.equal(message.includes("private provider"), false);
        assert.equal(message.includes("secret API key"), false);
        return true;
      },
    );
  }
});

test("rejects invalid SQL and workspace credentials before network access", async () => {
  let called = false;
  const fetcher = async () => {
    called = true;
    return response(validPayload);
  };
  await assert.rejects(
    recommendAiSqlPerformance({ apiBaseUrl: "https://api.example.test", sql: " ", token, workspaceId }, fetcher),
    /Enter a SQL statement/,
  );
  await assert.rejects(
    recommendAiSqlPerformance({ apiBaseUrl: "https://api.example.test", sql: "x".repeat(10_001), token, workspaceId }, fetcher),
    /10,000 characters/,
  );
  await assert.rejects(
    recommendAiSqlPerformance({ apiBaseUrl: "https://api.example.test", sql, token: "", workspaceId }, fetcher),
    /session token is invalid/,
  );
  await assert.rejects(
    recommendAiSqlPerformance({ apiBaseUrl: "https://api.example.test", sql, token, workspaceId: "not-a-uuid" }, fetcher),
    /valid workspace/,
  );
  assert.equal(called, false);
});

test("panel lifecycle aborts the active request when its context is invalidated", () => {
  const lifecycle = createAiSqlPerformanceRequestLifecycle();
  const request = lifecycle.begin();

  assert.equal(request.isCurrent(), true);
  assert.equal(request.signal.aborted, false);

  lifecycle.invalidate();

  assert.equal(request.signal.aborted, true);
  assert.equal(request.isCurrent(), false);
});

test("starting a replacement request aborts the previous request without affecting the new one", () => {
  const lifecycle = createAiSqlPerformanceRequestLifecycle();
  const first = lifecycle.begin();
  const second = lifecycle.begin();

  assert.equal(first.signal.aborted, true);
  assert.equal(first.isCurrent(), false);
  assert.equal(second.signal.aborted, false);
  assert.equal(second.isCurrent(), true);

  first.finish();
  assert.equal(second.isCurrent(), true);
  second.finish();
  assert.equal(second.isCurrent(), false);
});

test("panel request context changes invalidate stale SQL, workspace, session, API, flag, and busy states", () => {
  const context = {
    apiBaseUrl: "https://api.example.test",
    enabled: true,
    token,
    workspaceId,
    sql,
    busy: false,
  };

  assert.equal(isAiSqlPerformanceRequestContextCurrent(context, context), true);
  const changes: Array<Partial<typeof context>> = [
    { apiBaseUrl: "https://other.example.test" },
    { enabled: false },
    { token: "b".repeat(43) },
    { workspaceId: "8b6b8d2d-5c0e-4a15-9d71-38e0a5c9a441" },
    { sql: "SELECT 1" },
    { busy: true },
  ];

  for (const change of changes) {
    assert.equal(isAiSqlPerformanceRequestContextCurrent({ ...context, ...change }, context), false);
  }
});

test("finishing an obsolete request cannot clear a newer in-flight request", () => {
  const lifecycle = createAiSqlPerformanceRequestLifecycle();
  const obsolete = lifecycle.begin();
  const current = lifecycle.begin();

  obsolete.finish();

  assert.equal(obsolete.signal.aborted, true);
  assert.equal(current.isCurrent(), true);
  assert.equal(current.signal.aborted, false);
});
