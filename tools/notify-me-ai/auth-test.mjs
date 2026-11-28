import assert from 'node:assert/strict';
import worker from './worker.mjs';
import {database,clientId,randomKey,jwk,signed,post} from './auth-fixtures.mjs';
const {sql,DB}=database();
let fetches=0;
globalThis.fetch=async(url,opts)=>{assert.equal(url,'https://www.googleapis.com/oauth2/v3/certs');assert.equal(opts.redirect,'manual');fetches++;return Response.json({keys:[jwk]})};
const env={AUTH_LIMIT:{async limit(){return {success:true}}},BETA_TOKEN:'test-admin-secret-only-'.repeat(3),OWNER_GOOGLE_EMAIL:'owner@gmail.com',GOOGLE_CLIENT_ID:clientId,DB,AI:{}};
const now=Math.floor(Date.now()/1000);
const claims={iss:'https://accounts.google.com',aud:env.GOOGLE_CLIENT_ID,sub:'owner-sub',email:env.OWNER_GOOGLE_EMAIL,email_verified:true,iat:now,exp:now+3600};
const b64=x=>Buffer.from(x).toString('base64url');
const jwt=(p=claims,h)=>signed(p,h);
const login=(t,e=env,revokeKey=randomKey())=>post(worker,'google',{idToken:t,revokeKey},e);
async function health(t,e=env){return worker.fetch(new Request('https://worker.example/v1/health',{headers:{Authorization:'Bearer '+t}}),e)}
const test=async(n,f)=>{await f();console.log('PASS',n)};
let session; const valid=await jwt();
await test('owner login yields session, health succeeds, admin token unchanged',async()=>{let r=await login(valid);assert.equal(r.status,200);session=await r.json();assert.equal(session.sub,'owner-sub');assert.ok(session.expires>now);assert.equal((await health(session.token)).status,200);assert.equal((await health(env.BETA_TOKEN)).status,200)});
await test('second Google account allowed without owner config',async()=>{const r=await login(await jwt({...claims,sub:'other-sub',email:'other@gmail.com'}),{...env,OWNER_GOOGLE_EMAIL:''});assert.equal(r.status,200);assert.equal((await r.json()).sub,'other-sub');assert.equal(fetches,1)});
await test('bad signature, algorithm, issuer, audience, expiry rejected',async()=>{const bad=valid.split('.');bad[2]=b64(new Uint8Array(256));for(const t of [bad.join('.'),await jwt(claims,{alg:'none',kid:'key1'}),await jwt({...claims,iss:'https://example.com'}),await jwt({...claims,aud:'wrong'}),await jwt({...claims,exp:now-1})])assert.equal((await login(t)).status,401)});
await test('identity is verified sub, not email or owner setting',async()=>{assert.equal((await login(await jwt({...claims,email_verified:false}),{...env,OWNER_GOOGLE_EMAIL:''})).status,200);assert.equal((await login(await jwt({...claims,sub:''}))).status,401)});
await test('tampered session and admin rotation rejected, owner setting irrelevant',async()=>{const parts=session.token.split('.');parts[1]=b64(JSON.stringify({sub:'other',aud:'summit-services',exp:now+500000}));assert.equal((await health(parts.join('.'))).status,401);assert.equal((await health(session.token,{...env,OWNER_GOOGLE_EMAIL:'other@gmail.com'})).status,200);assert.equal((await health(session.token,{...env,BETA_TOKEN:'new-secret-'.repeat(6)})).status,401)});
await test('expired session rejected even with valid MAC',async()=>{const k=await crypto.subtle.importKey('raw',new TextEncoder().encode(env.BETA_TOKEN),{name:'HMAC',hash:'SHA-256'},false,['sign']);const data='nm2.'+b64(JSON.stringify({sub:'owner-sub',aud:'summit-services',exp:now-10}));const mac=b64(await crypto.subtle.sign('HMAC',k,new TextEncoder().encode('summit-session-v2:'+data)));assert.equal((await health(data+'.'+mac)).status,401)});
await test('legacy session rejected, wrong audience session rejected',async()=>{assert.equal((await health(session.token.replace('nm3.','nm1.'))).status,401);const k=await crypto.subtle.importKey('raw',new TextEncoder().encode(env.BETA_TOKEN),{name:'HMAC',hash:'SHA-256'},false,['sign']);const data='nm2.'+b64(JSON.stringify({sub:'owner-sub',aud:'wrong-service',exp:now+100}));const mac=b64(await crypto.subtle.sign('HMAC',k,new TextEncoder().encode('summit-session-v2:'+data)));assert.equal((await health(data+'.'+mac)).status,401);assert.equal(fetches,1)});

