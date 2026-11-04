const ACTION_TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export type CapturedEmailVerificationToken = {
  present: boolean;
  token: string | null;
};

export function captureEmailVerificationToken(
  href: string,
  replaceUrl: (cleanUrl: string) => void,
): CapturedEmailVerificationToken {
  const url = new URL(href);
  const flows = url.searchParams.getAll("flow");
  if (!flows.includes("email-verification")) {
    return { present: false, token: null };
  }

  const values = url.searchParams.getAll("token");
  url.searchParams.delete("flow");
  url.searchParams.delete("token");
  replaceUrl(`${url.pathname}${url.search}${url.hash}`);

  const candidate = flows.length === 1 && values.length === 1 ? values[0] : null;
  return {
    present: true,
    token: candidate && ACTION_TOKEN_PATTERN.test(candidate) ? candidate : null,
  };
}

export function accountPasswordError(password: string, label = "password"): string | null {
  if (Array.from(password).length < 12) {
    return `Use at least 12 characters for your ${label}.`;
  }
  if (new TextEncoder().encode(password).length > 72) {
    return `Your ${label} is too long. Use no more than 72 UTF-8 bytes.`;
  }
  for (const character of password) {
    const codePoint = character.codePointAt(0) ?? 0;
    if (codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)) {
      return `Your ${label} cannot contain control characters.`;
    }
  }
  return null;
}

function apiUrl(apiBaseUrl: string, path: string): string {
  return apiBaseUrl.replace(/\/$/, "") + path;
}

function requestOptions(body: Record<string, string>): RequestInit {
  return {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
    cache: "no-store",
    credentials: "omit",
  };
}

function normalizeEmail(email: string): string {
  const normalized = email.trim().toLowerCase();
  if (!normalized || normalized.length > 254 || !EMAIL_PATTERN.test(normalized)) {
    throw new Error("Enter a valid email address.");
  }
  return normalized;
}

async function post(
  apiBaseUrl: string,
  path: string,
  body: Record<string, string>,
  fetcher: typeof fetch,
): Promise<Response> {
  try {
    return await fetcher(apiUrl(apiBaseUrl, path), requestOptions(body));
  } catch {
    throw new Error("Account services are temporarily unavailable. Try again later.");
  }
}

function acceptedOrThrow(response: Response, action: "register" | "resend"): void {
  if (response.status === 202) return;
  if (response.status === 429) throw new Error("Too many requests. Try again later.");
  if (response.status === 400) {
    throw new Error(action === "register"
      ? "Registration details are invalid. Check the fields and try again."
      : "Enter a valid email address.");
  }
  throw new Error("Account services are temporarily unavailable. Try again later.");
}

export async function registerAccount(
  apiBaseUrl: string,
  input: { displayName: string; email: string; password: string },
  fetcher: typeof fetch = fetch,
): Promise<void> {
  const email = normalizeEmail(input.email);
  const displayName = input.displayName.trim();
  const displayNameHasControl = Array.from(displayName).some(character => {
    const codePoint = character.codePointAt(0) ?? 0;
    return codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f);
  });
  if (!displayName || Array.from(displayName).length > 120 || displayNameHasControl) {
    throw new Error("Enter a display name of 1 to 120 characters without control characters.");
  }
  const passwordError = accountPasswordError(input.password);
  if (passwordError) throw new Error(passwordError);

  const response = await post(apiBaseUrl, "/api/auth/register", {
    displayName,
    email,
    password: input.password,
  }, fetcher);
  acceptedOrThrow(response, "register");
}

export async function resendVerificationEmail(
  apiBaseUrl: string,
  emailInput: string,
  fetcher: typeof fetch = fetch,
): Promise<void> {
  const email = normalizeEmail(emailInput);
  const response = await post(apiBaseUrl, "/api/auth/verification/resend", { email }, fetcher);
  acceptedOrThrow(response, "resend");
}

export async function verifyEmail(
  apiBaseUrl: string,
  token: string,
  fetcher: typeof fetch = fetch,
): Promise<void> {
  if (!ACTION_TOKEN_PATTERN.test(token)) {
    throw new Error("This verification link is invalid or has expired. Request a new verification email.");
  }
  const response = await post(apiBaseUrl, "/api/auth/verify-email", { token }, fetcher);
  if (response.status === 200) return;
  if (response.status === 400) {
    throw new Error("This verification link is invalid or has expired. Request a new verification email.");
  }
  if (response.status === 429) throw new Error("Too many requests. Try again later.");
  throw new Error("Email verification is temporarily unavailable. Try again later.");
}
