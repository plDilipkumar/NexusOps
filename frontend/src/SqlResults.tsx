import { Activity, AlertCircle, Clock3, Database } from "lucide-react";
import type { QueryResult } from "./sqlExecution.ts";

type Props = {
  result: QueryResult | null;
  error: string;
  running: boolean;
};

export function SqlResults({ result, error, running }: Props) {
  if (!result && !error && !running) return null;

  return (
    <section className="query-results" aria-label="SQL query results">
      <div className="query-results-head">
        <h3><Database size={15} /> Query results</h3>
        {result && (
          <div className="query-results-meta">
            <span>{result.rowCount} rows</span>
            <span><Clock3 size={13} /> {result.executionTimeMillis} ms</span>
          </div>
        )}
        {running && <div className="query-results-meta"><Activity size={13} /> Running read-only query…</div>}
      </div>
      {error && <div className="query-results-error" role="alert"><AlertCircle size={14} /> {error}</div>}
      {running && !error && <div className="query-results-running" role="status">Executing with the server-side read-only transaction and query timeout.</div>}
      {result && !error && (
        <>
          {result.truncated && <div className="query-results-truncated" role="status">The result reached the configured row limit. Refine the query to inspect more specific rows.</div>}
          {result.columns.length === 0 ? (
            <div className="query-results-empty">The query returned no column metadata.</div>
          ) : (
            <div className="query-results-scroll">
              <table>
                <thead><tr>{result.columns.map((column, index) => <th key={column.name + ":" + index} title={column.databaseType}>{column.name}</th>)}</tr></thead>
                <tbody>
                  {result.rows.length === 0 ? (
                    <tr><td colSpan={result.columns.length} className="query-results-empty">No rows returned.</td></tr>
                  ) : result.rows.map((row, rowIndex) => (
                    <tr key={rowIndex}>{row.map((cell, columnIndex) => (
                      <td key={columnIndex} title={cell === null ? "NULL" : cell ?? ""}>
                        {cell === null ? <span className="null-value">NULL</span> : cell}
                      </td>
                    ))}</tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}
