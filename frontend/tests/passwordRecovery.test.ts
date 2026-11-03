import assert from "node:assert/strict";
import test from "node:test";
import {
  capturePasswordResetToken,
  confirmPasswordReset,
  passwordResetPasswordError,
  requestPasswordReset,
} from "../src/passwordRecovery.ts";

const token = "A".repeat(43);
const password = "a-secure-password-123";

test("captures one canonical token and removes it from the address bar", () => {
  let cleaned = "";
  const result = capturePasswordResetToken(
    "https://neurosql.vercel.app/?campaign=welcome&token=" + token + "#section",
    value => { cleaned = value; },
  );
  assert.deepEqual(result, { present: true, token });
  assert.equal(cleaned, "/?campaign=welcome#section");
  assert.equal(cleaned.includes(token), false);
});

test("rejects duplicate and malformed tokens while still removing them from the URL", () => {
  for (const query of [
    "token=" + token + "&token=" + token,
    "token=short",
    "token=" + "A".repeat(42) + "!",
  ]) {
    let cleaned = "";
    const result = capturePasswordResetToken("https://example.test/reset?" + query, value => { cleaned = value; });
    assert.deepEqual(result, { present: true, token: null });
    assert.equal(cleaned.includes("token="), false);
  }
});

test("does not change a URL without a reset token", () => {
  let called = false;
  const result = capturePasswordResetToken("https://example.test/?source=mail", () => { called = true; });
  assert.deepEqual(result, { present: false, token: null });
  assert.equal(called, false);
});

test("enforces the backend password policy without trimming or normalizing", () => {
  assert.equal(passwordResetPasswordError("short"), "Use at least 12 characters for your new password.");
  assert.equal(passwordResetPasswordError("é".repeat(37)), "Your new password is too long. Use no more than 72 UTF-8 bytes.");
  assert.equal(passwordResetPasswordError("valid-password-123"), null);
  assert.match(passwordResetPasswordError("valid-password\n123") ?? "", /control characters/);
  assert.equal(passwordResetPasswordError("😀".repeat(12)), null);
});

test("password-reset request normalizes email and uses a non-cacheable credential-free POST", async () => {
  let capturedUrl = "";
  let capturedInit: RequestInit | undefined;
  const fakeFetch: typeof fetch = async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return new Response(JSON.stringify({ message: "generic" }), { status: 202 });
  };
  await requestPasswordReset("https://api.example.test/", "  ALICE@EXAMPLE.COM ", fakeFetch);
  assert.equal(capturedUrl, "https://api.example.test/api/auth/password-reset/request");
  assert.equal(capturedInit?.method, "POST");
  assert.equal(capturedInit?.cache, "no-store");
  assert.equal(capturedInit?.credentials, "omit");
  assert.deepEqual(JSON.parse(String(capturedInit?.body)), { email: "alice@example.com" });
  assert.equal((capturedInit?.headers as Record<string,string>).Authorization, undefined);
});

test("password-reset request hides server account details and maps throttling safely", async () => {
  const genericFetch: typeof fetch = async () => new Response(
    JSON.stringify({ message: "If an account exists for that address, a password-reset link will be sent." }),
    { status: 202 },
  );
  await assert.doesNotReject(() => requestPasswordReset("https://api.example.test", "known@example.com", genericFetch));
  const throttled: typeof fetch = async () => new Response("private", { status: 429 });
  await assert.rejects(() => requestPasswordReset("https://api.example.test", "known@example.com", throttled), /Too many requests/);
  const unavailable: typeof fetch = async () => new Response("internal database details", { status: 503 });
  await assert.rejects(() => requestPasswordReset("https://api.example.test", "known@example.com", unavailable), /temporarily unavailable/);
  await assert.rejects(() => requestPasswordReset("https://api.example.test", "not-an-email", genericFetch), /valid email/);
});

test("password-reset confirmation validates locally and posts the token only in the body", async () => {
  let capturedUrl = "";
  let capturedInit: RequestInit | undefined;
  const fakeFetch: typeof fetch = async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return new Response(JSON.stringify({ message: "reset" }), { status: 200 });
  };
  await confirmPasswordReset("https://api.example.test", token, password, fakeFetch);
  assert.equal(capturedUrl, "https://api.example.test/api/auth/password-reset/confirm");
  assert.equal(capturedInit?.cache, "no-store");
  assert.equal(capturedInit?.credentials, "omit");
  assert.deepEqual(JSON.parse(String(capturedInit?.body)), { token, newPassword: password });
  assert.equal(capturedUrl.includes(token), false);
});

test("password-reset confirmation does not send invalid tokens or weak passwords", async () => {
  let calls = 0;
  const fakeFetch: typeof fetch = async () => {
    calls += 1;
    return new Response(null, { status: 200 });
  };
  await assert.rejects(() => confirmPasswordReset("https://api.example.test", "invalid", password, fakeFetch), /invalid or expired/);
  await assert.rejects(() => confirmPasswordReset("https://api.example.test", token, "short", fakeFetch), /at least 12/);
  await assert.rejects(() => confirmPasswordReset("https://api.example.test", token, "x".repeat(73), fakeFetch), /72 UTF-8 bytes/);
  assert.equal(calls, 0);
});

test("password-reset confirmation maps invalid tokens and service failures to generic messages", async () => {
  const invalid: typeof fetch = async () => new Response("database constraint details", { status: 400 });
  await assert.rejects(() => confirmPasswordReset("https://api.example.test", token, password, invalid), /invalid or expired/);
  const unavailable: typeof fetch = async () => new Response("stack trace", { status: 503 });
  await assert.rejects(() => confirmPasswordReset("https://api.example.test", token, password, unavailable), /temporarily unavailable/);
});
