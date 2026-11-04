import { buildDurableSchemaHeaders } from "./durableAuth.ts";

export type AiSqlPerformanceResult = {
  recommendations: string;
  statementType: "SELECT" | "WITH";
  planTruncated: boolean;
  warnings: string[];
};

type RecommendAiSqlPerformanceOptions = {
  apiBaseUrl: string;
  sql: string;
  token: string;
  workspaceId: string;
  signal?: AbortSignal;
};

const MAX_SQL_LENGTH = 10_000;
const MAX_RECOMMENDATION_LENGTH = 4_000;
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

export function parseAiSqlPerformanceResponse(payload: unknown): AiSqlPerformanceResult {
  if (!isRecord(payload)
      || typeof payload.recommendations !== "string"
      || payload.recommendations.trim().length === 0
      || payload.recommendations.length > MAX_RECOMMENDATION_LENGTH
      || hasUnsafeControlCharacters(payload.recommendations)
      || (payload.statementType !== "SELECT" && payload.statementType !== "WITH")
      || typeof payload.planTruncated !== "boolean"
      || !Array.isArray(payload.warnings)
      || payload.warnings.length > MAX_WARNINGS
      || payload.warnings.some((warning) =>
        typeof warning !== "string"
        || warning.trim().length === 0
        || warning.length > MAX_WARNING_LENGTH
        || hasUnsafeControlCharacters(warning))) {
    throw new Error("The API returned an invalid AI performance recommendation.");
  }

  return {
    recommendations: payload.recommendations.trim(),
    statementType: payload.statementType,
    planTruncated: payload.planTruncated,
    warnings: payload.warnings.map((warning) => (warning as string).trim()),
  };
}

function failureMessage(status: number, retryAfter: string | null): string {
  if (status === 400) return "The AI performance request was invalid.";
  if (status === 401) return "Your sign-in is no longer valid. Sign in again.";
  if (status === 403) return "Your account cannot request performance recommendations in the selected workspace.";
  if (status === 404) return "AI performance recommendations are not enabled on the API.";
  if (status === 422) return "The SQL did not pass read-only validation or PostgreSQL could not produce a plan.";
  if (status === 429) {
    const seconds = retryAfter && /^[1-9][0-9]{0,3}$/.test(retryAfter) ? Number(retryAfter) : 0;
    return seconds > 0
      ? "Too many AI performance requests. Try again in " + seconds + " seconds."
      : "Too many AI performance requests. Try again later.";
  }
  if (status === 503) {
    return "Performance analysis is not configured or the restricted read-only database role is unavailable.";
  }
  return "The AI performance request failed (HTTP " + status + ").";
}

export async function recommendAiSqlPerformance(
  options: RecommendAiSqlPerformanceOptions,
  fetcher: typeof fetch = fetch,
): Promise<AiSqlPerformanceResult> {
  const sql = options.sql.trim();
  if (!sql) throw new Error("Enter a SQL statement before requesting performance recommendations.");
  if (sql.length > MAX_SQL_LENGTH) throw new Error("SQL must be 10,000 characters or fewer.");

  const headers = buildDurableSchemaHeaders(options.token, options.workspaceId);
  const response = await fetcher(options.apiBaseUrl.replace(/\/$/, "") + "/api/sql/ai/performance", {
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
  return parseAiSqlPerformanceResponse(payload);
}

export type AiSqlPerformanceRequestContext = Readonly<{
  apiBaseUrl: string;
  enabled: boolean;
  token: string | null;
  workspaceId: string;
  sql: string;
  busy: boolean;
}>;

export function isAiSqlPerformanceRequestContextCurrent(
  current: AiSqlPerformanceRequestContext,
  expected: AiSqlPerformanceRequestContext,
): boolean {
  return current.apiBaseUrl === expected.apiBaseUrl
    && current.enabled === expected.enabled
    && current.token === expected.token
    && current.workspaceId === expected.workspaceId
    && current.sql === expected.sql
    && current.busy === expected.busy;
}

/**
 * Owns one in-flight performance request for a panel instance.
 * Starting a replacement or invalidating the panel aborts the previous request.
 */
export function createAiSqlPerformanceRequestLifecycle() {
  let generation = 0;
  let activeController: AbortController | null = null;

  return {
    begin() {
      activeController?.abort();
      const controller = new AbortController();
      activeController = controller;
      const requestId = ++generation;

      return {
        signal: controller.signal,
        isCurrent(): boolean {
          return requestId === generation
            && activeController === controller
            && !controller.signal.aborted;
        },
        finish(): void {
          if (requestId === generation && activeController === controller) {
            activeController = null;
          }
        },
      };
    },
    invalidate(): void {
      generation += 1;
      activeController?.abort();
      activeController = null;
    },
  };
}
