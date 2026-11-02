export type QueryHistoryOutcome = "SUCCEEDED" | "FAILED" | "CANCELED";
export type QueryHistoryOutcomeFilter = "ALL" | QueryHistoryOutcome;

export type QueryHistoryEntry = {
  historyId: string;
  queryText: string;
  outcome: QueryHistoryOutcome;
  rowCount: number | null;
  executionTimeMillis: number;
  truncated: boolean;
  createdAt: string;
};

export type QueryHistoryPage = {
  items: QueryHistoryEntry[];
  nextCursor: string | null;
  hasMore: boolean;
};

type FetchHistoryOptions = {
  apiBaseUrl: string;
  token: string;
  workspaceId: string;
  cursor?: string | null;
  outcome?: QueryHistoryOutcomeFilter;
  fromDate?: string;
  toDate?: string;
  query?: string;
  signal?: AbortSignal;
};

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const BEARER = /^[A-Za-z0-9_-]{43}$/;
const OUTCOME_FILTERS: readonly string[] = ["ALL", "SUCCEEDED", "FAILED", "CANCELED"];
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;
function isValidIsoDate(value: string): boolean {
  if (!ISO_DATE.test(value)) return false;
  const parsed = new Date(value + "T00:00:00.000Z");
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}

export function parseQueryHistoryPage(payload: unknown): QueryHistoryPage {
  if (!isRecord(payload) || !Array.isArray(payload.items) || payload.items.length > 100) {
    throw new Error("The API returned an invalid query history page.");
  }
  if (
    typeof payload.hasMore !== "boolean" ||
    (payload.nextCursor !== null && typeof payload.nextCursor !== "string") ||
    (typeof payload.nextCursor === "string" &&
      (payload.nextCursor.length < 1 || payload.nextCursor.length > 512 || !/^[A-Za-z0-9_-]+$/.test(payload.nextCursor))) ||
    payload.hasMore !== (payload.nextCursor !== null)
  ) {
    throw new Error("The API returned invalid query history pagination metadata.");
  }

  const seen = new Set<string>();
  const items: QueryHistoryEntry[] = payload.items.map((item) => {
    if (
      !isRecord(item) ||
      typeof item.historyId !== "string" ||
      !UUID.test(item.historyId) ||
      seen.has(item.historyId.toLowerCase()) ||
      typeof item.queryText !== "string" ||
      item.queryText.length < 1 ||
      item.queryText.length > 50_000 ||
      (item.outcome !== "SUCCEEDED" && item.outcome !== "FAILED" && item.outcome !== "CANCELED") ||
      (item.rowCount !== null && (!Number.isSafeInteger(item.rowCount) || (item.rowCount as number) < 0)) ||
      !Number.isSafeInteger(item.executionTimeMillis) ||
      (item.executionTimeMillis as number) < 0 ||
      (item.executionTimeMillis as number) > 30_000 ||
      typeof item.truncated !== "boolean" ||
      typeof item.createdAt !== "string" ||
      !Number.isFinite(Date.parse(item.createdAt))
    ) {
      throw new Error("The API returned an invalid query history entry.");
    }
    seen.add(item.historyId.toLowerCase());
    return {
      historyId: item.historyId,
      queryText: item.queryText,
      outcome: item.outcome,
      rowCount: item.rowCount as number | null,
      executionTimeMillis: item.executionTimeMillis as number,
      truncated: item.truncated,
      createdAt: item.createdAt,
    };
  });

  return {
    items,
    nextCursor: payload.nextCursor as string | null,
    hasMore: payload.hasMore,
  };
}

function failureMessage(status: number): string {
  if (status === 400) return "The history page, SQL search or date filter is invalid. Refresh the history and try again.";
  if (status === 401) return "Your sign-in is no longer valid. Sign in again to view query history.";
  if (status === 403) return "Your account cannot view history in the selected workspace.";
  if (status === 404) return "Query history is not enabled on the API.";
  if (status === 429) return "Too many history requests. Try again later.";
  if (status >= 500) return "Query history is temporarily unavailable.";
  return "The history request failed (HTTP " + status + ").";
}

export async function fetchQueryHistoryPage(
  options: FetchHistoryOptions,
  fetcher: typeof fetch = fetch,
): Promise<QueryHistoryPage> {
  if (!BEARER.test(options.token)) throw new Error("Sign in with a valid NeuroSQL session to view history.");
  if (!UUID.test(options.workspaceId)) throw new Error("Choose a valid workspace before viewing history.");
  if (options.cursor !== undefined && options.cursor !== null &&
      (options.cursor.length < 1 || options.cursor.length > 512 || !/^[A-Za-z0-9_-]+$/.test(options.cursor))) {
    throw new Error("The history cursor is invalid. Refresh the history and try again.");
  }

  const outcome = options.outcome ?? "ALL";
  if (!OUTCOME_FILTERS.includes(outcome)) {
    throw new Error("Choose a valid query-history outcome filter.");
  }
  const fromDate = options.fromDate?.trim() ?? "";
  const toDate = options.toDate?.trim() ?? "";
  if ((fromDate && !isValidIsoDate(fromDate)) || (toDate && !isValidIsoDate(toDate))) {
    throw new Error("Choose valid UTC calendar dates for query history.");
  }
  if (fromDate && toDate && fromDate > toDate) {
    throw new Error("The history start date must not be after its end date.");
  }
  const query = options.query?.trim() ?? "";
  if (Array.from(query).length > 120 || /[\u0000-\u001F\u007F-\u009F]/u.test(query)) {
    throw new Error("Use at most 120 printable characters when searching SQL history.");
  }

  const url = new URL("/api/sql/history", options.apiBaseUrl.replace(/\/$/, "") + "/");
  url.searchParams.set("limit", "25");
  if (options.cursor) url.searchParams.set("cursor", options.cursor);
  if (outcome !== "ALL") url.searchParams.set("outcome", outcome);
  if (fromDate) url.searchParams.set("from", fromDate);
  if (toDate) url.searchParams.set("to", toDate);
  if (query) url.searchParams.set("q", query);

  const response = await fetcher(url.toString(), {
    method: "GET",
    headers: {
      Accept: "application/json",
      Authorization: "Bearer " + options.token,
      "X-NeuroSQL-Workspace-Id": options.workspaceId,
    },
    cache: "no-store",
    signal: options.signal,
  });

  if (!response.ok) throw new Error(failureMessage(response.status));
  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new Error("The API returned an unreadable query history response.");
  }
  return parseQueryHistoryPage(payload);
}
