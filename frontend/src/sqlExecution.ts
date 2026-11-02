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
};

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

  const response = await fetcher(options.apiBaseUrl.replace(/\/$/, "") + "/api/sql/execute", {
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
  return parseQueryResult(payload);
}
