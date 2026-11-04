export type SavedQuery = {
  savedQueryId: string;
  workspaceId: string;
  ownerUserId: string;
  name: string;
  description: string;
  queryText: string;
  createdAt: string;
  updatedAt: string;
};

export type SavedQueryPage = {
  items: SavedQuery[];
  nextCursor: string | null;
  hasMore: boolean;
};

export type SavedQueryDraft = {
  name: string;
  description: string;
  queryText: string;
};

type RequestOptions = {
  apiBaseUrl: string;
  token: string;
  workspaceId: string;
  signal?: AbortSignal;
};

type PageOptions = RequestOptions & { cursor?: string | null; search?: string | null };

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const BEARER = /^[A-Za-z0-9_-]{43}$/;
const CURSOR = /^[A-Za-z0-9_-]{1,512}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function codePointLength(value: string): number {
  return Array.from(value).length;
}

function validateContext(options: RequestOptions): void {
  if (!BEARER.test(options.token)) {
    throw new Error("Sign in with a valid NeuroSQL session to access saved queries.");
  }
  if (!UUID.test(options.workspaceId)) {
    throw new Error("Choose a valid workspace before accessing saved queries.");
  }
}

function validateDraft(draft: SavedQueryDraft): SavedQueryDraft {
  const name = draft.name.trim();
  const description = draft.description.trim();
  const queryText = draft.queryText.trim();

  if (
    codePointLength(name) < 1 ||
    codePointLength(name) > 120 ||
    /[\u0000-\u001f\u007f-\u009f]/u.test(name)
  ) {
    throw new Error("The saved-query name must contain 1 to 120 printable characters.");
  }
  if (codePointLength(description) > 500) {
    throw new Error("The saved-query description must be 500 characters or fewer.");
  }
  if (codePointLength(queryText) < 1 || codePointLength(queryText) > 50_000) {
    throw new Error("Saved SQL must contain between 1 and 50000 characters.");
  }
  return { name, description, queryText };
}

function parseSavedQuery(payload: unknown, workspaceId: string): SavedQuery {
  if (
    !isRecord(payload) ||
    typeof payload.savedQueryId !== "string" ||
    !UUID.test(payload.savedQueryId) ||
    typeof payload.workspaceId !== "string" ||
    payload.workspaceId.toLowerCase() !== workspaceId.toLowerCase() ||
    typeof payload.ownerUserId !== "string" ||
    !UUID.test(payload.ownerUserId) ||
    typeof payload.name !== "string" ||
    payload.name.trim().length < 1 ||
    codePointLength(payload.name) > 120 ||
    typeof payload.description !== "string" ||
    codePointLength(payload.description) > 500 ||
    typeof payload.queryText !== "string" ||
    payload.queryText.trim().length < 1 ||
    codePointLength(payload.queryText) > 50_000 ||
    typeof payload.createdAt !== "string" ||
    !Number.isFinite(Date.parse(payload.createdAt)) ||
    typeof payload.updatedAt !== "string" ||
    !Number.isFinite(Date.parse(payload.updatedAt))
  ) {
    throw new Error("The API returned an invalid saved-query record.");
  }

  return {
    savedQueryId: payload.savedQueryId,
    workspaceId: payload.workspaceId,
    ownerUserId: payload.ownerUserId,
    name: payload.name,
    description: payload.description,
    queryText: payload.queryText,
    createdAt: payload.createdAt,
    updatedAt: payload.updatedAt,
  };
}

export function parseSavedQueryPage(payload: unknown, workspaceId: string): SavedQueryPage {
  if (!isRecord(payload) || !Array.isArray(payload.items) || payload.items.length > 100) {
    throw new Error("The API returned an invalid saved-query page.");
  }
  if (
    typeof payload.hasMore !== "boolean" ||
    (payload.nextCursor !== null && typeof payload.nextCursor !== "string") ||
    (typeof payload.nextCursor === "string" && !CURSOR.test(payload.nextCursor)) ||
    payload.hasMore !== (payload.nextCursor !== null)
  ) {
    throw new Error("The API returned invalid saved-query pagination metadata.");
  }

  const seen = new Set<string>();
  const items = payload.items.map((item) => {
    const parsed = parseSavedQuery(item, workspaceId);
    const key = parsed.savedQueryId.toLowerCase();
    if (seen.has(key)) {
      throw new Error("The API returned duplicate saved-query records.");
    }
    seen.add(key);
    return parsed;
  });

  return {
    items,
    nextCursor: payload.nextCursor as string | null,
    hasMore: payload.hasMore,
  };
}

