import { useEffect, useRef, useState } from "react";
import { Sparkles } from "lucide-react";
import {
  createAiSqlExplanationRequestGuard,
  explainAiSql,
  type AiSqlExplanationResult,
} from "./aiSqlExplanation.ts";

type Props = {
  apiBaseUrl: string;
  enabled: boolean;
  token: string | null;
  workspaceId: string;
  sql: string;
  busy: boolean;
};

export function AiSqlExplanationPanel({
  apiBaseUrl,
  enabled,
  token,
  workspaceId,
  sql,
  busy,
}: Props) {
  const [result, setResult] = useState<AiSqlExplanationResult | null>(null);
  const [error, setError] = useState("");
  const [running, setRunning] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const requestGuard = useRef(createAiSqlExplanationRequestGuard());

  useEffect(() => {
    requestGuard.current.invalidate();
    controller.current?.abort();
    controller.current = null;
    setResult(null);
    setError("");
    setRunning(false);

    return () => {
      requestGuard.current.invalidate();
      controller.current?.abort();
      controller.current = null;
    };
  }, [enabled, apiBaseUrl, token, workspaceId, sql, busy]);

  if (!enabled) return null;

  const canExplain = Boolean(token && workspaceId && sql.trim()) && !busy && !running;

  const requestExplanation = async () => {
    if (!token || !workspaceId) {
      setError("Sign in with a durable account and select a workspace before requesting an AI explanation.");
      return;
    }
    if (!sql.trim()) {
      setError("Enter a SQL statement before requesting an explanation.");
      return;
    }

    controller.current?.abort();
    const activeController = new AbortController();
    controller.current = activeController;
    const requestId = requestGuard.current.begin();
    setResult(null);
    setError("");
    setRunning(true);

    try {
      const next = await explainAiSql({
        apiBaseUrl,
        sql,
        token,
        workspaceId,
        signal: activeController.signal,
      });
      if (requestGuard.current.isCurrent(requestId)) setResult(next);
    } catch (requestError) {
      if (requestGuard.current.isCurrent(requestId) && !activeController.signal.aborted) {
        setError(requestError instanceof Error
          ? requestError.message
          : "The AI explanation could not be generated.");
      }
    } finally {
      if (requestGuard.current.isCurrent(requestId)) {
        setRunning(false);
        controller.current = null;
      }
    }
  };

  return (
    <section className="ai-explanation-panel" aria-labelledby="ai-explanation-title">
      <div className="ai-explanation-heading">
        <div>
          <h3 id="ai-explanation-title"><Sparkles size={15} /> AI query explanation</h3>
          <p>Understand the selected SQL without executing it.</p>
        </div>
        <button
          type="button"
          className="secondary explain-button"
          disabled={!canExplain}
          onClick={() => void requestExplanation()}
        >
          <Sparkles size={14} />
          {running ? "Explaining…" : "Explain SQL"}
        </button>
      </div>
      <p className="ai-explanation-privacy">
        When you request an explanation, the SQL text is sent to the configured AI provider.
        Query results and table rows are never sent, and the statement is not executed.
      </p>
      {(!token || !workspaceId) && (
        <p className="ai-explanation-state">Sign in with a durable account and select a workspace to use this feature.</p>
      )}
      {error && <p className="ai-explanation-error" role="alert">{error}</p>}
      {running && <p className="ai-explanation-state" role="status">Generating a plain-text explanation…</p>}
      {result && (
        <div className="ai-explanation-result" aria-live="polite">
          <div className="ai-explanation-result-heading">
            <b>Explanation</b>
            <span>{result.statementType}</span>
          </div>
          <p>{result.explanation}</p>
          {result.warnings.length > 0 && (
            <ul>
              {result.warnings.map((warning, index) => <li key={index}>{warning}</li>)}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
