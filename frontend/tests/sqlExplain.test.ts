import assert from "node:assert/strict";
import test from "node:test";
import { explainReadOnlyQuery, parseExplainResult } from "../src/sqlExplain.ts";

const planJson = JSON.stringify([{
  Plan: {
    "Node Type": "Limit",
    "Startup Cost": 0,
    "Total Cost": 12.5,
    "Plan Rows": 10,
    "Plan Width": 24,
    Plans: [{
      "Node Type": "Index Scan",
      "Schema": "public",
      "Relation Name": "orders",
      Alias: "o",
      "Index Name": "orders_created_at_idx",
      "Startup Cost": 0.2,
      "Total Cost": 8.75,
      "Plan Rows": 10,
      "Plan Width": 24,
    }],
  },
}]);

const validResponse = { planJson, elapsedTimeMillis: 4, analyze: false };

test("parses a bounded PostgreSQL plan tree and estimated costs", () => {
  const result = parseExplainResult(validResponse);
  assert.equal(result.analyze, false);
  assert.equal(result.root.nodeType, "Limit");
  assert.equal(result.root.totalCost, 12.5);
  assert.equal(result.root.children[0].relationName, "orders");
  assert.equal(result.root.children[0].indexName, "orders_created_at_idx");
  assert.equal(result.elapsedTimeMillis, 4);
});

test("rejects ANALYZE responses, malformed JSON, and invalid plan nodes", () => {
  assert.throws(() => parseExplainResult({ ...validResponse, analyze: true }), /invalid query-plan response/);
  assert.throws(() => parseExplainResult({ ...validResponse, planJson: "{" }), /invalid JSON query plan/);
  assert.throws(() => parseExplainResult({ ...validResponse, planJson: JSON.stringify([{ Plan: { "Node Type": 42 } }]) }), /invalid query-plan metadata/);
});

test("rejects plans above the response-size and node-count bounds", () => {
  assert.throws(() => parseExplainResult({ ...validResponse, planJson: "x".repeat(65_537) }), /invalid query-plan response/);
  const nested = (depth: number): Record<string, unknown> => depth === 0
    ? { "Node Type": "Result" }
    : { "Node Type": "Result", Plans: [nested(depth - 1)] };
  assert.throws(() => parseExplainResult({
    ...validResponse,
    planJson: JSON.stringify([{ Plan: nested(34) }]),
  }), /unsupported query-plan structure/);
});

test("posts the current SQL draft with authentication and no-store semantics", async () => {
  let capturedUrl = "";
  let capturedInit: RequestInit | undefined;
  const fetcher: typeof fetch = async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return new Response(JSON.stringify(validResponse), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  };

  const result = await explainReadOnlyQuery({
    apiBaseUrl: "https://api.example.test/",
    sql: "  SELECT 42  ",
    headers: {
      Authorization: "Bearer " + "A".repeat(43),
      "X-NeuroSQL-Workspace-Id": "123e4567-e89b-12d3-a456-426614174002",
    },
  }, fetcher);

  assert.equal(result.root.nodeType, "Limit");
  assert.equal(capturedUrl, "https://api.example.test/api/sql/explain");
  assert.equal(capturedInit?.method, "POST");
  assert.equal(capturedInit?.cache, "no-store");
  assert.equal(capturedInit?.body, JSON.stringify({ sql: "SELECT 42" }));
  assert.equal((capturedInit?.headers as Record<string, string>)["X-NeuroSQL-Workspace-Id"],
    "123e4567-e89b-12d3-a456-426614174002");
});

test("maps restricted-role readiness failures without exposing server details", async () => {
  const fetcher: typeof fetch = async () => new Response(JSON.stringify({
    message: "The runtime role is not ready.",
    internal: "secret-driver-detail",
  }), { status: 503 });

  await assert.rejects(explainReadOnlyQuery({
    apiBaseUrl: "https://api.example.test",
    sql: "SELECT 1",
    headers: { Authorization: "Basic dGVzdDp0ZXN0" },
  }, fetcher), /restricted read-only database role is not ready/);
});

test("rejects blank, oversized, and unauthenticated drafts before network access", async () => {
  let calls = 0;
  const fetcher: typeof fetch = async () => {
    calls += 1;
    return new Response(JSON.stringify(validResponse), { status: 200 });
  };
  const base = { apiBaseUrl: "https://api.example.test", headers: { Authorization: "Basic dGVzdDp0ZXN0" } };
  await assert.rejects(explainReadOnlyQuery({ ...base, sql: "  " }, fetcher), /Enter a SQL query/);
  await assert.rejects(explainReadOnlyQuery({ ...base, sql: "x".repeat(10_001) }, fetcher), /10,000 characters/);
  await assert.rejects(explainReadOnlyQuery({ ...base, sql: "SELECT 1", headers: {} }, fetcher), /Sign in/);
  assert.equal(calls, 0);
});
