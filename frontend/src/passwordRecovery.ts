const RESET_TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;

export type CapturedPasswordResetToken = {
  present: boolean;
  token: string | null;
};

/**
 * Captures the one-time token from the current URL and immediately removes it
 * from the visible address/history entry. The caller should keep the returned
 * token only in component memory and never persist it.
 */
export function capturePasswordResetToken(
  href: string,
  replaceUrl: (cleanUrl: string) => void,
): CapturedPasswordResetToken {
  const url = new URL(href);
  const values = url.searchParams.getAll("token");
  if (values.length === 0) {
    return { present: false, token: null };
  }

  url.searchParams.delete("token");
  replaceUrl(`${url.pathname}${url.search}${url.hash}`);

  const candidate = values.length === 1 ? values[0] : null;
  return {
    present: true,
    token: candidate && RESET_TOKEN_PATTERN.test(candidate) ? candidate : null,
  };
}

export function passwordResetPasswordError(password: string): string | null {
  if (Array.from(password).length < 12) {
    return "Use at least 12 characters for your new password.";
  }
  if (new TextEncoder().encode(password).length > 72) {
    return "Your new password is too long. Use no more than 72 UTF-8 bytes.";
  }
  for (const character of password) {
    const codePoint = character.codePointAt(0) ?? 0;
    if (codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)) {
      return "Your new password cannot contain control characters.";
    }
  }
  return null;
}

function apiUrl(apiBaseUrl: string, path: string): string {
  return apiBaseUrl.replace(/\\/$/, "") + path;
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

export async function requestPasswordReset(
  apiBaseUrl: string,
  email: string,
  fetcher: typeof fetch = fetch,
): Promise<void> {
  const normalizedEmail = email.trim().toLowerCase();
  if (!normalizedEmail || normalizedEmail.length > 254 || !/^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$/.test(normalizedEmail)) {
    throw new Error("Enter a valid email address.");
  }

  let response: Response;
  try {
    response = await fetcher(
      apiUrl(apiBaseUrl, "/api/auth/password-reset/request"),
      requestOptions({ email: normalizedEmail }),
    );
  } catch {
    throw new Error("Password reset is temporarily unavailable. Try again later.");
  }

  if (response.status === 202) return;
  if (response.status === 429) {
    throw new Error("Too many requests. Try again later.");
  }
  if (response.status === 503) {
    throw new Error("Password reset is temporarily unavailable. Try again later.");
  }
  throw new Error("Unable to request a password reset. Try again later.");
}

export async function confirmPasswordReset(
  apiBaseUrl: string,
  token: string,
  newPassword: string,
  fetcher: typeof fetch = fetch,
): Promise<void> {
  if (!RESET_TOKEN_PATTERN.test(token)) {
    throw new Error("This password-reset link is invalid or expired. Request a new link.");
  }
  const passwordError = passwordResetPasswordError(newPassword);
  if (passwordError) throw new Error(passwordError);

  let response: Response;
  try {
    response = await fetcher(
      apiUrl(apiBaseUrl, "/api/auth/password-reset/confirm"),
      requestOptions({ token, newPassword }),
    );
  } catch {
    throw new Error("Password reset is temporarily unavailable. Try again later.");
  }

  if (response.status === 200) return;
  if (response.status === 400) {
    throw new Error("This password-reset link is invalid or expired, or the new password was not accepted.");
  }
  if (response.status === 429) {
    throw new Error("Too many requests. Try again later.");
  }
  throw new Error("Password reset is temporarily unavailable. Try again later.");
}