function failureMessage(status: number): string {
  if (status === 400) return "The saved-query details, search, or page request are invalid.";
  if (status === 401) return "Your sign-in is no longer valid. Sign in again to access saved queries.";
  if (status === 403) return "Your account cannot perform this action in the selected workspace.";
  if (status === 404) return "Saved Queries is not enabled or the requested query was not found.";
  if (status === 409) return "A saved query with this name already exists in the workspace.";
  if (status === 429) return "Too many saved-query requests. Try again later.";
  if (status >= 500) return "Saved Queries is temporarily unavailable.";
  return "The saved-query request failed (HTTP " + status + ").";
}

async function requestJson(
  options: RequestOptions,
  method: "POST" | "PUT" | "DELETE",
  savedQueryId: string | null,
  draft: SavedQueryDraft | null,
  fetcher: typeof fetch,
): Promise<unknown> {
  validateContext(options);
  if (savedQueryId !== null && !UUID.test(savedQueryId)) {
    throw new Error("The saved-query identifier is invalid.");
  }

  const base = options.apiBaseUrl.replace(/\/+$/, "");
  const path = "/api/sql/saved-queries" + (savedQueryId ? "/" + encodeURIComponent(savedQueryId) : "");
  const headers: Record<string, string> = {
    Accept: "application/json",
    Authorization: "Bearer " + options.token,
    "X-NeuroSQL-Workspace-Id": options.workspaceId,
  };
  const init: RequestInit = {
    method,
    headers,
    cache: "no-store",
    signal: options.signal,
  };

  if (draft) {
    headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(validateDraft(draft));
  }

  const response = await fetcher(base + path, init);
  if (!response.ok) throw new Error(failureMessage(response.status));
  if (response.status === 204) return null;

  try {
    return await response.json();
  } catch {
    throw new Error("The API returned an unreadable saved-query response.");
  }
}

export async function fetchSavedQueriesPage(
  options: PageOptions,
  fetcher: typeof fetch = fetch,
): Promise<SavedQueryPage> {
  validateContext(options);
  if (options.cursor !== undefined && options.cursor !== null && !CURSOR.test(options.cursor)) {
    throw new Error("The saved-query cursor is invalid. Refresh the list and try again.");
  }
  const search = (options.search ?? "").trim();
  if (
    codePointLength(search) > 120 ||
    /[\u0000-\u001f\u007f-\u009f]/u.test(search)
  ) {
    throw new Error("Saved-query search must contain at most 120 printable characters.");
  }

  const url = new URL("/api/sql/saved-queries", options.apiBaseUrl.replace(/\/+$/, "") + "/");
  url.searchParams.set("limit", "25");
  if (options.cursor) url.searchParams.set("cursor", options.cursor);
  if (search) url.searchParams.set("q", search);

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
    throw new Error("The API returned an unreadable saved-query page.");
  }
  return parseSavedQueryPage(payload, options.workspaceId);
}

export async function createSavedQuery(
  options: RequestOptions,
  draft: SavedQueryDraft,
  fetcher: typeof fetch = fetch,
): Promise<SavedQuery> {
  const payload = await requestJson(options, "POST", null, draft, fetcher);
  return parseSavedQuery(payload, options.workspaceId);
}

export async function updateSavedQuery(
  options: RequestOptions,
  savedQueryId: string,
  draft: SavedQueryDraft,
  fetcher: typeof fetch = fetch,
): Promise<SavedQuery> {
  const payload = await requestJson(options, "PUT", savedQueryId, draft, fetcher);
  return parseSavedQuery(payload, options.workspaceId);
}

export async function deleteSavedQuery(
  options: RequestOptions,
  savedQueryId: string,
  fetcher: typeof fetch = fetch,
): Promise<void> {
  await requestJson(options, "DELETE", savedQueryId, null, fetcher);
}
