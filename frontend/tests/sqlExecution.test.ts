import assert from "node:assert/strict";
import test from "node:test";
import { cancelReadOnlyQuery, createExecutionId, executeReadOnlyQuery, parseQueryResult } from "../src/sqlExecution.ts";

const validResult = {
  columns: [{ name: "answer", databaseType: "int4", jdbcType: 4 }],
  rows: [["42"]],
  rowCount: 1,
  truncated: false,
  executionTimeMillis: 3,
};

test("parses bounded query columns, rows, and execution metadata", () => {
  assert.deepEqual(parseQueryResult(validResult), validResult);
});

test("rejects inconsistent result metadata and malformed row cells", () => {
  assert.throws(() => parseQueryResult({ ...validResult, rowCount: 2 }), /invalid query result metadata/);
  assert.throws(() => parseQueryResult({ ...validResult, rows: [[42]] }), /invalid cell value/);
  assert.throws(() => parseQueryResult({ ...validResult, rows: [[]] }), /invalid row shape/);
  assert.throws(() => parseQueryResult({ ...validResult, columns: [{ name: "x", databaseType: "int4", jdbcType: "4" }] }), /invalid column metadata/);
});

test("posts a trimmed SQL draft with no-store and the supplied auth/workspace headers", async () => {
  let capturedUrl = "";
  let capturedInit: RequestInit | undefined;
  const fetcher: typeof fetch = async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return new Response(JSON.stringify(validResult), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  };

  const result = await executeReadOnlyQuery({
    apiBaseUrl: "https://api.example.test/",
    sql: "  SELECT 42  ",
    headers: {
      Authorization: "Bearer " + "A".repeat(43),
      "X-NeuroSQL-Workspace-Id": "123e4567-e89b-12d3-a456-426614174002",
    },
    executionId: "123e4567-e89b-42d3-a456-426614174000",
  }, fetcher);

  assert.deepEqual(result, validResult);
  assert.equal(capturedUrl, "https://api.example.test/api/sql/execute");
  assert.equal(capturedInit?.method, "POST");
  assert.equal(capturedInit?.cache, "no-store");
  assert.equal(capturedInit?.headers && (capturedInit.headers as Record<string, string>)["X-NeuroSQL-Workspace-Id"], "123e4567-e89b-12d3-a456-426614174002");
  assert.equal(capturedInit?.body, JSON.stringify({ sql: "SELECT 42" }));
  assert.equal((capturedInit?.headers as Record<string, string>)["X-NeuroSQL-Execution-Id"], "123e4567-e89b-42d3-a456-426614174000");
});

test("surfaces validator rejections without exposing arbitrary API error fields", async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify({
    message: "The SQL draft was rejected.",
    errors: ["Only SELECT or WITH queries are accepted."],
    internal: "secret-driver-detail",
  }), { status: 400, headers: { "Content-Type": "application/json" } });

  await assert.rejects(
    executeReadOnlyQuery({
      apiBaseUrl: "https://api.example.test",
      sql: "DELETE FROM probe",
      headers: { Authorization: "Basic dGVzdDp0ZXN0" },
    }, fetcher),
    /Only SELECT or WITH queries are accepted/,
  );
});

test("maps unavailable read-only role to an actionable sanitized message", async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify({
    message: "SQL execution is not ready.",
    internal: "do-not-show",
  }), { status: 503 });

  await assert.rejects(
    executeReadOnlyQuery({
      apiBaseUrl: "https://api.example.test",
      sql: "SELECT 1",
      headers: { Authorization: "Basic dGVzdDp0ZXN0" },
    }, fetcher),
    /restricted read-only database role is not ready/,
  );
});

test("rejects blank, oversized, and unauthenticated requests before network access", async () => {
  let calls = 0;
  const fetcher: typeof fetch = async () => {
    calls += 1;
    return new Response(JSON.stringify(validResult), { status: 200 });
  };
  const base = { apiBaseUrl: "https://api.example.test", headers: { Authorization: "Basic dGVzdDp0ZXN0" } };
  await assert.rejects(executeReadOnlyQuery({ ...base, sql: "  " }, fetcher), /Enter a SQL query/);
  await assert.rejects(executeReadOnlyQuery({ ...base, sql: "x".repeat(10_001) }, fetcher), /10,000 characters/);
  await assert.rejects(executeReadOnlyQuery({ ...base, sql: "SELECT 1", headers: {} }, fetcher), /Sign in/);
  assert.equal(calls, 0);
});


test("creates canonical lowercase execution identifiers from a secure UUID source", () => {
  assert.equal(
    createExecutionId(() => "123E4567-E89B-42D3-A456-426614174000"),
    "123e4567-e89b-42d3-a456-426614174000",
  );
  assert.throws(() => createExecutionId(() => "not-a-uuid"), /valid query execution identifier/);
});

