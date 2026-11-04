import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ArrowRight, LockKeyhole, Search, Sparkles, WandSparkles } from "lucide-react";
import { buildDurableSchemaHeaders } from "./durableAuth.ts";
import {
  buildAiSchemaContext,
  createAiSqlRequestGuard,
  generateAiSql,
  type AiSqlSchemaInput,
  type AiSqlGenerationResponse,
} from "./aiSql.ts";

type AiSqlCopilotProps = {
  apiBaseUrl: string;
  enabled: boolean;
  token: string | null;
  workspaceId: string;
  schemas: readonly AiSqlSchemaInput[] | null;
  busy: boolean;
  onUseDraft: (sql: string) => void;
};

const suggestions = [
  "Show the 10 most recent orders",
  "Count records grouped by status",
  "Find duplicate email addresses",
];

export function AiSqlCopilot({
  apiBaseUrl,
  enabled,
  token,
  workspaceId,
  schemas,
  busy,
  onUseDraft,
}: AiSqlCopilotProps) {
  const [prompt, setPrompt] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState<AiSqlGenerationResponse | null>(null);
  const [applied, setApplied] = useState(false);
  const context = useMemo(() => buildAiSchemaContext(schemas), [schemas]);
  const requestGuard = useRef(createAiSqlRequestGuard());
  const abortController = useRef<AbortController | null>(null);

  useEffect(() => {
    requestGuard.current.invalidate();
    abortController.current?.abort();
    abortController.current = null;
    setLoading(false);
    setError("");
    setResult(null);
    setApplied(false);
    return () => {
      requestGuard.current.invalidate();
      abortController.current?.abort();
      abortController.current = null;
    };
  }, [enabled, token, workspaceId, context]);

  const ready = enabled && Boolean(token) && Boolean(workspaceId) && context.schemas.length > 0;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!ready || !token || !workspaceId || busy || loading) return;

    abortController.current?.abort();
    const controller = new AbortController();
    abortController.current = controller;
    const requestId = requestGuard.current.begin();
    setLoading(true);
    setError("");
    setResult(null);
    setApplied(false);

    try {
      const generated = await generateAiSql({
        apiBaseUrl,
        prompt,
        schemas: context.schemas,
        headers: buildDurableSchemaHeaders(token, workspaceId),
        signal: controller.signal,
      });
      if (requestGuard.current.isCurrent(requestId)) setResult(generated);
    } catch (requestError) {
      if (!controller.signal.aborted && requestGuard.current.isCurrent(requestId)) {
        setError(requestError instanceof Error
          ? requestError.message
          : "The AI SQL request could not be completed.");
      }
    } finally {
      if (requestGuard.current.isCurrent(requestId)) {
        setLoading(false);
        abortController.current = null;
      }
    }
  };

  return (
    <aside className="copilot">
      <div className="cophead">
        <div className="aiicon"><WandSparkles size={17} /></div>
        <div><b>AI Query Copilot</b><small>Workspace-aware SQL drafting</small></div>
        <span>PREVIEW</span>
      </div>
      <div className="intro">
        <div className="spark"><Sparkles size={19} /></div>
        <h3>Describe the result you need</h3>
        <p>Generate one read-only SQL draft from the visible schema. Nothing is executed automatically.</p>
      </div>
      <div className="prompts ai-copilot-prompts">
        {suggestions.map((suggestion) => (
          <button key={suggestion} type="button" disabled={!enabled || busy || loading} onClick={() => setPrompt(suggestion)}>
            <Search size={14} />{suggestion}<ArrowRight size={13} />
          </button>
        ))}
      </div>
      <form className="ai-copilot-form" onSubmit={(event) => void submit(event)}>
        <label htmlFor="ai-sql-prompt">Your request</label>
        <textarea
          id="ai-sql-prompt"
          value={prompt}
          onChange={(event) => setPrompt(event.target.value)}
          placeholder="e.g. Compare monthly order totals for the last six months"
          maxLength={2_000}
          rows={4}
          disabled={!enabled || busy || loading}
        />
        <button className="run" type="submit" disabled={!ready || busy || loading || Array.from(prompt.trim()).length < 5}>
          <Sparkles size={14} />{loading ? "Generating…" : "Generate SQL draft"}<ArrowRight size={14} />
        </button>
      </form>
      {!enabled && <p className="ai-copilot-state" role="status">AI generation is not enabled in this deployment.</p>}
      {enabled && !token && <p className="ai-copilot-state" role="status">Sign in with a verified account to use the Copilot.</p>}
      {enabled && token && !workspaceId && <p className="ai-copilot-state" role="status">Select a workspace before generating SQL.</p>}
      {enabled && token && workspaceId && context.schemas.length === 0 && (
        <p className="ai-copilot-state" role="status">Load visible schema metadata before generating SQL.</p>
      )}
      {context.truncated && <p className="ai-copilot-state">Large schemas are reduced to a bounded metadata sample for this request.</p>}
      {busy && <p className="ai-copilot-state">Wait for the active query or plan request to finish.</p>}
      {error && <p className="ai-copilot-error" role="alert">{error}</p>}
      {result && (
        <section className="ai-copilot-result" aria-live="polite">
          <div className="ai-copilot-result-heading"><b>{result.statementType} draft</b><span>Not executed</span></div>
          <pre>{result.sql}</pre>
          {result.warnings.map((warning, index) => <p className="ai-copilot-warning" key={index}>{warning}</p>)}
          <div className="ai-copilot-result-actions">
            <button type="button" className="secondary" onClick={() => { onUseDraft(result.sql); setApplied(true); }}>
              {applied ? "Insert again" : "Use in editor"}<ArrowRight size={13} />
            </button>
          </div>
        </section>
      )}
      <small className="disclaimer"><LockKeyhole size={13} /> Only schema names and types are sent; table rows are never included. Review the draft before running it.</small>
    </aside>
  );
}
