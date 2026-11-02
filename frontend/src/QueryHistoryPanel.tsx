import {useEffect,useRef,useState} from "react";
import {AlertCircle,CheckCircle2,Clock3,History,LoaderCircle,RefreshCw,RotateCcw,Timer} from "lucide-react";
import {fetchQueryHistoryPage,type QueryHistoryEntry,type QueryHistoryOutcomeFilter} from "./queryHistory.ts";

type Props = {
  apiBaseUrl: string;
  token: string;
  workspaceId: string;
  onRestore: (sql: string) => void;
};

export function QueryHistoryPanel({apiBaseUrl,token,workspaceId,onRestore}:Props) {
  const [items,setItems]=useState<QueryHistoryEntry[]>([]);
  const [nextCursor,setNextCursor]=useState<string|null>(null);
  const [hasMore,setHasMore]=useState(false);
  const [loading,setLoading]=useState(false);
  const [loadingMore,setLoadingMore]=useState(false);
  const [error,setError]=useState("");
  const [reloadKey,setReloadKey]=useState(0);
  const [outcome,setOutcome]=useState<QueryHistoryOutcomeFilter>("ALL");
  const generation=useRef(0);
  const initialController=useRef<AbortController|null>(null);
  const moreController=useRef<AbortController|null>(null);

  useEffect(()=>{
    generation.current+=1;
    const requestGeneration=generation.current;
    initialController.current?.abort();
    moreController.current?.abort();
    initialController.current=null;
    moreController.current=null;
    setItems([]);
    setNextCursor(null);
    setHasMore(false);
    setError("");
    setLoadingMore(false);

    if(!token||!workspaceId){
      setLoading(false);
      return;
    }

    const controller=new AbortController();
    initialController.current=controller;
    setLoading(true);
    void fetchQueryHistoryPage({apiBaseUrl,token,workspaceId,outcome,signal:controller.signal})
      .then(page=>{
        if(requestGeneration!==generation.current)return;
        setItems(page.items);
        setNextCursor(page.nextCursor);
        setHasMore(page.hasMore);
      })
      .catch(reason=>{
        if(requestGeneration===generation.current&&!controller.signal.aborted){
          setError(reason instanceof Error?reason.message:"Unable to load query history.");
        }
      })
      .finally(()=>{
        if(requestGeneration===generation.current){
          setLoading(false);
          initialController.current=null;
        }
      });

    return ()=>{
      controller.abort();
      moreController.current?.abort();
      generation.current+=1;
    };
  },[apiBaseUrl,token,workspaceId,reloadKey,outcome]);

  const loadMore=async()=>{
    if(!token||!workspaceId||!nextCursor||loadingMore)return;
    const requestGeneration=generation.current;
    moreController.current?.abort();
    const controller=new AbortController();
    moreController.current=controller;
    setLoadingMore(true);
    setError("");
    try{
      const page=await fetchQueryHistoryPage({apiBaseUrl,token,workspaceId,cursor:nextCursor,outcome,signal:controller.signal});
      if(requestGeneration!==generation.current)return;
      setItems(current=>{
        const known=new Set(current.map(item=>item.historyId));
        return [...current,...page.items.filter(item=>!known.has(item.historyId))];
      });
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    }catch(reason){
      if(requestGeneration===generation.current&&!controller.signal.aborted){
        setError(reason instanceof Error?reason.message:"Unable to load the next history page.");
      }
    }finally{
      if(requestGeneration===generation.current){
        setLoadingMore(false);
        moreController.current=null;
      }
    }
  };

  const restore=(entry:QueryHistoryEntry)=>{
    onRestore(entry.queryText);
  };

  return <div className="history-page">
    <div className="history-heading">
      <div>
        <div className="eyebrow"><History size={14}/> PERSONAL QUERY ACTIVITY</div>
        <h2>Query history</h2>
        <p>Review your recent SQL executions in the selected workspace. History is private to your account.</p>
      </div>
      <button type="button" className="secondary" onClick={()=>setReloadKey(value=>value+1)} disabled={!token||!workspaceId||loading}>
        <RefreshCw size={14}/> Refresh
      </button>
    </div>

    {!token ? <article className="history-gate">
      <AlertCircle size={20}/>
      <div><b>Sign in to view query history</b><p>Query history is available only to authenticated NeuroSQL accounts. Open Schema explorer to sign in.</p></div>
    </article> : !workspaceId ? <article className="history-gate">
      <AlertCircle size={20}/>
      <div><b>Select a workspace first</b><p>Choose an active workspace in Schema explorer, then return here to view its query history.</p></div>
    </article> : <>
      <div className="history-toolbar">
        <span><CheckCircle2 size={14}/> User-private · workspace-scoped</span>
        <label className="history-filter">
          Outcome
          <select aria-label="Filter history by outcome" value={outcome} onChange={event=>setOutcome(event.target.value as QueryHistoryOutcomeFilter)}>
            <option value="ALL">All outcomes</option>
            <option value="SUCCEEDED">Succeeded</option>
            <option value="FAILED">Failed</option>
            <option value="CANCELED">Canceled</option>
          </select>
        </label>
        <span>{items.length} loaded</span>
      </div>
      {error&&<div className="history-error" role="alert"><AlertCircle size={15}/>{error}</div>}
      {loading ? <div className="history-state"><LoaderCircle size={18} className="history-spin"/> Loading query history…</div> :
        items.length===0&&!error ? <article className="history-empty">
          <History size={25}/><b>{outcome==="ALL"?"No query history yet":"No matching executions"}</b>
          <p>{outcome==="ALL"?"Run a read-only SQL query from the Workspace. Completed executions will appear here when query history is enabled by the API.":"Try another outcome filter to see more executions."}</p>
        </article> :
        items.length>0 ? <div className="history-list">
          {items.map(entry=>{
            const statusClass=entry.outcome==="SUCCEEDED"?"success":entry.outcome==="FAILED"?"failed":"cancelled";
            const statusLabel=entry.outcome==="SUCCEEDED"?"Succeeded":entry.outcome==="FAILED"?"Failed":"Canceled";
            return <article className="history-entry" key={entry.historyId}>
              <div className="history-entry-top">
                <span className={"history-status "+statusClass}>
                  {entry.outcome==="SUCCEEDED"?<CheckCircle2 size={13}/>:<AlertCircle size={13}/>}
                  {statusLabel}
                </span>
                <time dateTime={entry.createdAt}>{new Date(entry.createdAt).toLocaleString()}</time>
              </div>
              <pre>{entry.queryText}</pre>
              <div className="history-entry-bottom">
                <span><Timer size={13}/>{entry.executionTimeMillis} ms</span>
                <span><History size={13}/>{entry.rowCount===null?"No rows returned":entry.rowCount+" rows"}</span>
                {entry.truncated&&<span className="history-truncated">Result truncated</span>}
                <button type="button" className="history-restore" onClick={()=>restore(entry)}><RotateCcw size={13}/> Restore in editor</button>
              </div>
            </article>;
          })}
        </div> : null}
      {hasMore&&<div className="history-more"><button type="button" className="secondary" onClick={()=>void loadMore()} disabled={loadingMore}>
        {loadingMore?<LoaderCircle size={14} className="history-spin"/>:<Clock3 size={14}/>}
        {loadingMore?"Loading…":"Load more history"}
      </button></div>}
      {!hasMore&&items.length>0&&!loading&&<div className="history-end">You're up to date with the available history.</div>}
    </>}
  </div>;
}
