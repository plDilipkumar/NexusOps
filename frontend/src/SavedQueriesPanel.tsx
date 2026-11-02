import {useEffect,useRef,useState,type FormEvent} from "react";
import {AlertCircle,Bookmark,CheckCircle2,Clock3,Edit3,LoaderCircle,Plus,RefreshCw,RotateCcw,Save,Search,Trash2,X} from "lucide-react";
import {
  createSavedQuery,
  deleteSavedQuery,
  fetchSavedQueriesPage,
  updateSavedQuery,
  type SavedQuery,
} from "./savedQueries.ts";

type WorkspaceRole = "OWNER" | "ADMIN" | "MEMBER" | "VIEWER";

type Props = {
  apiBaseUrl: string;
  token: string;
  workspaceId: string;
  userId: string;
  role: WorkspaceRole | null;
  canCreate: boolean;
  canManage: boolean;
  initialSql: string;
  onInitialSqlConsumed: () => void;
  onRestore: (sql: string) => void;
};

export function SavedQueriesPanel({
  apiBaseUrl,
  token,
  workspaceId,
  userId,
  role,
  canCreate,
  canManage,
  initialSql,
  onInitialSqlConsumed,
  onRestore,
}: Props) {
  const [items,setItems]=useState<SavedQuery[]>([]);
  const [nextCursor,setNextCursor]=useState<string|null>(null);
  const [hasMore,setHasMore]=useState(false);
  const [loading,setLoading]=useState(false);
  const [loadingMore,setLoadingMore]=useState(false);
  const [saving,setSaving]=useState(false);
  const [deletingId,setDeletingId]=useState<string|null>(null);
  const [confirmDeleteId,setConfirmDeleteId]=useState<string|null>(null);
  const [error,setError]=useState("");
  const [formError,setFormError]=useState("");
  const [formOpen,setFormOpen]=useState(false);
  const [editingId,setEditingId]=useState<string|null>(null);
  const [name,setName]=useState("");
  const [description,setDescription]=useState("");
  const [queryText,setQueryText]=useState("");
  const [searchInput,setSearchInput]=useState("");
  const [appliedSearch,setAppliedSearch]=useState("");
  const [reloadKey,setReloadKey]=useState(0);
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
    void fetchSavedQueriesPage({apiBaseUrl,token,workspaceId,search:appliedSearch,signal:controller.signal})
      .then(page=>{
        if(requestGeneration!==generation.current)return;
        setItems(page.items);
        setNextCursor(page.nextCursor);
        setHasMore(page.hasMore);
      })
      .catch(reason=>{
        if(requestGeneration===generation.current&&!controller.signal.aborted){
          setError(reason instanceof Error?reason.message:"Unable to load saved queries.");
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
  },[apiBaseUrl,token,workspaceId,reloadKey,appliedSearch]);

  useEffect(()=>{
    if(initialSql&&token&&workspaceId&&canCreate){
      setEditingId(null);
      setName("");
      setDescription("");
      setQueryText(initialSql);
      setFormError("");
      setFormOpen(true);
    }
  },[initialSql,token,workspaceId,canCreate]);

  useEffect(()=>{
    setFormOpen(false);
    setEditingId(null);
    setConfirmDeleteId(null);
    setName("");
    setDescription("");
    setQueryText("");
    setFormError("");
    setSaving(false);
    setDeletingId(null);
    setSearchInput("");
    setAppliedSearch("");
    onInitialSqlConsumed();
  },[token,workspaceId]);

  const loadMore=async()=>{
    if(!token||!workspaceId||!nextCursor||loadingMore)return;
    const requestGeneration=generation.current;
    moreController.current?.abort();
    const controller=new AbortController();
    moreController.current=controller;
    setLoadingMore(true);
    setError("");
    try{
      const page=await fetchSavedQueriesPage({
        apiBaseUrl,token,workspaceId,cursor:nextCursor,search:appliedSearch,signal:controller.signal,
      });
      if(requestGeneration!==generation.current)return;
      setItems(current=>{
        const known=new Set(current.map(item=>item.savedQueryId.toLowerCase()));
        return [...current,...page.items.filter(item=>!known.has(item.savedQueryId.toLowerCase()))];
      });
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    }catch(reason){
      if(requestGeneration===generation.current&&!controller.signal.aborted){
        setError(reason instanceof Error?reason.message:"Unable to load the next saved-query page.");
      }
    }finally{
      if(requestGeneration===generation.current){
        setLoadingMore(false);
        moreController.current=null;
      }
    }
  };

  const submitSearch=(event:FormEvent<HTMLFormElement>)=>{
    event.preventDefault();
    setAppliedSearch(searchInput.trim());
  };

  const clearSearch=()=>{
    setSearchInput("");
    setAppliedSearch("");
  };

  const startCreate=()=>{
    if(!canCreate)return;
    setEditingId(null);
    setName("");
    setDescription("");
    setQueryText("");
    setFormError("");
    setFormOpen(true);
  };

  const startEdit=(item:SavedQuery)=>{
    if(!canManage)return;
    setEditingId(item.savedQueryId);
    setName(item.name);
    setDescription(item.description);
    setQueryText(item.queryText);
    setFormError("");
    setFormOpen(true);
  };

  const closeForm=()=>{
    setFormOpen(false);
    setEditingId(null);
    setFormError("");
    onInitialSqlConsumed();
  };

  const submitForm=async(event:FormEvent<HTMLFormElement>)=>{
    event.preventDefault();
    if(saving||!token||!workspaceId)return;
    if(editingId&&!canManage)return;
    if(!editingId&&!canCreate)return;
    setSaving(true);
    setFormError("");
    setError("");
    try{
      const draft={name,description,queryText};
      if(editingId){
        await updateSavedQuery({apiBaseUrl,token,workspaceId},editingId,draft);
      }else{
        await createSavedQuery({apiBaseUrl,token,workspaceId},draft);
      }
      setFormOpen(false);
      setEditingId(null);
      onInitialSqlConsumed();
      setReloadKey(value=>value+1);
    }catch(reason){
      setFormError(reason instanceof Error?reason.message:"Unable to save this query.");
    }finally{
      setSaving(false);
    }
  };

  const confirmDelete=async(item:SavedQuery)=>{
    if(!canManage||deletingId)return;
    setDeletingId(item.savedQueryId);
    setError("");
    try{
      await deleteSavedQuery({apiBaseUrl,token,workspaceId},item.savedQueryId);
      setItems(current=>current.filter(entry=>entry.savedQueryId!==item.savedQueryId));
      setConfirmDeleteId(null);
    }catch(reason){
      setError(reason instanceof Error?reason.message:"Unable to delete this saved query.");
      setConfirmDeleteId(null);
    }finally{
      setDeletingId(null);
    }
  };

  return <div className="saved-page">
    <div className="saved-heading">
      <div>
        <div className="eyebrow"><Bookmark size={14}/> WORKSPACE SQL LIBRARY</div>
        <h2>Saved queries</h2>
        <p>Keep reusable SQL in the selected workspace. Workspace members can view and restore queries; only administrators can edit or delete them.</p>
      </div>
      <div className="saved-heading-actions">
        <button type="button" className="secondary" onClick={()=>setReloadKey(value=>value+1)} disabled={!token||!workspaceId||loading}>
          <RefreshCw size={14}/> Refresh
        </button>
        {canCreate&&<button type="button" className="run" onClick={startCreate} disabled={!token||!workspaceId}>
          <Plus size={14}/> New saved query
        </button>}
      </div>
    </div>

    {!token ? <article className="saved-gate">
      <AlertCircle size={20}/><div><b>Sign in to access saved queries</b><p>Saved queries are available to authenticated workspace members. Open Schema explorer to sign in.</p></div>
    </article> : !workspaceId ? <article className="saved-gate">
      <AlertCircle size={20}/><div><b>Select a workspace first</b><p>Choose an active workspace in Schema explorer, then return here.</p></div>
    </article> : <>
      <div className="saved-toolbar">
        <span><CheckCircle2 size={14}/> {role||"Workspace"} · workspace-scoped</span>
        <span>{items.length} loaded</span>
      </div>
      <form className="saved-search" onSubmit={submitSearch}>
        <label htmlFor="saved-query-search">Search by name or description</label>
        <div className="saved-search-controls">
          <Search size={15}/>
          <input
            id="saved-query-search"
            value={searchInput}
            onChange={event=>setSearchInput(event.target.value)}
            maxLength={120}
            placeholder="e.g. monthly revenue or finance"
          />
          <button type="submit" className="secondary" disabled={loading}>Search</button>
          {(searchInput||appliedSearch)&&<button type="button" className="secondary" onClick={clearSearch}>Clear</button>}
        </div>
        {appliedSearch&&<small>Showing matches for “{appliedSearch}”. Search covers names and descriptions.</small>}
      </form>
      {error&&<div className="saved-error" role="alert"><AlertCircle size={15}/>{error}</div>}

      {formOpen&&<form className="saved-form" onSubmit={submitForm}>
        <div className="saved-form-heading">
          <div><b>{editingId?"Edit saved query":"New saved query"}</b><small>SQL is stored in this workspace and is not executed when saved.</small></div>
          <button type="button" className="icon-button" aria-label="Close saved query form" onClick={closeForm} disabled={saving}><X size={16}/></button>
        </div>
        <div className="saved-form-grid">
          <label>Query name<input value={name} onChange={event=>setName(event.target.value)} maxLength={120} required placeholder="Monthly revenue report"/></label>
          <label>Description <span>(optional)</span><input value={description} onChange={event=>setDescription(event.target.value)} maxLength={500} placeholder="What this query is used for"/></label>
        </div>
        <label>SQL statement<textarea value={queryText} onChange={event=>setQueryText(event.target.value)} maxLength={50000} required spellCheck={false} rows={8} placeholder="SELECT ..."/></label>
        {formError&&<div className="saved-error" role="alert"><AlertCircle size={14}/>{formError}</div>}
        <div className="saved-form-actions">
          <span>{Array.from(queryText).length.toLocaleString()} / 50,000 characters</span>
          <button type="button" className="secondary" onClick={closeForm} disabled={saving}>Cancel</button>
          <button type="submit" className="run" disabled={saving||!name.trim()||!queryText.trim()}>
            {saving?<LoaderCircle size={14} className="saved-spin"/>:<Save size={14}/>}
            {saving?"Saving…":editingId?"Save changes":"Save query"}
          </button>
        </div>
      </form>}

      {loading ? <div className="saved-state"><LoaderCircle size={18} className="saved-spin"/> Loading saved queries…</div> :
        items.length===0&&!error ? <article className="saved-empty">
          <Bookmark size={26}/>
          {appliedSearch ? <>
            <b>No saved queries match this search</b>
            <p>Try another name or description, or clear the search to browse the full workspace library.</p>
            <button type="button" className="secondary" onClick={clearSearch}>Clear search</button>
          </> : <>
            <b>No saved queries yet</b>
            <p>Save a query from the Workspace or create one here. Saved SQL is never executed automatically.</p>
            {canCreate&&<button type="button" className="run" onClick={startCreate}><Plus size={14}/> Create your first saved query</button>}
          </>}
        </article> :
        items.length>0 ? <div className="saved-list">
          {items.map(item=><article className="saved-entry" key={item.savedQueryId}>
            <div className="saved-entry-top">
              <div><h3>{item.name}</h3>{item.description&&<p>{item.description}</p>}</div>
              <time dateTime={item.updatedAt}>Updated {new Date(item.updatedAt).toLocaleString()}</time>
            </div>
            <pre>{item.queryText}</pre>
            <div className="saved-entry-bottom">
              <span><Clock3 size={13}/>Created {new Date(item.createdAt).toLocaleDateString()}</span>
              <span>{item.ownerUserId.toLowerCase()===userId.toLowerCase()?"Saved by you":"Saved by a workspace member"}</span>
              <button type="button" className="saved-restore" onClick={()=>{onInitialSqlConsumed();onRestore(item.queryText);}}><RotateCcw size={13}/> Restore in editor</button>
              {canManage&&<div className="saved-manage">
                <button type="button" className="secondary" onClick={()=>startEdit(item)}><Edit3 size={13}/> Edit</button>
                {confirmDeleteId===item.savedQueryId ? <>
                  <button type="button" className="danger-button" onClick={()=>void confirmDelete(item)} disabled={deletingId===item.savedQueryId}>
                    {deletingId===item.savedQueryId?<LoaderCircle size={13} className="saved-spin"/>:<Trash2 size={13}/>} Confirm delete
                  </button>
                  <button type="button" className="secondary" onClick={()=>setConfirmDeleteId(null)} disabled={deletingId===item.savedQueryId}>Cancel</button>
                </> : <button type="button" className="danger-button" onClick={()=>setConfirmDeleteId(item.savedQueryId)}><Trash2 size={13}/> Delete</button>}
              </div>}
            </div>
          </article>)}
        </div> : null}

      {hasMore&&<div className="saved-more"><button type="button" className="secondary" onClick={()=>void loadMore()} disabled={loadingMore}>
        {loadingMore?<LoaderCircle size={14} className="saved-spin"/>:<Clock3 size={14}/>}
        {loadingMore?"Loading…":"Load more saved queries"}
      </button></div>}
      {!hasMore&&items.length>0&&!loading&&<div className="saved-end">You're up to date with the saved queries in this workspace.</div>}
      {!canCreate&&<div className="saved-permission-note">Your VIEWER role allows browsing and restoring saved queries. Ask a workspace member or administrator to save new queries.</div>}
      {canCreate&&!canManage&&<div className="saved-permission-note">You can create saved queries. Editing and deletion are restricted to workspace administrators.</div>}
    </>}
  </div>;
}
