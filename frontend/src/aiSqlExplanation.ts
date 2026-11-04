import { buildDurableSchemaHeaders } from "./durableAuth.ts";

export type AiSqlExplanationResult = {
  explanation: string;
  statementType: "SELECT" | "WITH";
  warnings: string[];
};

type ExplainAiSqlOptions = {
  apiBaseUrl: string;
  sql: string;
  token: string;
  workspaceId: string;
  signal?: AbortSignal;
};

const MAX_SQL_LENGTH = 10_000;
const MAX_EXPLANATION_LENGTH = 4_000;
const MAX_WARNINGS = 8;
const MAX_WARNING_LENGTH = 500;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function hasUnsafeControlCharacters(value: string): boolean {
  return Array.from(value).some((character) => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint < 32 && codePoint !== 9 && codePoint !== 10 && codePoint !== 13;
  });
}

export function parseAiSqlExplanationResponse(payload: unknown): AiSqlExplanationResult {
  if (!isRecord(payload)
      || typeof payload.explanation !== "string"
      || payload.explanation.trim().length === 0
      || payload.explanation.length > MAX_EXPLANATION_LENGTH
      || hasUnsafeControlCharacters(payload.explanation)
      || (payload.statementType !== "SELECT" && payload.statementType !== "WITH")
      || !Array.isArray(payload.warnings)
      || payload.warnings.length > MAX_WARNINGS
      || payload.warnings.some((warning) =>
        typeof warning !== "string"
        || warning.trim().length === 0
        || warning.length > MAX_WARNING_LENGTH
        || hasUnsafeControlCharacters(warning))) {
    throw new Error("The API returned an invalid AI explanation.");
  }

  return {
    explanation: payload.explanation.trim(),
    statementType: payload.statementType,
    warnings: payload.warnings.map((warning) => (warning as string).trim()),
  };
}

function failureMessage(status: number, retryAfter: string | null): string {
  if (status === 400) return "The AI explanation request was invalid.";
  if (status === 401) return "Your sign-in is no longer valid. Sign in again.";
  if (status === 403) return "Your account cannot request explanations in the selected workspace.";
  if (status === 404) return "AI SQL explanation is not enabled on the API.";
  if (status === 422) return "This SQL statement did not pass the read-only safety checks.";
  if (status === 429) {
    const seconds = retryAfter && /^[1-9][0-9]{0,3}$/.test(retryAfter) ? Number(retryAfter) : 0;
    return seconds > 0
      ? "Too many AI explanation requests. Try again in " + seconds + " seconds."
      : "Too many AI explanation requests. Try again later.";
  }
  if (status === 503) return "AI explanation is not configured or is temporarily unavailable.";
  return "The AI explanation request failed (HTTP " + status + ").";
}

export async function explainAiSql(
  options: ExplainAiSqlOptions,
  fetcher: typeof fetch = fetch,
): Promise<AiSqlExplanationResult> {
  const sql = options.sql.trim();
  if (!sql) throw new Error("Enter a SQL statement before requesting an explanation.");
  if (sql.length > MAX_SQL_LENGTH) throw new Error("SQL must be 10,000 characters or fewer.");

  const headers = buildDurableSchemaHeaders(options.token, options.workspaceId);
  const response = await fetcher(options.apiBaseUrl.replace(/\/$/, "") + "/api/sql/ai/explain", {
    method: "POST",
    headers: {
      ...headers,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ sql }),
    cache: "no-store",
    credentials: "omit",
    signal: options.signal,
  });

  let payload: unknown = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  if (!response.ok) throw new Error(failureMessage(response.status, response.headers.get("Retry-After")));
  return parseAiSqlExplanationResponse(payload);
}

export function createAiSqlExplanationRequestGuard() {
  let generation = 0;
  return {
    begin(): number {
      generation += 1;
      return generation;
    },
    isCurrent(requestId: number): boolean {
      return requestId === generation;
    },
    invalidate(): void {
      generation += 1;
    },
  };
}
