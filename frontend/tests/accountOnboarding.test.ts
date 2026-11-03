import assert from "node:assert/strict";
import test from "node:test";
import {
  accountPasswordError,
  captureEmailVerificationToken,
  registerAccount,
  resendVerificationEmail,
  verifyEmail,
} from "../src/accountOnboarding.ts";
import {capturePasswordResetToken} from "../src/passwordRecovery.ts";

const token = "A".repeat(43);
const password = "a-secure-password-123";

test("captures only email-verification links and removes the one-time token", () => {
  let cleaned = "";
  const result = captureEmailVerificationToken(
    "https://neurosql.vercel.app/?campaign=welcome&flow=email-verification&token=" + token + "#verify",
    value => { cleaned = value; },
  );
  assert.deepEqual(result, {present:true,token});
  assert.equal(cleaned, "/?campaign=welcome#verify");
  assert.equal(cleaned.includes(token), false);
  assert.equal(cleaned.includes("flow="), false);
});

test("removes malformed or duplicate verification tokens without accepting them", () => {
  for (const query of [
    "flow=email-verification&token=" + token + "&token=" + token,
    "flow=email-verification&token=short",
    "flow=email-verification&token=" + "A".repeat(42) + "!",
    "flow=email-verification&token=" + token + "&flow=email-verification",
  ]) {
    let cleaned = "";
    const result = captureEmailVerificationToken("https://example.test/?" + query, value => { cleaned = value; });
    assert.deepEqual(result, {present:true,token:null});
    assert.equal(cleaned.includes("token="), false);
    assert.equal(cleaned.includes("flow="), false);
  }
});

test("does not consume password-reset links as email-verification links", () => {
  let called = false;
  const result = captureEmailVerificationToken(
    "https://example.test/?flow=password-reset&token=" + token,
    () => { called = true; },
  );
  assert.deepEqual(result, {present:false,token:null});
  assert.equal(called,false);
  let cleaned = "";
  const reset = capturePasswordResetToken(
    "https://example.test/?flow=password-reset&token=" + token,
    value => { cleaned = value; },
  );
  assert.deepEqual(reset,{present:true,token});
  assert.equal(cleaned,"/");
});

test("does not treat verification tokens as password-reset tokens", () => {
  let called = false;
  const result = capturePasswordResetToken(
    "https://example.test/?flow=email-verification&token=" + token,
    () => { called = true; },
  );
  assert.deepEqual(result,{present:false,token:null});
  assert.equal(called,false);
});

test("matches the backend BCrypt password policy", () => {
  assert.match(accountPasswordError("short") ?? "", /at least 12/);
  assert.match(accountPasswordError("é".repeat(37)) ?? "", /72 UTF-8 bytes/);
  assert.match(accountPasswordError("valid-password\n123") ?? "", /control characters/);
  assert.equal(accountPasswordError("😀".repeat(12)),null);
});

test("registration normalizes the email/name and sends a no-store credential-free POST", async () => {
  let url = "";
  let init: RequestInit|undefined;
  const fakeFetch: typeof fetch = async (input,options) => {
    url = String(input); init = options;
    return new Response(JSON.stringify({message:"generic"}),{status:202});
  };
  await registerAccount("https://api.example.test/",{displayName:"  Alice Smith  ",email:"  ALICE@EXAMPLE.COM ",password},fakeFetch);
  assert.equal(url,"https://api.example.test/api/auth/register");
  assert.equal(init?.method,"POST");
  assert.equal(init?.cache,"no-store");
  assert.equal(init?.credentials,"omit");
  assert.deepEqual(JSON.parse(String(init?.body)),{displayName:"Alice Smith",email:"alice@example.com",password});
  assert.equal((init?.headers as Record<string,string>).Authorization,undefined);
});