test("requires a durable workspace session before attaching a cancellation identifier", async () => {
  let calls = 0;
  const fetcher: typeof fetch = async () => {
    calls += 1;
    return new Response(JSON.stringify(validResult), { status: 200 });
  };
  await assert.rejects(
    executeReadOnlyQuery({
      apiBaseUrl: "https://api.example.test",
      sql: "SELECT 1",
      headers: { Authorization: "Basic dGVzdDp0ZXN0" },
      executionId: "123e4567-e89b-42d3-a456-426614174000",
    }, fetcher),
    /durable workspace session/,
  );
  assert.equal(calls, 0);
});

test("posts a workspace-scoped cancellation request and validates the accepted response", async () => {
  let capturedUrl = "";
  let capturedInit: RequestInit | undefined;
  const fetcher: typeof fetch = async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return new Response(JSON.stringify({ status: "CANCEL_REQUESTED" }), {
      status: 202,
      headers: { "Content-Type": "application/json" },
    });
  };
  const result = await cancelReadOnlyQuery({
    apiBaseUrl: "https://api.example.test/",
    executionId: "123e4567-e89b-42d3-a456-426614174000",
    headers: {
      Authorization: "Bearer " + "A".repeat(43),
      "X-NeuroSQL-Workspace-Id": "123e4567-e89b-12d3-a456-426614174002",
    },
  }, fetcher);

  assert.equal(result, "requested");
  assert.equal(capturedUrl, "https://api.example.test/api/sql/cancel");
  assert.equal(capturedInit?.method, "POST");
  assert.equal(capturedInit?.cache, "no-store");
  assert.equal(capturedInit?.body, JSON.stringify({ executionId: "123e4567-e89b-42d3-a456-426614174000" }));
  assert.equal((capturedInit?.headers as Record<string, string>).Authorization, "Bearer " + "A".repeat(43));
  assert.equal((capturedInit?.headers as Record<string, string>)["X-NeuroSQL-Workspace-Id"], "123e4567-e89b-12d3-a456-426614174002");
});

test("treats a completed or unknown execution as not active", async () => {
  const result = await cancelReadOnlyQuery({
    apiBaseUrl: "https://api.example.test",
    executionId: "123e4567-e89b-42d3-a456-426614174000",
    headers: {
      Authorization: "Bearer " + "A".repeat(43),
      "X-NeuroSQL-Workspace-Id": "123e4567-e89b-12d3-a456-426614174002",
    },
  }, async () => new Response(JSON.stringify({ message: "No active query was found." }), { status: 404 }));
  assert.equal(result, "not-active");
});

test("rejects cancellation for malformed identifiers and legacy Basic authentication before network access", async () => {
  let calls = 0;
  const fetcher: typeof fetch = async () => {
    calls += 1;
    return new Response(JSON.stringify({ status: "CANCEL_REQUESTED" }), { status: 202 });
  };
  const base = {
    apiBaseUrl: "https://api.example.test",
    headers: {
      Authorization: "Basic dGVzdDp0ZXN0",
      "X-NeuroSQL-Workspace-Id": "123e4567-e89b-12d3-a456-426614174002",
    },
  };
  await assert.rejects(cancelReadOnlyQuery({ ...base, executionId: "bad-id" }, fetcher), /identifier is invalid/);
  await assert.rejects(cancelReadOnlyQuery({ ...base, executionId: "123e4567-e89b-42d3-a456-426614174000" }, fetcher), /durable workspace session/);
  assert.equal(calls, 0);
});

test("maps rejected cancellation responses to sanitized errors", async () => {
  const headers = {
    Authorization: "Bearer " + "A".repeat(43),
    "X-NeuroSQL-Workspace-Id": "123e4567-e89b-12d3-a456-426614174002",
  };
  await assert.rejects(
    cancelReadOnlyQuery({ apiBaseUrl: "https://api.example.test", executionId: "123e4567-e89b-42d3-a456-426614174000", headers },
      async () => new Response(JSON.stringify({ message: "secret database detail" }), { status: 503 })),
    /server could not cancel the query/,
  );
});


test("distinguishes a cancelled query from an execution identifier conflict", async () => {
  const options = {
    apiBaseUrl: "https://api.example.test",
    sql: "SELECT 1",
    headers: { Authorization: "Bearer " + "A".repeat(43), "X-NeuroSQL-Workspace-Id": "123e4567-e89b-12d3-a456-426614174002" },
    executionId: "123e4567-e89b-42d3-a456-426614174000",
  };
  await assert.rejects(
    executeReadOnlyQuery(options, async () => new Response(JSON.stringify({ message: "The query was cancelled.", errors: [] }), { status: 409 })),
    /The query was cancelled/,
  );
  await assert.rejects(
    executeReadOnlyQuery(options, async () => new Response(JSON.stringify({ message: "An execution with this identifier is already active.", errors: [] }), { status: 409 })),
    /conflicted with another active execution/,
  );
});
