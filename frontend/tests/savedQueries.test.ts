import assert from "node:assert/strict";
import test from "node:test";
import {
  createSavedQuery,
  deleteSavedQuery,
  fetchSavedQueriesPage,
  parseSavedQueryPage,
  updateSavedQuery,
} from "../src/savedQueries.ts";

const token="A".repeat(43);
const workspaceId="123e4567-e89b-12d3-a456-426614174000";
const otherWorkspaceId="123e4567-e89b-12d3-a456-426614174099";
const savedQueryId="123e4567-e89b-42d3-a456-426614174001";
const ownerUserId="123e4567-e89b-42d3-a456-426614174002";
const item={
  savedQueryId,
  workspaceId,
  ownerUserId,
  name:"Monthly revenue",
  description:"Revenue grouped by month",
  queryText:"SELECT date_trunc('month', created_at) FROM orders",
  createdAt:"2026-11-02T10:00:00Z",
  updatedAt:"2026-11-02T10:00:00Z",
};
const page={items:[item],nextCursor:null,hasMore:false};
const options={apiBaseUrl:"https://api.example.test/",token,workspaceId};
const draft={name:"Monthly revenue",description:"Revenue grouped by month",queryText:"SELECT 1"};

test("parses a valid saved-query page scoped to the requested workspace",()=>{
  assert.deepEqual(parseSavedQueryPage(page,workspaceId),page);
});

test("rejects malformed, cross-workspace, duplicate, and inconsistent page data",()=>{
  assert.throws(()=>parseSavedQueryPage({...page,items:[{...item,workspaceId:otherWorkspaceId}]},workspaceId),/invalid saved-query record/);
  assert.throws(()=>parseSavedQueryPage({...page,items:[item,item]},workspaceId),/duplicate saved-query/);
  assert.throws(()=>parseSavedQueryPage({...page,hasMore:true},workspaceId),/pagination metadata/);
  assert.throws(()=>parseSavedQueryPage({...page,items:[{...item,queryText:""}]},workspaceId),/invalid saved-query record/);
});

test("fetches a no-store workspace page with bearer credentials, cursor, and server-side search",async()=>{
  let url="";
  let init:RequestInit|undefined;
  const fetcher:typeof fetch=async(input,requestInit)=>{
    url=String(input);init=requestInit;
    return new Response(JSON.stringify(page),{status:200,headers:{"Content-Type":"application/json"}});
  };
  const result=await fetchSavedQueriesPage({...options,cursor:"eyJjdXJzb3IifQ",search:" Finance "},fetcher);
  const parsed=new URL(url);
  assert.deepEqual(result,page);
  assert.equal(parsed.pathname,"/api/sql/saved-queries");
  assert.equal(parsed.searchParams.get("limit"),"25");
  assert.equal(parsed.searchParams.get("cursor"),"eyJjdXJzb3IifQ");
  assert.equal(parsed.searchParams.get("q"),"Finance");
  assert.equal(init?.method,"GET");
  assert.equal(init?.cache,"no-store");
  const headers=init?.headers as Record<string,string>;
  assert.equal(headers.Authorization,"Bearer "+token);
  assert.equal(headers["X-NeuroSQL-Workspace-Id"],workspaceId);
});

test("creates a saved query with normalized JSON and parses the response",async()=>{
  let requestBody="";
  let requestUrl="";
  const fetcher:typeof fetch=async(input,init)=>{
    requestUrl=String(input);requestBody=String(init?.body);
    return new Response(JSON.stringify(item),{status:201,headers:{"Content-Type":"application/json"}});
  };
  const result=await createSavedQuery(options,{name:" Monthly revenue ",description:" Revenue ",queryText:" SELECT 1 "},fetcher);
  assert.equal(requestUrl,"https://api.example.test/api/sql/saved-queries");
  assert.equal(result.savedQueryId,savedQueryId);
  assert.deepEqual(JSON.parse(requestBody),{name:"Monthly revenue",description:"Revenue",queryText:"SELECT 1"});
});

test("updates a saved query using its canonical item route",async()=>{
  let requestUrl="";
  let requestMethod="";
  const fetcher:typeof fetch=async(input,init)=>{
    requestUrl=String(input);requestMethod=String(init?.method);
    return new Response(JSON.stringify(item),{status:200});
  };
  await updateSavedQuery(options,savedQueryId,draft,fetcher);
  assert.equal(requestUrl,"https://api.example.test/api/sql/saved-queries/"+savedQueryId);
  assert.equal(requestMethod,"PUT");
});

test("deletes a saved query and accepts the no-content response",async()=>{
  let requestMethod="";
  const fetcher:typeof fetch=async(_input,init)=>{
    requestMethod=String(init?.method);
    return new Response(null,{status:204});
  };
  await deleteSavedQuery(options,savedQueryId,fetcher);
  assert.equal(requestMethod,"DELETE");
});

test("rejects invalid credentials, workspace, cursor, and drafts before network access",async()=>{
  let calls=0;
  const fetcher:typeof fetch=async()=>{calls+=1;return new Response(JSON.stringify(page),{status:200});};
  await assert.rejects(fetchSavedQueriesPage({...options,token:"bad"},fetcher),/valid NeuroSQL session/);
  await assert.rejects(fetchSavedQueriesPage({...options,workspaceId:"bad"},fetcher),/valid workspace/);
  await assert.rejects(fetchSavedQueriesPage({...options,cursor:"bad cursor"},fetcher),/cursor is invalid/);
  await assert.rejects(fetchSavedQueriesPage({...options,search:"x".repeat(121)},fetcher),/search must contain/);
  await assert.rejects(fetchSavedQueriesPage({...options,search:"bad\nsearch"},fetcher),/search must contain/);
  await assert.rejects(createSavedQuery(options,{...draft,name:" "},fetcher),/saved-query name/);
  await assert.rejects(createSavedQuery(options,{...draft,queryText:" "},fetcher),/Saved SQL/);
  await assert.rejects(createSavedQuery(options,{...draft,name:"bad\u0000name"},fetcher),/printable characters/);
  assert.equal(calls,0);
});

test("maps duplicate-name and authorization errors to safe messages",async()=>{
  for(const [status,message] of [
    [401,/Sign in again/],
    [403,/cannot perform this action/],
    [404,/not enabled/],
    [409,/already exists/],
    [429,/Too many saved-query requests/],
  ] as const){
    const fetcher:typeof fetch=async()=>new Response("{}",{status});
    await assert.rejects(fetchSavedQueriesPage(options,fetcher),message);
  }
});
