import {useEffect,useState,type FormEvent} from "react";
import {Activity,ArrowRight,Braces,CheckCircle2,ChevronDown,Clock3,Database,FileCode2,Gauge,History,Layers3,LockKeyhole,Play,Plus,Search,Settings2,ShieldCheck,Sparkles,Table2,TerminalSquare,WandSparkles,Zap} from "lucide-react";
const example="SELECT customer_id, COUNT(*) AS order_count\nFROM orders\nWHERE created_at >= CURRENT_DATE - INTERVAL '30 days'\nGROUP BY customer_id\nORDER BY order_count DESC\nLIMIT 20;";
type DatabaseHealth = { status: string; database?: string; postgresVersion?: string; timestamp?: string };
type SchemaCredentials = { username: string; password: string };
type SchemaColumn = { name: string; dataType: string; nullable: boolean; ordinalPosition: number };
type SchemaTable = { name: string; type: string; columns: SchemaColumn[] };
type SchemaInfo = { name: string; tables: SchemaTable[] };
type SchemaResponse = { database: string; schemas: SchemaInfo[]; tableCount: number; timestamp: string };
type DurableWorkspace = { workspaceId: string; name: string; slug: string; role: "OWNER" | "ADMIN" | "MEMBER" | "VIEWER" };
type DurableSession = { token: string; email: string; userId: string; sessionId: string; expiresAt: string };
const DURABLE_AUTH_ENABLED = import.meta.env.VITE_DURABLE_AUTH_ENABLED === "true";
const SCHEMA_LOGIN_ENABLED = import.meta.env.VITE_SCHEMA_LOGIN_ENABLED === "true";
function basicAuthorization(username: string, password: string) {
  const bytes = new TextEncoder().encode(username + ":" + password);
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return "Basic " + btoa(binary);
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "https://neurosql-api.onrender.com").replace(/\/$/, "");
export default function App(){
const [sql,setSql]=useState(example);const [notice,setNotice]=useState("");const [active,setActive]=useState("Workspace");
const [databaseHealth,setDatabaseHealth]=useState<DatabaseHealth|null>(null);const [healthLoading,setHealthLoading]=useState(true);const [healthError,setHealthError]=useState("");const [healthCheckedAt,setHealthCheckedAt]=useState("");
const [schemaUsername,setSchemaUsername]=useState("");
const [schemaPassword,setSchemaPassword]=useState("");
const [schemaCredentials,setSchemaCredentials]=useState<SchemaCredentials|null>(null);
const [schemaData,setSchemaData]=useState<SchemaResponse|null>(null);
const [schemaLoading,setSchemaLoading]=useState(false);
const [schemaError,setSchemaError]=useState("");
const [expandedTables,setExpandedTables]=useState<Record<string,boolean>>({});
const [durableEmail,setDurableEmail]=useState("");
const [durablePassword,setDurablePassword]=useState("");
const [durableSession,setDurableSession]=useState<DurableSession|null>(null);
const [durableWorkspaces,setDurableWorkspaces]=useState<DurableWorkspace[]>([]);
const [selectedWorkspaceId,setSelectedWorkspaceId]=useState("");
const [durableAuthLoading,setDurableAuthLoading]=useState(false);
const loadSchema=async(credentials:SchemaCredentials)=>{setSchemaLoading(true);setSchemaError("");try{const response=await fetch(API_BASE_URL+"/api/schema",{headers:{Accept:"application/json",Authorization:basicAuthorization(credentials.username,credentials.password)}});if(response.status===401)throw new Error("Sign-in failed. Check the owner username and password.");if(response.status===403)throw new Error("Schema access is disabled on the API or this account is not authorized.");if(response.status===404)throw new Error("The schema browser endpoint is not enabled on the API.");if(!response.ok)throw new Error("Schema request failed (HTTP "+response.status+").");const payload=(await response.json()) as SchemaResponse;if(!payload||!Array.isArray(payload.schemas)||typeof payload.tableCount!=="number")throw new Error("The API returned an unexpected schema response.");setSchemaData(payload);setSchemaCredentials(credentials);setSchemaPassword("");}catch(error){setSchemaData(null);setSchemaCredentials(null);setSchemaError(error instanceof Error?error.message:"Unable to load schema.");}finally{setSchemaLoading(false);}};
const loadDurableSchema=async(token:string,workspaceId:string)=>{
  setSchemaLoading(true);setSchemaError("");setSchemaCredentials(null);
  try{
    if(!workspaceId)throw new Error("Choose an active workspace before browsing schema.");
    const response=await fetch(API_BASE_URL+"/api/schema",{headers:{Accept:"application/json",Authorization:"Bearer "+token,"X-NeuroSQL-Workspace-Id":workspaceId},cache:"no-store"});
    if(response.status===401){setDurableSession(null);setDurableWorkspaces([]);setSelectedWorkspaceId("");throw new Error("Your session is invalid or expired. Sign in again.");}
    if(response.status===400)throw new Error("The selected workspace identifier was rejected.");
    if(response.status===403)throw new Error("This account does not have permission to browse that workspace.");
    if(response.status===404)throw new Error("Schema browsing is not enabled on the API.");
    if(!response.ok)throw new Error("Schema request failed (HTTP "+response.status+").");
    const payload=(await response.json()) as SchemaResponse;
    if(!payload||!Array.isArray(payload.schemas)||typeof payload.tableCount!=="number")throw new Error("The API returned an unexpected schema response.");
    setSchemaData(payload);
  }catch(error){setSchemaData(null);setSchemaError(error instanceof Error?error.message:"Unable to load schema.");}
  finally{setSchemaLoading(false);}
};
const handleDurableLogin=async(event:FormEvent<HTMLFormElement>)=>{
  event.preventDefault();
  const email=durableEmail.trim().toLowerCase();
  const password=durablePassword;
  if(!email||!password){setSchemaError("Enter your email and password.");return;}
  setDurableAuthLoading(true);setSchemaError("");setSchemaData(null);
  let issuedToken:string|null=null;
  try{
    const response=await fetch(API_BASE_URL+"/api/auth/login",{method:"POST",headers:{Accept:"application/json","Content-Type":"application/json"},body:JSON.stringify({email,password}),cache:"no-store"});
    if(response.status===401)throw new Error("Invalid email or password.");
    if(response.status===429){const retryAfter=response.headers.get("Retry-After");throw new Error(retryAfter?"Too many login attempts. Try again in "+retryAfter+" seconds.":"Too many login attempts. Try again later.");}
    if(!response.ok)throw new Error("Login failed (HTTP "+response.status+").");
    const loginPayload=(await response.json()) as {token?:unknown;expiresAt?:unknown};
    if(typeof loginPayload.token!=="string"||!/^[A-Za-z0-9_-]{43}$/.test(loginPayload.token)||typeof loginPayload.expiresAt!=="string"||!Number.isFinite(Date.parse(loginPayload.expiresAt))||Date.parse(loginPayload.expiresAt)<=Date.now())throw new Error("The API returned an invalid session response.");
    issuedToken=loginPayload.token;
    const authHeaders={Accept:"application/json",Authorization:"Bearer "+issuedToken};
    const identityResponse=await fetch(API_BASE_URL+"/api/auth/me",{headers:authHeaders,cache:"no-store"});
    if(identityResponse.status===401)throw new Error("The new session could not be validated. Please sign in again.");
    if(!identityResponse.ok)throw new Error("Unable to verify session identity (HTTP "+identityResponse.status+").");
    const identity=(await identityResponse.json()) as {userId?:unknown;sessionId?:unknown;expiresAt?:unknown};
    if(typeof identity.userId!=="string"||typeof identity.sessionId!=="string"||typeof identity.expiresAt!=="string"||!Number.isFinite(Date.parse(identity.expiresAt))||Date.parse(identity.expiresAt)<=Date.now())throw new Error("The API returned an invalid session identity.");
    const workspaceResponse=await fetch(API_BASE_URL+"/api/auth/workspaces",{headers:authHeaders,cache:"no-store"});
    if(workspaceResponse.status===401)throw new Error("The session was rejected while loading workspaces.");
    if(!workspaceResponse.ok)throw new Error("Unable to load workspaces (HTTP "+workspaceResponse.status+").");
    const rawWorkspaces=(await workspaceResponse.json()) as unknown;
    if(!Array.isArray(rawWorkspaces)||!rawWorkspaces.every((item:unknown)=>{
      if(typeof item!=="object"||item===null)return false;
      const workspace=item as Record<string,unknown>;
      return typeof workspace.workspaceId==="string"&&typeof workspace.name==="string"&&typeof workspace.slug==="string"&&["OWNER","ADMIN","MEMBER","VIEWER"].includes(String(workspace.role));
    }))throw new Error("The API returned an unexpected workspace list.");
    const workspaces=rawWorkspaces as DurableWorkspace[];
    setDurableSession({token:issuedToken,email,userId:identity.userId,sessionId:identity.sessionId,expiresAt:identity.expiresAt});
    setDurableWorkspaces(workspaces);setDurablePassword("");
    if(workspaces.length===0){setSelectedWorkspaceId("");setSchemaError("Signed in, but no active workspace membership is assigned to this account.");return;}
    const firstWorkspace=workspaces[0];
    setSelectedWorkspaceId(firstWorkspace.workspaceId);
    await loadDurableSchema(issuedToken,firstWorkspace.workspaceId);
  }catch(error){
    if(issuedToken){try{await fetch(API_BASE_URL+"/api/auth/logout",{method:"POST",headers:{Authorization:"Bearer "+issuedToken},cache:"no-store"});}catch{/* Best-effort revocation; never log the token. */}}
    setDurableSession(null);setDurableWorkspaces([]);setSelectedWorkspaceId("");setSchemaData(null);
    setSchemaError(error instanceof Error?error.message:"Unable to sign in.");
  }finally{setDurableAuthLoading(false);}
};
const logoutDurable=async()=>{
  const token=durableSession?.token;
  let revocationConfirmed=!token;
  if(token){try{const response=await fetch(API_BASE_URL+"/api/auth/logout",{method:"POST",headers:{Authorization:"Bearer "+token},cache:"no-store"});revocationConfirmed=response.status===204||response.status===401;}catch{/* The local token is still cleared; surface that server revocation was not confirmed. */}}
  setDurableSession(null);setDurableWorkspaces([]);setSelectedWorkspaceId("");setDurableEmail("");setDurablePassword("");setSchemaData(null);setSchemaError(revocationConfirmed?"":"Signed out locally, but the server could not confirm session revocation. The session may remain valid until it expires.");setExpandedTables({});
};
const selectDurableWorkspace=(workspaceId:string)=>{
  setSelectedWorkspaceId(workspaceId);setSchemaData(null);setExpandedTables({});setSchemaError("");
  if(durableSession&&workspaceId)void loadDurableSchema(durableSession.token,workspaceId);
};
const handleSchemaLogin=async(event:FormEvent<HTMLFormElement>)=>{event.preventDefault();const username=schemaUsername.trim();if(!username||!schemaPassword){setSchemaError("Enter both the owner username and password.");return;}await loadSchema({username,password:schemaPassword});};
const logoutSchema=()=>{setSchemaCredentials(null);setSchemaData(null);setSchemaUsername("");setSchemaPassword("");setSchemaError("");setExpandedTables({});};

useEffect(()=>{if(!durableSession)return;const expire=()=>{setDurableSession(null);setDurableWorkspaces([]);setSelectedWorkspaceId("");setSchemaData(null);setSchemaError("Your session expired. Sign in again.");};const remaining=Date.parse(durableSession.expiresAt)-Date.now();if(!Number.isFinite(remaining)||remaining<=0){expire();return;}const timer=window.setTimeout(expire,remaining);return()=>window.clearTimeout(timer);},[durableSession?.expiresAt]);
useEffect(()=>{let cancelled=false;const check=async()=>{try{const response=await fetch(`${API_BASE_URL}/api/health/database`,{headers:{Accept:"application/json"}});if(!response.ok)throw new Error(`HTTP ${response.status}`);const payload=(await response.json()) as DatabaseHealth;if(payload.status!=="UP")throw new Error("Database reported unavailable");if(!cancelled){setDatabaseHealth(payload);setHealthError("");setHealthCheckedAt(new Date().toLocaleTimeString());}}catch(error){if(!cancelled){setDatabaseHealth(null);setHealthError(error instanceof Error?error.message:"Unable to reach API");setHealthCheckedAt(new Date().toLocaleTimeString());}}finally{if(!cancelled)setHealthLoading(false);}};void check();const timer=window.setInterval(()=>void check(),60000);return()=>{cancelled=true;window.clearInterval(timer);};},[]);
const nav=[["Workspace",TerminalSquare],["Schema explorer",Database],["Query history",History],["Performance lab",Gauge]] as const;
const selectedWorkspace=durableWorkspaces.find(workspace=>workspace.workspaceId===selectedWorkspaceId);
return <div className="shell"><aside className="sidebar"><div className="brand"><div className="mark"><Braces size={20}/></div><div><b>NeuroSQL</b><small>SQL INTELLIGENCE WORKSPACE</small></div></div><div className="workspace"><Database size={17}/><div><b>{selectedWorkspace?.name||"Personal workspace"}</b><small>{selectedWorkspace?selectedWorkspace.role+" · PostgreSQL 17":"Neon · PostgreSQL 17"}</small></div><ChevronDown size={14}/></div><label>WORKSPACE</label><nav>{nav.map(([label,Icon])=><button key={label} className={active===label?"nav active":"nav"} onClick={()=>{setActive(label);setNotice(label==="Workspace"||label==="Schema explorer"?"":label+" is planned for the next milestone.");}}><Icon size={17}/>{label}{label==="Performance lab"&&<small>BETA</small>}</button>)}</nav><div className="sidebottom"><div className="safety"><ShieldCheck size={18}/><div><b>Safety first</b><small>Read-only query mode by default</small></div></div><button className="nav" onClick={()=>setNotice("Settings will be added with authentication and connection management.")}><Settings2 size={17}/>Settings</button><div className="profile"><div className="avatar">DK</div><div><b>Developer</b><small>Free plan</small></div></div></div></aside><main><header><div className="crumb">NeuroSQL <ArrowRight size={13}/> <b>{active}</b></div><div className="header-right"><span className="pill"><i/> {selectedWorkspace?.name||"Database workspace"} <ChevronDown size={13}/></span><div className="avatar">DK</div></div></header><section className="content">{active === "Schema explorer" ? (
<div className="schema-page">
  <div className="schema-page-heading">
    <div><div className="eyebrow"><Database size={14}/> READ-ONLY METADATA</div><h2>Schema explorer</h2><p>Browse schemas, tables, and ordered column definitions from the connected PostgreSQL database.</p></div>
    {schemaData && <div className="schema-summary"><b>{schemaData.tableCount}</b><small>tables found</small></div>}
  </div>
  {!SCHEMA_LOGIN_ENABLED && !DURABLE_AUTH_ENABLED ? (
    <article className="schema-gate"><LockKeyhole size={22}/><div><b>Schema access is disabled in this deployment</b><p>The browser remains hidden until an authentication mode is deliberately enabled on both frontend and backend.</p><small>No credentials are requested or stored by this disabled view.</small></div></article>
  ) : DURABLE_AUTH_ENABLED ? (
    durableSession ? (
      <>
        <article className="schema-accessbar"><div><CheckCircle2 size={17}/><div><b>Signed in as {durableSession.email}</b><small>Session expires {new Date(durableSession.expiresAt).toLocaleString()} · token held in memory only</small></div></div><div className="schema-actions">
          <label style={{display:"grid",gap:"5px",fontSize:"10px",color:"#a8b7ce"}}>Workspace
            <select aria-label="Active workspace" value={selectedWorkspaceId} disabled={schemaLoading||durableAuthLoading||durableWorkspaces.length===0} onChange={event=>selectDurableWorkspace(event.target.value)} style={{minWidth:"185px",maxWidth:"240px",padding:"8px 10px",border:"1px solid #30405c",borderRadius:"7px",background:"#0a1220",color:"#e7ecf6"}}>
              <option value="">Select workspace</option>{durableWorkspaces.map(workspace=><option key={workspace.workspaceId} value={workspace.workspaceId}>{workspace.name} · {workspace.role}</option>)}
            </select>
          </label>
          <button type="button" className="secondary" disabled={schemaLoading||!selectedWorkspaceId} onClick={()=>{if(durableSession&&selectedWorkspaceId)void loadDurableSchema(durableSession.token,selectedWorkspaceId);}}>{schemaLoading?"Refreshing…":"Refresh schema"}</button>
          <button type="button" className="secondary" onClick={()=>void logoutDurable()}>Sign out</button>
        </div></article>
        {durableWorkspaces.length===0&&<div className="schema-error" role="status">This account has no active workspace membership. Ask a workspace owner to assign access.</div>}
      </>
    ) : (
      <form className="schema-login" onSubmit={handleDurableLogin}>
        <div className="schema-login-icon"><LockKeyhole size={19}/></div><h3>Sign in to NeuroSQL</h3><p>Use an active, email-verified NeuroSQL account. Your bearer token stays in memory and is cleared when you sign out or the session expires.</p>
        <label>Email<input type="email" autoComplete="username" value={durableEmail} onChange={event=>setDurableEmail(event.target.value)} maxLength={254} required /></label>
        <label>Password<input type="password" autoComplete="current-password" value={durablePassword} onChange={event=>setDurablePassword(event.target.value)} maxLength={256} required /></label>
        <button type="submit" className="run" disabled={durableAuthLoading}>{durableAuthLoading?"Signing in…":"Sign in and load workspaces"} <ArrowRight size={14}/></button>
        <small>Accounts must be provisioned and email-verified. Registration and password recovery are not available yet.</small>
      </form>
    )
  ) : schemaData && schemaCredentials ? (
    <article className="schema-accessbar"><div><CheckCircle2 size={17}/><div><b>Connected to {schemaData.database}</b><small>Signed in as {schemaCredentials.username} · credentials are held in memory only</small></div></div><div className="schema-actions"><button type="button" className="secondary" disabled={schemaLoading} onClick={()=>void loadSchema(schemaCredentials)}>{schemaLoading?"Refreshing…":"Refresh schema"}</button><button type="button" className="secondary" onClick={logoutSchema}>Sign out</button></div></article>
  ) : (
    <form className="schema-login" onSubmit={handleSchemaLogin}>
      <div className="schema-login-icon"><LockKeyhole size={19}/></div><h3>Owner sign-in</h3><p>Use the owner credentials configured for the NeuroSQL API. This sends a read-only metadata request over HTTPS; no password is saved to browser storage.</p>
      <label>Username<input autoComplete="username" value={schemaUsername} onChange={event=>setSchemaUsername(event.target.value)} required /></label>
      <label>Password<input type="password" autoComplete="current-password" value={schemaPassword} onChange={event=>setSchemaPassword(event.target.value)} required /></label>
      <button type="submit" className="run" disabled={schemaLoading}>{schemaLoading?"Verifying access…":"Sign in and load schema"} <ArrowRight size={14}/></button>
      <small>Signing in will work only after the backend owner authentication and schema browser are enabled.</small>
    </form>
  )}
  {schemaError && <div className="schema-error" role="alert"><Activity size={15}/>{schemaError}</div>}
  {((!DURABLE_AUTH_ENABLED && SCHEMA_LOGIN_ENABLED && schemaData && schemaCredentials) || (DURABLE_AUTH_ENABLED && durableSession && selectedWorkspaceId && schemaData)) && (
    <div className="schema-list">
      {schemaData.schemas.length===0 ? <article className="schema-empty-state"><Database size={22}/><b>No visible schemas</b><p>The connected role did not return any visible schema metadata.</p></article> : schemaData.schemas.map(schema=>(
        <article className="schema-card" key={schema.name}>
          <div className="schema-card-heading"><div><Layers3 size={16}/><b>{schema.name}</b></div><span>{schema.tables.length} tables</span></div>
          {schema.tables.length===0 ? <p className="schema-no-tables">No tables in this schema.</p> : schema.tables.map(table=>{
            const tableKey=[schema.name,table.name].join(".");
            const expanded=Boolean(expandedTables[tableKey]);
            return <div className="schema-table" key={tableKey}>
              <button type="button" className="schema-table-toggle" aria-expanded={expanded} onClick={()=>setExpandedTables(current=>({...current,[tableKey]:!current[tableKey]}))}><Table2 size={15}/><b>{table.name}</b><small>{table.type}</small><span>{table.columns.length} columns</span><ChevronDown className={expanded?"rotated":""} size={14}/></button>
              {expanded && <div className="schema-columns"><div className="schema-column-row schema-column-head"><span>Column</span><span>Data type</span><span>Nullable</span></div>{table.columns.map(column=><div className="schema-column-row" key={column.name}><span>{column.name}</span><span>{column.dataType}</span><span>{column.nullable?"Yes":"No"}</span></div>)}</div>}
            </div>;
          })}
        </article>
      ))}
    </div>
  )}
</div>
) : (<><div className="welcome"><div><div className="eyebrow"><Sparkles size={14}/> SQL INTELLIGENCE, REIMAGINED</div><h1>Your data, <em>understood.</em></h1><p>Explore schemas, reason about queries, and find performance wins — in one focused workspace.</p></div><button className="secondary" onClick={()=>setNotice("The backend database connection is configured. Saved connections and schema browsing are still being implemented.")}><Plus size={16}/> Add connection</button></div>{notice&&<div className="notice" role="status">{notice}<button onClick={()=>setNotice("")}>×</button></div>}<div className="metrics"><article><div className="metric-head">DATABASE STATUS <Database size={17}/></div><h2><i className={databaseHealth?"connected":healthLoading?"checking":"offline"}/>{healthLoading?"Checking…":databaseHealth?"Connected":"Unavailable"}</h2><p>{databaseHealth?`${databaseHealth.database} · PostgreSQL ${databaseHealth.postgresVersion?.split(" ")[0]??"17"}`:healthLoading?"Checking backend and Neon…":healthError||"Neon PostgreSQL 17"}</p><footer>{databaseHealth?<CheckCircle2 size={14}/>:<Activity size={14}/>} {databaseHealth?`API + database connected · checked ${healthCheckedAt}`:healthLoading?"Contacting API and database…":`Connection check failed · ${healthCheckedAt}`}</footer></article><article><div className="metric-head">QUERY SAFETY <ShieldCheck size={17}/></div><h2>Read-only <span>DEFAULT</span></h2><p>Review before execution</p><footer><LockKeyhole size={14}/> Destructive SQL blocked by design</footer></article><article><div className="metric-head">QUERY PERFORMANCE <Gauge size={17}/></div><h2>Awaiting data</h2><p>Plan insights and tuning</p><footer><Activity size={14}/> Explain-plan analysis planned</footer></article></div><div className="sectionhead"><div><h3>Query workspace</h3><p>Draft SQL with a clear view of what runs and why.</p></div><span><i/> PostgreSQL</span></div><div className="editorgrid"><article className="editor"><div className="editorbar"><span><FileCode2 size={15}/> query.sql</span><small>SQL　 UTF-8</small></div><div className="code"><div className="lines">{sql.split("\n").map((_,i)=><span key={i}>{i+1}</span>)}</div><textarea aria-label="SQL editor" spellCheck={false} value={sql} onChange={e=>setSql(e.target.value)}/></div><div className="editorfoot"><span>Changes are local until saved</span><button className="run" onClick={()=>setNotice("Query execution is not connected yet. This preview does not send SQL to a database.")}><Play size={14} fill="currentColor"/> Run query　⌘ ↵</button></div></article><aside className="copilot"><div className="cophead"><div className="aiicon"><WandSparkles size={17}/></div><div><b>AI Query Copilot</b><small>Context-aware assistance</small></div><span>PREVIEW</span></div><div className="intro"><div className="spark"><Sparkles size={19}/></div><h3>What would you like to know?</h3><p>Ask in plain language. NeuroSQL will help turn intent into SQL you can review.</p></div><div className="prompts"><button onClick={()=>setNotice("Natural-language SQL generation is not connected yet.")}><WandSparkles size={15}/> Write a query for me <ArrowRight size={14}/></button><button onClick={()=>setNotice("Query explanation API is not implemented yet.")}><Braces size={15}/> Explain this query <ArrowRight size={14}/></button><button onClick={()=>setNotice("Optimization analysis is planned after explain-plan support.")}><Zap size={15}/> Find performance wins <ArrowRight size={14}/></button></div><div className="ask"><Search size={15}/><span>Describe a query...</span><ArrowRight size={15}/></div><small className="disclaimer"><LockKeyhole size={13}/> Review generated SQL before running.</small></aside></div><div className="lower"><article><div className="lowhead"><h3><Layers3 size={16}/> Schema explorer</h3><button onClick={()=>setNotice("Schema browsing API is not connected yet.")}>View all <ArrowRight size={13}/></button></div><div className="empty"><div><Table2 size={21}/></div><b>Your schema will appear here</b><p>Connect and introspect a database to browse tables, columns, and relationships.</p></div></article><article><div className="lowhead"><h3><History size={16}/> Recent activity</h3><button onClick={()=>setNotice("Query history will be persisted after API storage and authentication are implemented.")}>View history <ArrowRight size={13}/></button></div><div className="activity"><Clock3 size={17}/><div><b>Ready for your first query</b><p>Query runs and diagnostics will appear here.</p></div></div><div className="activity"><TerminalSquare size={17}/><div><b>Keyboard-first workflow</b><p>Shortcuts and saved snippets are planned.</p></div></div></article></div><div className="foot"><span>NeuroSQL · Early access build</span><span><ShieldCheck size={13}/> Secure by design · PostgreSQL compatible</span></div></>)}</section></main></div>}