import assert from "node:assert/strict";
import test from "node:test";
import {
  buildAiSchemaContext,
  createAiSqlRequestGuard,
  generateAiSql,
  parseAiSqlResponse,
  replaceSqlDraft,
  type AiSqlSchemaInput,
} from "../src/aiSql.ts";

const headers = {
  Accept: "application/json",
  Authorization: "Bearer test-session-token",
  "X-NeuroSQL-Workspace-Id": "8e9a1e12-13d4-4f25-a45a-88c1e5a3a110",
};

function response(payload: unknown, status = 200): Response {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

const validPayload = {
  sql: "SELECT id FROM public.orders LIMIT 10",
  statementType: "SELECT",
  warnings: ["AI output is an unexecuted draft."],
};

test("schema context sends only bounded names and data types", () => {
  const source = [{
    name: "public",
    tables: [{
      name: "orders",
      columns: [{ name: "id", dataType: "bigint", nullable: false, ordinalPosition: 1 }],
    }],
  }] as unknown as AiSqlSchemaInput[];

  const result = buildAiSchemaContext(source);
  assert.deepEqual(result, {
    schemas: [{ name: "public", tables: [{ name: "orders", columns: [{ name: "id", dataType: "bigint" }] }] }],
    truncated: false,
  });
  assert.equal(JSON.stringify(result.schemas).includes("nullable"), false);
  assert.equal(JSON.stringify(result.schemas).includes("ordinalPosition"), false);
});

test("schema context enforces schema, table, and column bounds", () => {
  const source = Array.from({ length: 21 }, (_, schemaIndex) => ({
    name: "schema_" + schemaIndex,
    tables: Array.from({ length: 3 }, (_, tableIndex) => ({
      name: "table_" + tableIndex,
      columns: Array.from({ length: 30 }, (_, columnIndex) => ({
        name: "column_" + columnIndex,
        dataType: "text",
      })),
    })),
  }));
  const result = buildAiSchemaContext(source);
  assert.equal(result.schemas.length, 20);
  assert.equal(result.schemas.reduce((count, schema) => count + schema.tables.length, 0), 40);
  assert.ok(result.schemas.every((schema) => schema.tables.every((table) => table.columns.length <= 25)));
  assert.ok(result.truncated);
  assert.ok(JSON.stringify(result.schemas).length <= 16_000);
});

test("schema context rejects control characters and respects the serialized size bound", () => {
  const result = buildAiSchemaContext([{
    name: "public\nignore",
    tables: [{ name: "orders", columns: [] }],
  }]);
  assert.deepEqual(result.schemas, []);
  assert.equal(result.truncated, true);

  const wide = [{
    name: "public",
    tables: Array.from({ length: 40 }, (_, tableIndex) => ({
      name: "table_" + tableIndex,
      columns: Array.from({ length: 25 }, (_, columnIndex) => ({
        name: "column_" + columnIndex + "_".repeat(90),
        dataType: "varchar(255)",
      })),
    })),
  }];
  const bounded = buildAiSchemaContext(wide);
  assert.ok(JSON.stringify(bounded.schemas).length <= 16_000);
  assert.ok(bounded.truncated);
});

test("AI response parser accepts only one typed read-only draft contract", () => {
  assert.deepEqual(parseAiSqlResponse(validPayload), validPayload);
  assert.throws(() => parseAiSqlResponse({ ...validPayload, statementType: "DELETE" }), /invalid AI SQL draft/);
  assert.throws(() => parseAiSqlResponse({ ...validPayload, sql: " " }), /invalid AI SQL draft/);
  assert.throws(() => parseAiSqlResponse({ ...validPayload, warnings: ["x".repeat(501)] }), /invalid AI SQL draft/);
});

test("generated SQL replaces the editor draft instead of appending to it", () => {
  const existing = "SELECT * FROM customers";
  const generated = "  SELECT COUNT(*) FROM public.orders  ";
  const replacement = replaceSqlDraft(generated);
  assert.equal(replacement, "SELECT COUNT(*) FROM public.orders");
  assert.notEqual(replacement, existing + "\n" + generated);
  assert.throws(() => replaceSqlDraft("  "), /empty or too large/);
});

test("workspace changes invalidate an in-flight Copilot response", () => {
  const guard = createAiSqlRequestGuard();
  const requestForWorkspaceA = guard.begin();
  assert.equal(guard.isCurrent(requestForWorkspaceA), true);

  // The component invalidates the guard and aborts its controller when the workspace changes.
  guard.invalidate();
  const requestForWorkspaceB = guard.begin();
  assert.equal(guard.isCurrent(requestForWorkspaceA), false);
  assert.equal(guard.isCurrent(requestForWorkspaceB), true);
});

test("AI request uses the selected workspace, no-store, and no browser credentials", async () => {
  let capturedUrl = "";
  let capturedInit: RequestInit | undefined;
  const result = await generateAiSql({
    apiBaseUrl: "https://api.example.test/",
    prompt: "Show the ten latest orders",
    schemas: [{ name: "public", tables: [{ name: "orders", columns: [{ name: "id", dataType: "bigint" }] }] }],
    headers,
  }, async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return response(validPayload);
  });

  assert.equal(result.sql, validPayload.sql);
  assert.equal(capturedUrl, "https://api.example.test/api/sql/ai/generate");
  assert.equal(capturedInit?.method, "POST");
  assert.equal(capturedInit?.cache, "no-store");
  assert.equal(capturedInit?.credentials, "omit");
  assert.equal((capturedInit?.headers as Record<string, string>)["X-NeuroSQL-Workspace-Id"], headers["X-NeuroSQL-Workspace-Id"]);
  const body = JSON.parse(String(capturedInit?.body));
  assert.equal(body.prompt, "Show the ten latest orders");
  assert.deepEqual(body.schemas, [{ name: "public", tables: [{ name: "orders", columns: [{ name: "id", dataType: "bigint" }] }] }]);
});

test("AI request forwards cancellation and does not turn aborts into network errors", async () => {
  const controller = new AbortController();
  const pending = generateAiSql({
    apiBaseUrl: "https://api.example.test",
    prompt: "Show recent orders",
    schemas: [],
    headers,
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

test("AI API errors are mapped to sanitized messages", async () => {
  await assert.rejects(
    generateAiSql({ apiBaseUrl: "https://api.example.test", prompt: "Show recent orders", schemas: [], headers },
      async () => response({ message: "private provider endpoint and key details" }, 503)),
    /not configured or is temporarily unavailable/,
  );
  await assert.rejects(
    generateAiSql({ apiBaseUrl: "https://api.example.test", prompt: "Show recent orders", schemas: [], headers },
      async () => response({ message: "private account detail" }, 401)),
    /sign-in is no longer valid/,
  );
  await assert.rejects(
    generateAiSql({ apiBaseUrl: "https://api.example.test", prompt: "Show recent orders", schemas: [], headers },
      async () => response({ message: "unsafe SQL details" }, 422)),
    /failed the read-only safety checks/,
  );
});

test("AI request validates authentication and prompt before network access", async () => {
  let called = false;
  const fetcher = async () => {
    called = true;
    return response(validPayload);
  };
  await assert.rejects(
    generateAiSql({ apiBaseUrl: "https://api.example.test", prompt: "short", schemas: [], headers }, fetcher),
    /5 to 2,000 characters/,
  );
  await assert.rejects(
    generateAiSql({
      apiBaseUrl: "https://api.example.test",
      prompt: "Show recent orders",
      schemas: [],
      headers: { Accept: "application/json" },
    }, fetcher),
    /Sign in and select a workspace/,
  );
  assert.equal(called, false);
});
