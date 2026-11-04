export type DurableWorkspace = {
  workspaceId: string;
  name: string;
  slug: string;
  role: "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";
};

export type IssuedSession = { token: string; expiresAt: string };
export type SessionIdentity = { userId: string; sessionId: string; expiresAt: string };

const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ISO_INSTANT_PATTERN = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d+))?(Z|([+-])(\d{2}):(\d{2}))$/;
const WORKSPACE_ROLES = new Set(["OWNER", "ADMIN", "MEMBER", "VIEWER"]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isFutureInstant(value: unknown, nowMs: number): value is string {
  if (typeof value !== "string") return false;
  const match = ISO_INSTANT_PATTERN.exec(value);
  if (!match) return false;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  const daysInMonth = month === 2
    ? (year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28)
    : ([4, 6, 9, 11].includes(month) ? 30 : 31);

  if (
    month < 1 || month > 12 ||
    day < 1 || day > daysInMonth ||
    hour > 23 || minute > 59 || second > 59
  ) return false;

  if (match[8] !== "Z") {
    const offsetHours = Number(match[10]);
    const offsetMinutes = Number(match[11]);
    if (offsetHours > 23 || offsetMinutes > 59) return false;
  }

  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) && timestamp > nowMs;
}

export function isCanonicalWorkspaceId(value: unknown): value is string {
  return typeof value === "string" && UUID_PATTERN.test(value);
}

export function parseIssuedSession(payload: unknown, nowMs = Date.now()): IssuedSession {
  if (
    !isRecord(payload) ||
    typeof payload.token !== "string" ||
    !TOKEN_PATTERN.test(payload.token) ||
    !isFutureInstant(payload.expiresAt, nowMs)
  ) {
    throw new Error("The API returned an invalid session response.");
  }
  return { token: payload.token, expiresAt: payload.expiresAt };
}

export function parseSessionIdentity(payload: unknown, nowMs = Date.now()): SessionIdentity {
  if (
    !isRecord(payload) ||
    typeof payload.userId !== "string" ||
    !UUID_PATTERN.test(payload.userId) ||
    typeof payload.sessionId !== "string" ||
    !UUID_PATTERN.test(payload.sessionId) ||
    !isFutureInstant(payload.expiresAt, nowMs)
  ) {
    throw new Error("The API returned an invalid session identity.");
  }
  return { userId: payload.userId, sessionId: payload.sessionId, expiresAt: payload.expiresAt };
}

export function parseWorkspaceList(payload: unknown): DurableWorkspace[] {
  if (!Array.isArray(payload)) {
    throw new Error("The API returned an unexpected workspace list.");
  }

  const seen = new Set<string>();
  const workspaces: DurableWorkspace[] = [];
  for (const item of payload) {
    if (
      !isRecord(item) ||
      !isCanonicalWorkspaceId(item.workspaceId) ||
      typeof item.name !== "string" ||
      item.name.trim().length === 0 ||
      typeof item.slug !== "string" ||
      item.slug.trim().length === 0 ||
      typeof item.role !== "string" ||
      !WORKSPACE_ROLES.has(item.role)
    ) {
      throw new Error("The API returned an unexpected workspace list.");
    }

    const key = item.workspaceId.toLowerCase();
    if (seen.has(key)) {
      throw new Error("The API returned an unexpected workspace list.");
    }
    seen.add(key);
    workspaces.push({
      workspaceId: item.workspaceId,
      name: item.name,
      slug: item.slug,
      role: item.role as DurableWorkspace["role"],
    });
  }
  return workspaces;
}

export function buildDurableSchemaHeaders(token: string, workspaceId: string): Record<string, string> {
  if (!TOKEN_PATTERN.test(token)) {
    throw new Error("The session token is invalid.");
  }
  if (!isCanonicalWorkspaceId(workspaceId)) {
    throw new Error("Choose a valid workspace before browsing schema.");
  }
  return {
    Accept: "application/json",
    Authorization: `Bearer ${token}`,
    "X-NeuroSQL-Workspace-Id": workspaceId,
  };
}


/**
 * Tracks the newest async request so responses from an old workspace/session
 * cannot overwrite state after a newer request, sign-out, or expiry.
 */
export function createRequestGuard() {
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
