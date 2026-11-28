import assert from 'node:assert/strict';
import worker from './worker.mjs';
const key=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
const jwk={...await crypto.subtle.exportKey('jwk',key.publicKey),kid:'key1',alg:'RS256',use:'sig'};
let fetches=0;
globalThis.fetch=async(url,opts)=>{assert.equal(url,'https://www.googleapis.com/oauth2/v3/certs');assert.equal(opts.redirect,'error');fetches++;return Response.json({keys:[jwk]})};
const env={BETA_TOKEN:'test-admin-secret-only-'.repeat(3),OWNER_GOOGLE_EMAIL:'owner@gmail.com',GOOGLE_CLIENT_ID:'test-client',DB:{prepare(){return {async first(){return {n:0}}}}},AI:{}};
const now=Math.floor(Date.now()/1000);
const claims={iss:'https://accounts.google.com',aud:env.GOOGLE_CLIENT_ID,sub:'owner-sub',email:env.OWNER_GOOGLE_EMAIL,email_verified:true,iat:now,exp:now+3600};
const b64=x=>Buffer.from(x).toString('base64url');
async function jwt(p=claims,h={alg:'RS256',kid:'key1'}){const data=b64(JSON.stringify(h))+'.'+b64(JSON.stringify(p));return data+'.'+b64(await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key.privateKey,new TextEncoder().encode(data)))}
async function login(t,e=env){return worker.fetch(new Request('https://worker.example/v1/auth/google',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({idToken:t})}),e)}
async function health(t,e=env){return worker.fetch(new Request('https://worker.example/v1/health',{headers:{Authorization:'Bearer '+t}}),e)}
const test=async(n,f)=>{await f();console.log('PASS',n)};
let session; const valid=await jwt();
await test('owner login yields session, health succeeds, admin token unchanged',async()=>{let r=await login(valid);assert.equal(r.status,200);session=await r.json();assert.equal(session.sub,'owner-sub');assert.ok(session.expires>now);assert.equal((await health(session.token)).status,200);assert.equal((await health(env.BETA_TOKEN)).status,200)});
await test('second Google account allowed without owner config',async()=>{const r=await login(await jwt({...claims,sub:'other-sub',email:'other@gmail.com'}),{...env,OWNER_GOOGLE_EMAIL:''});assert.equal(r.status,200);assert.equal((await r.json()).sub,'other-sub');assert.equal(fetches,1)});
await test('bad signature, algorithm, issuer, audience, expiry rejected',async()=>{const bad=valid.split('.');bad[2]=b64(new Uint8Array(256));for(const t of [bad.join('.'),await jwt(claims,{alg:'none',kid:'key1'}),await jwt({...claims,iss:'https://example.com'}),await jwt({...claims,aud:'wrong'}),await jwt({...claims,exp:now-1})])assert.equal((await login(t)).status,401)});
await test('identity is verified sub, not email or owner setting',async()=>{assert.equal((await login(await jwt({...claims,email_verified:false}),{...env,OWNER_GOOGLE_EMAIL:''})).status,200);assert.equal((await login(await jwt({...claims,sub:''}))).status,401)});
await test('tampered session and admin rotation rejected, owner setting irrelevant',async()=>{const parts=session.token.split('.');parts[1]=b64(JSON.stringify({sub:'other',aud:'summit-services',exp:now+500000}));assert.equal((await health(parts.join('.'))).status,401);assert.equal((await health(session.token,{...env,OWNER_GOOGLE_EMAIL:'other@gmail.com'})).status,200);assert.equal((await health(session.token,{...env,BETA_TOKEN:'new-secret-'.repeat(6)})).status,401)});
await test('expired session rejected even with valid MAC',async()=>{const k=await crypto.subtle.importKey('raw',new TextEncoder().encode(env.BETA_TOKEN),{name:'HMAC',hash:'SHA-256'},false,['sign']);const data='nm2.'+b64(JSON.stringify({sub:'owner-sub',aud:'summit-services',exp:now-10}));const mac=b64(await crypto.subtle.sign('HMAC',k,new TextEncoder().encode('summit-session-v2:'+data)));assert.equal((await health(data+'.'+mac)).status,401)});
await test('legacy session rejected, wrong audience session rejected',async()=>{assert.equal((await health(session.token.replace('nm2.','nm1.'))).status,401);const k=await crypto.subtle.importKey('raw',new TextEncoder().encode(env.BETA_TOKEN),{name:'HMAC',hash:'SHA-256'},false,['sign']);const data='nm2.'+b64(JSON.stringify({sub:'owner-sub',aud:'wrong-service',exp:now+100}));const mac=b64(await crypto.subtle.sign('HMAC',k,new TextEncoder().encode('summit-session-v2:'+data)));assert.equal((await health(data+'.'+mac)).status,401);assert.equal(fetches,1)});
console.log('7 authentication test groups passed (local signed JWT fixtures).');
