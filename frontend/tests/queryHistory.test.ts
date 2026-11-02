import assert from "node:assert/strict";
import test from "node:test";
import {fetchQueryHistoryPage,parseQueryHistoryPage} from "../src/queryHistory.ts";

const token="A".repeat(43);
const workspaceId="123e4567-e89b-12d3-a456-426614174000";
const historyId="123e4567-e89b-42d3-a456-426614174001";
const entry={
  historyId,
  queryText:"SELECT 42",
  outcome:"SUCCEEDED",
  rowCount:1,
  executionTimeMillis:8,
  truncated:false,
  createdAt:"2026-11-02T10:00:00Z",
};
const page={items:[entry],nextCursor:null,hasMore:false};

test("parses a valid private query-history page",()=>{
  assert.deepEqual(parseQueryHistoryPage(page),page);
});

test("accepts canceled history records from the proposed schema contract",()=>{
  const canceled={...entry,outcome:"CANCELED",rowCount:null};
  assert.deepEqual(parseQueryHistoryPage({...page,items:[canceled]}).items,[canceled]);
});

test("rejects malformed entries, duplicate IDs, and inconsistent cursors",()=>{
  assert.throws(()=>parseQueryHistoryPage({...page,hasMore:true}),/pagination metadata/);
  assert.throws(()=>parseQueryHistoryPage({...page,items:[entry,entry]}),/invalid query history entry/);
  assert.throws(()=>parseQueryHistoryPage({...page,items:[{...entry,rowCount:-1}]}),/invalid query history entry/);
  assert.throws(()=>parseQueryHistoryPage({...page,items:[{...entry,createdAt:"not-a-date"}]}),/invalid query history entry/);
  assert.throws(()=>parseQueryHistoryPage({...page,nextCursor:"bad cursor",hasMore:true}),/pagination metadata/);
});

test("requests the first page with bearer and workspace headers and no-store caching",async()=>{
  let url="";
  let init:RequestInit|undefined;
  const fetcher:typeof fetch=async(input,requestInit)=>{
    url=String(input);
    init=requestInit;
    return new Response(JSON.stringify(page),{status:200,headers:{"Content-Type":"application/json"}});
  };
  const result=await fetchQueryHistoryPage({apiBaseUrl:"https://api.example.test/",token,workspaceId},fetcher);
  const parsed=new URL(url);
  assert.deepEqual(result,page);
  assert.equal(parsed.pathname,"/api/sql/history");
  assert.equal(parsed.searchParams.get("limit"),"25");
  assert.equal(parsed.searchParams.get("cursor"),null);
  assert.equal(parsed.searchParams.get("outcome"),null);
  assert.equal(init?.method,"GET");
  assert.equal(init?.cache,"no-store");
  const headers=init?.headers as Record<string,string>;
  assert.equal(headers.Authorization,"Bearer "+token);
  assert.equal(headers["X-NeuroSQL-Workspace-Id"],workspaceId);
});

test("sends the selected outcome filter with the history request",async()=>{
  let url="";
  const fetcher:typeof fetch=async(input)=>{
    url=String(input);
    return new Response(JSON.stringify(page),{status:200});
  };
  await fetchQueryHistoryPage({
    apiBaseUrl:"https://api.example.test",
    token,
    workspaceId,
    outcome:"FAILED",
  },fetcher);
  assert.equal(new URL(url).searchParams.get("outcome"),"FAILED");
});

test("passes an opaque cursor as a query parameter for the next page",async()=>{
  let url="";
  const fetcher:typeof fetch=async(input)=>{
    url=String(input);
    return new Response(JSON.stringify(page),{status:200});
  };
  await fetchQueryHistoryPage({apiBaseUrl:"https://api.example.test",token,workspaceId,cursor:"eyJjdXJzb3IifQ"},fetcher);
  assert.equal(new URL(url).searchParams.get("cursor"),"eyJjdXJzb3IifQ");
});

test("rejects invalid credentials, workspace, and outcome before network access",async()=>{
  let calls=0;
  const fetcher:typeof fetch=async()=>{calls+=1;return new Response(JSON.stringify(page),{status:200});};
  await assert.rejects(fetchQueryHistoryPage({apiBaseUrl:"https://api.example.test",token:"bad",workspaceId},fetcher),/valid NeuroSQL session/);
  await assert.rejects(fetchQueryHistoryPage({apiBaseUrl:"https://api.example.test",token,workspaceId:"bad"},fetcher),/valid workspace/);
  await assert.rejects(fetchQueryHistoryPage({
    apiBaseUrl:"https://api.example.test",token,workspaceId,outcome:"DELETE" as "ALL",
  },fetcher),/valid query-history outcome filter/);
  assert.equal(calls,0);
});

test("maps API authorization and feature-gate errors to safe messages",async()=>{
  for(const [status,message] of [
    [401,/Sign in again/],
    [403,/cannot view history/],
    [404,/not enabled/],
    [429,/Too many history requests/],
  ] as const){
    const fetcher:typeof fetch=async()=>new Response("{}",{status});
    await assert.rejects(fetchQueryHistoryPage({apiBaseUrl:"https://api.example.test",token,workspaceId},fetcher),message);
  }
});
