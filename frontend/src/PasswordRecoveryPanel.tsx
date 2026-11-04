import {useState,type FormEvent} from "react";
import {ArrowLeft,ArrowRight,KeyRound,MailCheck} from "lucide-react";
import {
  confirmPasswordReset,
  requestPasswordReset,
  type CapturedPasswordResetToken,
} from "./passwordRecovery.ts";

type Props = {
  apiBaseUrl: string;
  initialMode: "request" | "confirm";
  initialToken: string | null;
  onBack: () => void;
};

export function PasswordRecoveryPanel({apiBaseUrl,initialMode,initialToken,onBack}:Props) {
  const [mode,setMode] = useState<"request"|"confirm">(initialMode);
  const [token] = useState<CapturedPasswordResetToken["token"]>(initialToken);
  const [email,setEmail] = useState("");
  const [password,setPassword] = useState("");
  const [confirmation,setConfirmation] = useState("");
  const [busy,setBusy] = useState(false);
  const [requestAccepted,setRequestAccepted] = useState(false);
  const [resetCompleted,setResetCompleted] = useState(false);
  const [error,setError] = useState("");

  const submitRequest = async(event:FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    setBusy(true);
    try {
      await requestPasswordReset(apiBaseUrl,email);
      setRequestAccepted(true);
    } catch(problem) {
      setError(problem instanceof Error?problem.message:"Password reset is temporarily unavailable. Try again later.");
    } finally {
      setBusy(false);
    }
  };

  const submitConfirmation = async(event:FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError("");
    if(password!==confirmation) {
      setError("The passwords do not match.");
      return;
    }
    setBusy(true);
    try {
      if(!token) throw new Error("This password-reset link is invalid or expired. Request a new link.");
      await confirmPasswordReset(apiBaseUrl,token,password);
      setPassword("");
      setConfirmation("");
      setResetCompleted(true);
    } catch(problem) {
      setError(problem instanceof Error?problem.message:"Password reset is temporarily unavailable. Try again later.");
    } finally {
      setBusy(false);
    }
  };

  return <section className="schema-login" aria-label="Password recovery">
    <button type="button" className="secondary" onClick={onBack} disabled={busy}><ArrowLeft size={14}/> Back to sign in</button>
    {mode==="request" ? (
      <>
        <div className="schema-login-icon"><MailCheck size={19}/></div>
        <h3>Reset your password</h3>
        <p>Enter the email address associated with your NeuroSQL account. If an eligible account exists, we will send a reset link.</p>
        {requestAccepted ? (
          <div className="notice" role="status">
            If an account exists for that address, a password-reset link will be sent.
          </div>
        ) : (
          <form onSubmit={submitRequest} style={{display:"grid",gap:"12px"}}>
            <label>Email<input type="email" autoComplete="email" value={email} onChange={event=>setEmail(event.target.value)} maxLength={254} required /></label>
            <button type="submit" className="run" disabled={busy}>{busy?"Sending…":"Send reset link"} <ArrowRight size={14}/></button>
          </form>
        )}
      </>
    ) : (
      <>
        <div className="schema-login-icon"><KeyRound size={19}/></div>
        <h3>Choose a new password</h3>
        {resetCompleted ? (
          <div className="notice" role="status">Your password has been reset. Please sign in again.</div>
        ) : !token ? (
          <div className="schema-error" role="alert">This password-reset link is invalid or expired. Request a new link.</div>
        ) : (
          <form onSubmit={submitConfirmation} style={{display:"grid",gap:"12px"}}>
            <label>New password<input type="password" autoComplete="new-password" value={password} onChange={event=>setPassword(event.target.value)} maxLength={256} required /></label>
            <label>Confirm new password<input type="password" autoComplete="new-password" value={confirmation} onChange={event=>setConfirmation(event.target.value)} maxLength={256} required /></label>
            <small>Use at least 12 characters, no more than 72 UTF-8 bytes, and no control characters.</small>
            <button type="submit" className="run" disabled={busy}>{busy?"Updating password…":"Update password"} <ArrowRight size={14}/></button>
          </form>
        )}
        {!token && <button type="button" className="secondary" onClick={()=>{setMode("request");setError("");}}>Request another reset link</button>}
      </>
    )}
    {error&&<div className="schema-error" role="alert">{error}</div>}
    {(requestAccepted||resetCompleted)&&<button type="button" className="secondary" onClick={onBack}>Return to sign in</button>}
  </section>;
}
