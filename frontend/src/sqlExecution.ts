export type QueryColumn = { name: string; databaseType: string; jdbcType: number };
export type QueryResult = {
  columns: QueryColumn[];
  rows: (string | null)[][];
  rowCount: number;
  truncated: boolean;
  executionTimeMillis: number;
};

type ExecuteReadOnlyQueryOptions = {
  apiBaseUrl: string;
  sql: string;
  headers: Record<string, string>;
  signal?: AbortSignal;
  executionId?: string;
};

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function secureRandomUuid(): string {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === "function") return cryptoApi.randomUUID();
  if (typeof cryptoApi?.getRandomValues !== "function") {
    throw new Error("Secure random query identifiers are unavailable in this browser.");
  }
  const bytes = cryptoApi.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, "0")).join("");
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20)].join("-");
}

export function createExecutionId(randomUuid: () => string = secureRandomUuid): string {
  const executionId = randomUuid();
  if (!UUID_PATTERN.test(executionId)) {
    throw new Error("A valid query execution identifier could not be created.");
  }
  return executionId.toLowerCase();
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function parseQueryResult(payload: unknown): QueryResult {
  if (!isRecord(payload) || !Array.isArray(payload.columns) || !Array.isArray(payload.rows)) {
    throw new Error("The API returned an invalid query result.");
  }
  if (payload.columns.length > 100 || payload.rows.length > 2_000) {
    throw new Error("The API returned a result larger than the supported limit.");
  }

  const columns: QueryColumn[] = payload.columns.map((column) => {
    if (
      !isRecord(column) ||
      typeof column.name !== "string" ||
      typeof column.databaseType !== "string" ||
      !Number.isInteger(column.jdbcType)
    ) {
      throw new Error("The API returned invalid column metadata.");
    }
    return {
      name: column.name,
      databaseType: column.databaseType,
      jdbcType: column.jdbcType as number,
    };
  });

  const rows: (string | null)[][] = payload.rows.map((row) => {
    if (!Array.isArray(row) || row.length !== columns.length) {
      throw new Error("The API returned an invalid row shape.");
    }
    return row.map((cell) => {
      if (cell !== null && typeof cell !== "string") {
        throw new Error("The API returned an invalid cell value.");
      }
      return cell as string | null;
    });
  });

  if (
    !Number.isSafeInteger(payload.rowCount) ||
    payload.rowCount !== rows.length ||
    typeof payload.truncated !== "boolean" ||
    !Number.isSafeInteger(payload.executionTimeMillis) ||
    (payload.executionTimeMillis as number) < 0
  ) {
    throw new Error("The API returned invalid query result metadata.");
  }

  return {
    columns,
    rows,
    rowCount: payload.rowCount as number,
    truncated: payload.truncated,
    executionTimeMillis: payload.executionTimeMillis as number,
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
  if (status === 403) return "Your account cannot run queries in the selected workspace.";
  if (status === 404) return "SQL execution is not enabled on the API.";
  if (status === 409) {
    if (isRecord(payload) && payload.message === "The query was cancelled.") return "The query was cancelled.";
    return "The query request conflicted with another active execution. Retry it.";
  }
  if (status === 429) return "Too many query requests. Try again later.";
  if (status === 503) return "The restricted read-only database role is not ready. Query execution is unavailable.";
  if (status === 422) return "The query could not be completed. Check the SQL and visible schema, then try again.";
  return "The query request failed (HTTP " + status + ").";
}

export async function executeReadOnlyQuery(
  options: ExecuteReadOnlyQueryOptions,
  fetcher: typeof fetch = fetch,
): Promise<QueryResult> {
  const sql = options.sql.trim();
  if (!sql) throw new Error("Enter a SQL query before running it.");
  if (sql.length > 10_000) throw new Error("SQL must be 10,000 characters or fewer.");
  const authorization = options.headers.Authorization ?? options.headers.authorization;
  if (!authorization || !/^(Basic|Bearer) \S+$/.test(authorization)) {
    throw new Error("Sign in before running a query.");
  }

  const requestHeaders: Record<string, string> = {
    Accept: "application/json",
    "Content-Type": "application/json",
    ...options.headers,
  };
  if (options.executionId !== undefined) {
    if (!UUID_PATTERN.test(options.executionId)) {
      throw new Error("The query execution identifier is invalid.");
    }
    if (!/^Bearer \S+$/.test(authorization) || !options.headers["X-NeuroSQL-Workspace-Id"]) {
      throw new Error("Server-side cancellation requires an authenticated durable workspace session.");
    }
    requestHeaders["X-NeuroSQL-Execution-Id"] = options.executionId.toLowerCase();
  }

  const baseUrl = options.apiBaseUrl.endsWith("/") ? options.apiBaseUrl.slice(0, -1) : options.apiBaseUrl;
  const response = await fetcher(baseUrl + "/api/sql/execute", {
    method: "POST",
    headers: requestHeaders,
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
  return parseQueryResult(payload);
}


export type CancelQueryResult = "requested" | "not-active";

type CancelReadOnlyQueryOptions = {
  apiBaseUrl: string;
  executionId: string;
  headers: Record<string, string>;
};

function cancelFailureMessage(status: number): string {
  if (status === 400) return "The query execution identifier was rejected.";
  if (status === 401) return "Your sign-in is no longer valid. Sign in again.";
  if (status === 403) return "Query cancellation requires an authenticated durable workspace session.";
  if (status === 429) return "Too many cancellation requests. Try again shortly.";
  if (status === 503) return "The server could not cancel the query. It may have finished.";
  return "The cancellation request failed (HTTP " + status + ").";
}

export async function cancelReadOnlyQuery(
  options: CancelReadOnlyQueryOptions,
  fetcher: typeof fetch = fetch,
): Promise<CancelQueryResult> {
  if (!UUID_PATTERN.test(options.executionId)) {
    throw new Error("The query execution identifier is invalid.");
  }
  const authorization = options.headers.Authorization ?? options.headers.authorization;
  if (!authorization || !/^Bearer \S+$/.test(authorization) || !options.headers["X-NeuroSQL-Workspace-Id"]) {
    throw new Error("Sign in with a durable workspace session before cancelling a query.");
  }

  const baseUrl = options.apiBaseUrl.endsWith("/") ? options.apiBaseUrl.slice(0, -1) : options.apiBaseUrl;
  const response = await fetcher(baseUrl + "/api/sql/cancel", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...options.headers,
    },
    body: JSON.stringify({ executionId: options.executionId.toLowerCase() }),
    cache: "no-store",
  });
  if (response.status === 404) return "not-active";

  let payload: unknown = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  if (!response.ok) throw new Error(cancelFailureMessage(response.status));
  if (response.status !== 202 || !isRecord(payload) || payload.status !== "CANCEL_REQUESTED") {
    throw new Error("The API returned an invalid query-cancellation response.");
  }
  return "requested";
}
