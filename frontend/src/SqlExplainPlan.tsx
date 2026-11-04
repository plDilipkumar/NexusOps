import { Activity, AlertCircle, Clock3, Gauge, GitBranch } from "lucide-react";
import type { ExplainPlanNode, ExplainResult } from "./sqlExplain.ts";

type Props = {
  result: ExplainResult | null;
  error: string;
  running: boolean;
};

function formatEstimate(value: number | null): string {
  return value === null ? "—" : value.toLocaleString("en-US", { maximumFractionDigits: 2 });
}

function PlanNode({ node, depth = 0 }: { node: ExplainPlanNode; depth?: number }) {
  const relation = [node.schema, node.relationName].filter(Boolean).join(".");
  return (
    <div className="explain-plan-node">
      <div className="explain-plan-node-row" style={{ paddingLeft: Math.min(depth, 32) * 15 }}>
        <span className="explain-plan-node-kind">{node.nodeType}</span>
        {relation && <span className="explain-plan-relation">{relation}</span>}
        {node.indexName && <span className="explain-plan-index">index {node.indexName}</span>}
        <span className="explain-plan-estimates">
          cost {formatEstimate(node.startupCost)}..{formatEstimate(node.totalCost)}
          {" · "}rows {formatEstimate(node.planRows)}
          {" · "}width {formatEstimate(node.planWidth)}
        </span>
      </div>
      {node.children.length > 0 && (
        <div className="explain-plan-children">
          {node.children.map((child, index) => <PlanNode key={node.nodeType + ":" + index} node={child} depth={depth + 1} />)}
        </div>
      )}
    </div>
  );
}

export function SqlExplainPlan({ result, error, running }: Props) {
  if (!result && !error && !running) return null;

  return (
    <section className="explain-plan" aria-label="PostgreSQL execution plan">
      <div className="explain-plan-header">
        <h3><Gauge size={15} /> Estimated query plan</h3>
        {result && (
          <div className="explain-plan-meta">
            <span><Clock3 size={13} /> Plan request {result.elapsedTimeMillis} ms</span>
            <span><GitBranch size={13} /> Estimated cost {formatEstimate(result.root.totalCost)}</span>
          </div>
        )}
        {running && <div className="explain-plan-meta"><Activity size={13} /> Planning…</div>}
      </div>
      {error && <div className="explain-plan-error" role="alert"><AlertCircle size={14} /> {error}</div>}
      {running && !error && <div className="explain-plan-running" role="status">PostgreSQL is estimating the plan in a read-only transaction. The query is not executed.</div>}
      {result && !error && (
        <>
          <div className="explain-plan-notice" role="note">Estimated plan only — EXPLAIN ANALYZE is disabled, so actual rows and execution timings are not collected.</div>
          <div className="explain-plan-tree"><PlanNode node={result.root} /></div>
        </>
      )}
    </section>
  );
}