test("registration rejects invalid inputs before making a request", async () => {
  let calls = 0;
  const fakeFetch: typeof fetch = async () => { calls += 1; return new Response(null,{status:202}); };
  await assert.rejects(()=>registerAccount("https://api.example.test",{displayName:"Alice",email:"bad",password},fakeFetch),/valid email/);
  await assert.rejects(()=>registerAccount("https://api.example.test",{displayName:" ",email:"alice@example.com",password},fakeFetch),/display name/);
  await assert.rejects(()=>registerAccount("https://api.example.test",{displayName:"Alice",email:"alice@example.com",password:"short"},fakeFetch),/at least 12/);
  assert.equal(calls,0);
});

test("registration maps validation, throttling and provider failures to safe messages", async () => {
  const response = (status:number): typeof fetch => async () => new Response("private details",{status});
  await assert.rejects(()=>registerAccount("https://api.example.test",{displayName:"Alice",email:"alice@example.com",password},response(400)),/Registration details are invalid/);
  await assert.rejects(()=>registerAccount("https://api.example.test",{displayName:"Alice",email:"alice@example.com",password},response(429)),/Too many requests/);
  await assert.rejects(()=>registerAccount("https://api.example.test",{displayName:"Alice",email:"alice@example.com",password},response(503)),/temporarily unavailable/);
});

test("verification resend normalizes email and uses the same generic accepted contract", async () => {
  let url = "";
  let init: RequestInit|undefined;
  const fakeFetch: typeof fetch = async (input,options) => {
    url = String(input); init = options;
    return new Response(JSON.stringify({message:"If the address can receive verification email, instructions will be sent."}),{status:202});
  };
  await resendVerificationEmail("https://api.example.test"," Alice@Example.com ",fakeFetch);
  assert.equal(url,"https://api.example.test/api/auth/verification/resend");
  assert.equal(init?.cache,"no-store");
  assert.equal(init?.credentials,"omit");
  assert.deepEqual(JSON.parse(String(init?.body)),{email:"alice@example.com"});
});

test("verification resend hides account details and maps throttling safely", async () => {
  const throttled: typeof fetch = async () => new Response("private",{status:429});
  await assert.rejects(()=>resendVerificationEmail("https://api.example.test","alice@example.com",throttled),/Too many requests/);
  const unavailable: typeof fetch = async () => new Response("private",{status:503});
  await assert.rejects(()=>resendVerificationEmail("https://api.example.test","alice@example.com",unavailable),/temporarily unavailable/);
});

test("verification posts the token only in a no-store credential-free request body", async () => {
  let url = "";
  let init: RequestInit|undefined;
  const fakeFetch: typeof fetch = async (input,options) => {
    url = String(input); init = options;
    return new Response(JSON.stringify({message:"verified"}),{status:200});
  };
  await verifyEmail("https://api.example.test",token,fakeFetch);
  assert.equal(url,"https://api.example.test/api/auth/verify-email");
  assert.equal(url.includes(token),false);
  assert.equal(init?.method,"POST");
  assert.equal(init?.cache,"no-store");
  assert.equal(init?.credentials,"omit");
  assert.deepEqual(JSON.parse(String(init?.body)),{token});
});

test("verification rejects malformed tokens before network access", async () => {
  let calls = 0;
  const fakeFetch: typeof fetch = async () => { calls += 1; return new Response(null,{status:200}); };
  await assert.rejects(()=>verifyEmail("https://api.example.test","invalid",fakeFetch),/invalid or has expired/);
  assert.equal(calls,0);
});

test("verification maps invalid, throttled and unavailable outcomes generically", async () => {
  const invalid: typeof fetch = async () => new Response("account state details",{status:400});
  await assert.rejects(()=>verifyEmail("https://api.example.test",token,invalid),/invalid or has expired/);
  const throttled: typeof fetch = async () => new Response("private",{status:429});
  await assert.rejects(()=>verifyEmail("https://api.example.test",token,throttled),/Too many requests/);
  const unavailable: typeof fetch = async () => new Response("stack trace",{status:503});
  await assert.rejects(()=>verifyEmail("https://api.example.test",token,unavailable),/temporarily unavailable/);
});
