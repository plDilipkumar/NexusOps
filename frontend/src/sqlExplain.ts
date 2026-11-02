export type ExplainPlanNode = {
  nodeType: string;
  relationName: string | null;
  schema: string | null;
  alias: string | null;
  indexName: string | null;
  startupCost: number | null;
  totalCost: number | null;
  planRows: number | null;
  planWidth: number | null;
  children: ExplainPlanNode[];
};

export type ExplainResult = {
  root: ExplainPlanNode;
  elapsedTimeMillis: number;
  analyze: false;
};

type ExplainOptions = {
  apiBaseUrl: string;
  sql: string;
  headers: Record<string, string>;
  signal?: AbortSignal;
};

const MAX_PLAN_CHARACTERS = 65_536;
const MAX_PLAN_NODES = 256;
const MAX_PLAN_DEPTH = 32;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function optionalText(value: unknown): string | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== "string" || value.length > 256) {
    throw new Error("The API returned invalid query-plan metadata.");
  }
  return value;
}

function optionalNumber(value: unknown): number | null {
  if (value === undefined || value === null) return null;
  if (typeof value !== "number" || !Number.isFinite(value) || value < 0) {
    throw new Error("The API returned invalid query-plan estimates.");
  }
  return value;
}

function parsePlanNode(value: unknown, depth: number, count: { value: number }): ExplainPlanNode {
  if (!isRecord(value) || depth > MAX_PLAN_DEPTH || ++count.value > MAX_PLAN_NODES) {
    throw new Error("The API returned an unsupported query-plan structure.");
  }
  const nodeType = value["Node Type"];
  if (typeof nodeType !== "string" || nodeType.length === 0 || nodeType.length > 120) {
    throw new Error("The API returned invalid query-plan metadata.");
  }
  const rawChildren = value.Plans ?? [];
  if (!Array.isArray(rawChildren) || rawChildren.length > 64) {
    throw new Error("The API returned invalid query-plan children.");
  }
  return {
    nodeType,
    relationName: optionalText(value["Relation Name"]),
    schema: optionalText(value.Schema),
    alias: optionalText(value.Alias),
    indexName: optionalText(value["Index Name"]),
    startupCost: optionalNumber(value["Startup Cost"]),
    totalCost: optionalNumber(value["Total Cost"]),
    planRows: optionalNumber(value["Plan Rows"]),
    planWidth: optionalNumber(value["Plan Width"]),
    children: rawChildren.map((child) => parsePlanNode(child, depth + 1, count)),
  };
}

export function parseExplainResult(payload: unknown): ExplainResult {
  if (!isRecord(payload)
      || typeof payload.planJson !== "string"
      || payload.planJson.length === 0
      || payload.planJson.length > MAX_PLAN_CHARACTERS
      || payload.analyze !== false
      || !Number.isSafeInteger(payload.elapsedTimeMillis)
      || (payload.elapsedTimeMillis as number) < 0) {
    throw new Error("The API returned an invalid query-plan response.");
  }

  let decoded: unknown;
  try {
    decoded = JSON.parse(payload.planJson);
  } catch {
    throw new Error("The API returned an invalid JSON query plan.");
  }
  if (!Array.isArray(decoded) || decoded.length !== 1 || !isRecord(decoded[0]) || !("Plan" in decoded[0])) {
    throw new Error("The API returned an unsupported PostgreSQL query plan.");
  }

  return {
    root: parsePlanNode(decoded[0].Plan, 0, { value: 0 }),
    elapsedTimeMillis: payload.elapsedTimeMillis as number,
    analyze: false,
  };
}

function failureMessage(status: number, payload: unknown): string {
  if (status === 400) {
    if (isRecord(payload) && Array.isArray(payload.errors)) {
      const errors = payload.errors.filter((item): item is string => typeof item === "string").slice(0, 5);
      if (errors.length > 0) return errors.join(" ");
    }
    return "The SQL draft was rejected.";
  }
  if (status === 401) return "Your sign-in is no longer valid. Sign in again.";
  if (status === 403) return "Your account cannot inspect plans in the selected workspace.";
  if (status === 404) return "Query-plan analysis is not enabled on the API.";
  if (status === 429) return "Too many query-plan requests. Try again later.";
  if (status === 503) return "The restricted read-only database role is not ready. Plan analysis is unavailable.";
  if (status === 422) return "The query plan could not be generated. Check the SQL and visible schema, then try again.";
  return "The query-plan request failed (HTTP " + status + ").";
}

export async function explainReadOnlyQuery(
  options: ExplainOptions,
  fetcher: typeof fetch = fetch,
): Promise<ExplainResult> {
  const sql = options.sql.trim();
  if (!sql) throw new Error("Enter a SQL query before requesting a plan.");
  if (sql.length > 10_000) throw new Error("SQL must be 10,000 characters or fewer.");
  const authorization = options.headers.Authorization ?? options.headers.authorization;
  if (!authorization || !/^(Basic|Bearer) \S+$/.test(authorization)) {
    throw new Error("Sign in before requesting a query plan.");
  }

  const response = await fetcher(options.apiBaseUrl.replace(/\/$/, "") + "/api/sql/explain", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...options.headers,
    },
    body: JSON.stringify({ sql }),
    cache: "no-store",
    signal: options.signal,
  });

  let payload: unknown = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  if (!response.ok) throw new Error(failureMessage(response.status, payload));
  return parseExplainResult(payload);
}
