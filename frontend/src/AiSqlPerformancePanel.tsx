import { useEffect, useRef, useState } from "react";
import { Gauge, Sparkles } from "lucide-react";
import {
  createAiSqlPerformanceRequestGuard,
  recommendAiSqlPerformance,
  type AiSqlPerformanceResult,
} from "./aiSqlPerformance.ts";

type Props = {
  apiBaseUrl: string;
  enabled: boolean;
  token: string | null;
  workspaceId: string;
  sql: string;
  busy: boolean;
};

export function AiSqlPerformancePanel({
  apiBaseUrl,
  enabled,
  token,
  workspaceId,
  sql,
  busy,
}: Props) {
  const [result, setResult] = useState<AiSqlPerformanceResult | null>(null);
  const [error, setError] = useState("");
  const [running, setRunning] = useState(false);
  const controller = useRef<AbortController | null>(null);
  const requestGuard = useRef(createAiSqlPerformanceRequestGuard());
  const currentContext = useRef({ apiBaseUrl, enabled, token, workspaceId, sql, busy });
  currentContext.current = { apiBaseUrl, enabled, token, workspaceId, sql, busy };

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

  const canRequest = Boolean(token && workspaceId && sql.trim()) && !busy && !running;

  const requestRecommendations = async () => {
    if (!token || !workspaceId) {
      setError("Sign in with a durable account and select a workspace before requesting performance recommendations.");
      return;
    }
    if (!sql.trim()) {
      setError("Enter a SQL statement before requesting performance recommendations.");
      return;
    }

    controller.current?.abort();
    const activeController = new AbortController();
    controller.current = activeController;
    const requestId = requestGuard.current.begin();
    const requestContext = currentContext.current;
    const isCurrent = () => requestGuard.current.isCurrent(requestId)
      && currentContext.current.apiBaseUrl === requestContext.apiBaseUrl
      && currentContext.current.enabled === requestContext.enabled
      && currentContext.current.token === requestContext.token
      && currentContext.current.workspaceId === requestContext.workspaceId
      && currentContext.current.sql === requestContext.sql
      && currentContext.current.busy === requestContext.busy;
    setResult(null);
    setError("");
    setRunning(true);

    try {
      const next = await recommendAiSqlPerformance({
        apiBaseUrl,
        sql,
        token,
        workspaceId,
        signal: activeController.signal,
      });
      if (isCurrent()) setResult(next);
    } catch (requestError) {
      if (isCurrent() && !activeController.signal.aborted) {
        setError(requestError instanceof Error
          ? requestError.message
          : "Performance recommendations could not be generated.");
      }
    } finally {
      if (isCurrent()) {
        setRunning(false);
        controller.current = null;
      }
    }
  };

  return (
    <section id="ai-performance-panel" className="ai-performance-panel" aria-labelledby="ai-performance-title">
      <div className="ai-performance-heading">
        <div>
          <h3 id="ai-performance-title"><Gauge size={15} /> AI performance recommendations</h3>
          <p>Request a fresh PostgreSQL plan and get evidence-based, advisory suggestions.</p>
        </div>
        <button
          type="button"
          className="secondary explain-button"
          disabled={!canRequest}
          onClick={() => void requestRecommendations()}
        >
          <Sparkles size={14} />
          {running ? "Analyzing plan…" : "Get recommendations"}
        </button>
      </div>
      <p className="ai-performance-privacy">
        NeuroSQL sends the SQL to its backend to request non-ANALYZE EXPLAIN. Only a bounded,
        sanitized plan is sent to the configured AI provider; filter expressions, literal values,
        query results, and actual runtime measurements are excluded. The query is not executed.
      </p>
      {(!token || !workspaceId) && (
        <p className="ai-performance-state">Sign in with a durable account and select a workspace to use this feature.</p>
      )}
      {error && <p className="ai-performance-error" role="alert">{error}</p>}
      {running && <p className="ai-performance-state" role="status">PostgreSQL is building an estimated plan. No query rows are read or returned.</p>}
      {result && (
        <div className="ai-performance-result" aria-live="polite">
          <div className="ai-performance-result-heading">
            <b>Advisory recommendations</b>
            <span>{result.statementType} · estimated plan only</span>
          </div>
          {result.planTruncated && (
            <p className="ai-performance-truncated" role="note">
              The plan sent to the AI provider was truncated to safety limits. Recommendations may omit deeper plan details.
            </p>
          )}
          <p>{result.recommendations}</p>
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
