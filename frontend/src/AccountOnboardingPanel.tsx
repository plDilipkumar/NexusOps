import {useState,type FormEvent} from "react";
import {ArrowLeft,ArrowRight,CheckCircle2,MailCheck,UserPlus} from "lucide-react";
import {
  accountPasswordError,
  registerAccount,
  resendVerificationEmail,
  verifyEmail,
} from "./accountOnboarding.ts";

type Mode = "register" | "verify" | "resend";

type Props = {
  apiBaseUrl: string;
  initialMode: Mode;
  initialToken: string | null;
  onBack: () => void;
};

const GENERIC_ACCEPTED = "If the address can receive verification email, instructions will be sent.";

export function AccountOnboardingPanel({apiBaseUrl,initialMode,initialToken,onBack}:Props) {
  const [mode,setMode] = useState<Mode>(initialMode);
  const [token,setToken] = useState<string|null>(initialToken);
  const [displayName,setDisplayName] = useState("");
  const [email,setEmail] = useState("");
  const [password,setPassword] = useState("");
  const [confirmation,setConfirmation] = useState("");
  const [busy,setBusy] = useState(false);
  const [accepted,setAccepted] = useState(false);
  const [verified,setVerified] = useState(false);
  const [error,setError] = useState("");

  const submitRegistration = async(event:FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    if(password!==confirmation) {
      setError("The passwords do not match.");
      return;
    }
    const passwordError=accountPasswordError(password);
    if(passwordError) {
      setError(passwordError);
      return;
    }
    setBusy(true);
    try {
      await registerAccount(apiBaseUrl,{displayName,email,password});
      setEmail(email.trim().toLowerCase());
      setPassword("");
      setConfirmation("");
      setAccepted(true);
    } catch(problem) {
      setError(problem instanceof Error?problem.message:"Account creation is temporarily unavailable. Try again later.");
    } finally {
      setBusy(false);
    }
  };

  const submitResend = async(event:FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      await resendVerificationEmail(apiBaseUrl,email);
      setAccepted(true);
    } catch(problem) {
      setError(problem instanceof Error?problem.message:"Account services are temporarily unavailable. Try again later.");
    } finally {
      setBusy(false);
    }
  };

  const submitVerification = async() => {
    if(!token) {
      setError("This verification link is invalid or has expired. Request a new verification email.");
      return;
    }
    setError("");
    setBusy(true);
    try {
      await verifyEmail(apiBaseUrl,token);
      setVerified(true);
      setToken(null);
    } catch(problem) {
      setError(problem instanceof Error?problem.message:"Email verification is temporarily unavailable. Try again later.");
    } finally {
      setBusy(false);
    }
  };

  const openResend = () => {
    setMode("resend");
    setAccepted(false);
    setError("");
  };

  return <section className="schema-login" aria-label="Account onboarding">
    <button type="button" className="secondary" onClick={onBack} disabled={busy}><ArrowLeft size={14}/> Back to sign in</button>
    {mode==="register" ? (
      <>
        <div className="schema-login-icon"><UserPlus size={19}/></div>
        <h3>Create your NeuroSQL account</h3>
        <p>Register with your email address. You must verify it before signing in.</p>
        {accepted ? (
          <>
            <div className="notice" role="status">{GENERIC_ACCEPTED}</div>
            <button type="button" className="secondary" onClick={openResend}>Resend verification email</button>
          </>
        ) : (
          <form onSubmit={submitRegistration} style={{display:"grid",gap:"12px"}}>
            <label>Display name<input autoComplete="name" value={displayName} onChange={event=>setDisplayName(event.target.value)} maxLength={120} required /></label>
            <label>Email<input type="email" autoComplete="email" value={email} onChange={event=>setEmail(event.target.value)} maxLength={254} required /></label>
            <label>Password<input type="password" autoComplete="new-password" value={password} onChange={event=>setPassword(event.target.value)} maxLength={256} required /></label>
            <label>Confirm password<input type="password" autoComplete="new-password" value={confirmation} onChange={event=>setConfirmation(event.target.value)} maxLength={256} required /></label>
            <small>Use at least 12 characters, no more than 72 UTF-8 bytes, and no control characters.</small>
            <button type="submit" className="run" disabled={busy}>{busy?"Creating account…":"Create account"} <ArrowRight size={14}/></button>
          </form>
        )}
      </>
    ) : mode==="resend" ? (
      <>
        <div className="schema-login-icon"><MailCheck size={19}/></div>
        <h3>Resend verification email</h3>
        <p>If the address can receive verification email, instructions will be sent.</p>
        {accepted ? (
          <div className="notice" role="status">{GENERIC_ACCEPTED}</div>
        ) : (
          <form onSubmit={submitResend} style={{display:"grid",gap:"12px"}}>
            <label>Email<input type="email" autoComplete="email" value={email} onChange={event=>setEmail(event.target.value)} maxLength={254} required /></label>
            <button type="submit" className="run" disabled={busy}>{busy?"Sending…":"Send verification email"} <ArrowRight size={14}/></button>
          </form>
        )}
      </>
    ) : (
      <>
        <div className="schema-login-icon"><CheckCircle2 size={19}/></div>
        <h3>Verify your email</h3>
        {verified ? (
          <div className="notice" role="status">Email verified. You can now sign in.</div>
        ) : (
          <>
            <p>Confirm your email address to activate your NeuroSQL account.</p>
            {!token && <div className="schema-error" role="alert">This verification link is invalid or has expired. Request a new verification email.</div>}
            {token && <button type="button" className="run" disabled={busy} onClick={()=>void submitVerification()}>{busy?"Verifying…":"Verify email"} <ArrowRight size={14}/></button>}
            {(error || !token) && <button type="button" className="secondary" onClick={openResend}>Resend verification email</button>}
          </>
        )}
      </>
    )}
    {error&&<div className="schema-error" role="alert">{error}</div>}
    {(accepted||verified)&&<button type="button" className="secondary" onClick={onBack}>Return to sign in</button>}
  </section>;
}