await test('retry after lost exchange response returns the same family; replay on another device rejected',async()=>{
 const revokeKey=randomKey(),idToken=await jwt();
 const first=await (await login(idToken,env,revokeKey)).json();
 assert.equal((await (await login(idToken,env,revokeKey)).json()).refresh, first.refresh);
 assert.equal((await login(idToken)).status,401);
});
await test('refresh, offline retry and logout affect only the selected session',async()=>{
 const revokeKey=randomKey(), id=await jwt();
 const a=await (await login(id,env,revokeKey)).json();
 const other=await (await login(await jwt())).json();
 const forged=a.token.split('.');forged[2]=String(Number(forged[2])+3600);
 assert.equal((await health(forged.join('.'))).status,401);
 const unrelated=await post(worker,'logout',{revokeKey:randomKey(),sub:claims.sub,token:a.token},env);
 assert.deepEqual(await unrelated.json(),{revoked:false});assert.equal((await health(a.token)).status,200);
 const sid=a.token.split('.')[1];
 assert.ok(sql.prepare('SELECT 1 FROM summit_sessions WHERE sid=?').get(sid));
 const refresh=()=>post(worker,'refresh',{refresh:a.refresh},env);
 assert.equal((await health(a.refresh)).status,401);
 assert.equal((await post(worker,'refresh',{refresh:revokeKey},env)).status,401);
 const clock=Date.now;
 try {
  Date.now=()=>clock()+3601000;
  assert.deepEqual(await (await health(a.token)).json(),{error:'session_expired'});
  const renewed=await (await refresh()).json();
  assert.equal(renewed.sub,claims.sub);assert.equal((await health(renewed.token)).status,200);
 } finally {Date.now=clock;}
 assert.equal((await post(worker,'logout',{revokeKey},env)).status,200);
 assert.equal((await post(worker,'logout',{revokeKey},env)).status,200);
 assert.equal((await health(a.token)).status,401);assert.equal((await refresh()).status,401);
 assert.equal((await login(id,env,revokeKey)).status,401);
 assert.equal((await health(other.token)).status,200);
 sql.prepare('UPDATE summit_sessions SET expires=0 WHERE sid=?').run(other.token.split('.')[1]);
 assert.equal((await post(worker,'refresh',{refresh:other.refresh},env)).status,401);
});
await test('diagnostic codes distinguish provider, audience, expiry, signature and server config',async()=>{
 for(const [t,e,code] of [[null,env,'google_token_missing'],[valid,{...env,GOOGLE_CLIENT_ID:''},'google_client_config'],
   [valid,{...env,GOOGLE_CLIENT_ID:'bad'},'google_client_config'],[valid,{...env,DB:null},'server_config'],
   [await jwt({...claims,aud:'wrong'}),env,'google_audience'],[await jwt({...claims,exp:now-10}),env,'google_expired'],
   [await jwt({...claims,iss:'wrong'}),env,'google_invalid']]) assert.equal((await (await login(t,e)).json()).error,code);
 const clock=Date.now, savedFetch=fetch;
 try {
  Date.now=()=>clock()+360000;
  globalThis.fetch=async()=>{throw Error('offline')};
  const r=await login(valid);assert.equal(r.status,503);assert.deepEqual(await r.json(),{error:'google_keys_unavailable'});
  Date.now=()=>clock()+400000;
  globalThis.fetch=async(url,opts)=>{
   assert.equal(url,'https://www.googleapis.com/oauth2/v3/certs');assert.equal(opts.redirect,'manual');
   return new Response(null,{status:302,headers:{Location:'https://other.example.invalid/keys'}});
  };
  assert.equal((await (await login(valid)).json()).error,'google_keys_unavailable');
  Date.now=()=>clock()+440000;globalThis.fetch=savedFetch;
  assert.equal((await login(await jwt())).status,200);
 } finally {Date.now=clock;globalThis.fetch=savedFetch;}
});
await test('repeat additive migration preserves legacy and account rows',async()=>{
 sql.exec("INSERT INTO ai_usage VALUES('fixture',4); INSERT INTO notify_ai_usage_v2 VALUES('google:accountA','fixture',2)");
 const {readFileSync}=await import('node:fs');
 sql.exec(`INSERT INTO watches(id,url,title,kind,selector,latest,latest_url,checked_at,next_check)
   VALUES('w','https://example.com','legacy','chapter','.c a',1,'https://example.com/1',0,1);
   INSERT INTO events(id,watch_id,revision,number,url,created_at) VALUES('e','w',1,1,'https://example.com/1',0);
   INSERT INTO notify_watches_v2(id,account_id,url,title,kind,selector,latest,latest_url,checked_at,next_check)
   VALUES('w2','google:A','https://example.com','owned','chapter','.c a',1,'https://example.com/1',0,1);
   INSERT INTO notify_events_v2(id,account_id,watch_id,revision,number,url,created_at)
   VALUES('e2','google:A','w2',1,1,'https://example.com/1',0)`);
 const tables=['watches','events','notify_watches_v2','notify_events_v2','ai_usage','notify_ai_usage_v2'];
 const snapshot=()=>tables.map(table=>sql.prepare('SELECT * FROM '+table).all());
 const expected=snapshot();
 const before=sql.prepare('SELECT count(*) AS n FROM summit_sessions').get().n;
 sql.exec(readFileSync(new URL('./migration-unified-auth.sql',import.meta.url),'utf8'));
 assert.equal(sql.prepare("SELECT count FROM ai_usage WHERE day='fixture'").get().count,4);
 assert.equal(sql.prepare("SELECT count FROM notify_ai_usage_v2 WHERE day='fixture'").get().count,2);
 assert.equal(sql.prepare('SELECT count(*) AS n FROM summit_sessions').get().n,before);assert.deepEqual(snapshot(),expected);
});
await test('rate-limited auth stops before credential exchange; absent binding fails closed',async()=>{
 const limited={...env,AUTH_LIMIT:{async limit(){return {success:false}}}};
 const rate=await login(valid,limited);assert.equal(rate.status,429);assert.equal((await rate.json()).error,'auth_rate_limit');
 const missing=await login(valid,{...env,AUTH_LIMIT:null});assert.equal(missing.status,503);
 assert.equal((await missing.json()).error,'auth_limit_config');
});
console.log('12 authentication test groups passed.');
