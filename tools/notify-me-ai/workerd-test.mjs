import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFileSync} from 'node:fs';
import {clientId,jwk,randomKey,signed} from './auth-fixtures.mjs';
const {Miniflare} = createRequire(import.meta.url)('miniflare');
const read = name => readFileSync(new URL(name,import.meta.url),'utf8');
const instance = new Miniflare({
 compatibilityDate:'2026-07-01',
 modules:[
  {type:'ESModule',path:'entry.mjs',contents:`
    import worker from './worker.mjs';
    export default { fetch(req,env) {
      const AI={async run(){return {response:{group:0,kind:'chapter'}}}};
      return worker.fetch(req,{...env,AI});
    } };`},
  {type:'ESModule',path:'worker.mjs',contents:read('worker.mjs')}
 ],
 d1Databases:['DB'],
 ratelimits:{AUTH_LIMIT:{namespace_id:'3303',simple:{limit:60,period:60}}},
 bindings:{GOOGLE_CLIENT_ID:clientId,BETA_TOKEN:randomKey(),ALLOWED_HOSTS:'example.com'},
 outboundService: req => {
  if (req.url==='https://www.googleapis.com/oauth2/v3/certs') return Response.json({keys:[jwk]});
  assert.equal(req.url,'https://example.com/book');
  return new Response('<div class="chapters"><a href="/c7">Chương 7</a></div>',{headers:{'Content-Type':'text/html'}});
 }
});
async function api(path,body,token) {
 return instance.dispatchFetch('https://worker.example/v1/'+path,{
  method:body?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
  ...(body?{body:JSON.stringify(body)}:{})
 });
}
try {
 const db=await instance.getD1Database('DB');
 for(const file of ['schema.sql','migration-v2.sql','migration-unified-auth.sql','migration-unified-auth.sql']){
  const statements=read(file).replace(/^--.*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean);
  await db.batch(statements.map(s=>db.prepare(s)));
 }
 async function login(sub){
  const now=Math.floor(Date.now()/1000),revokeKey=randomKey();
  const idToken=await signed({sub,iss:'accounts.google.com',aud:clientId,iat:now,exp:now+3600});
  const r=await api('auth/google',{idToken,revokeKey});
  if (!r.ok) throw Error("auth fixture: "+(await r.json()).error);return {...await r.json(),revokeKey};
 }
 const a=await login('A'), b=await login('B');
 const payload={url:'https://example.com/book',title:'Book',groups:[
  {selector:'.chapters a',links:[{label:'Chương 7',url:'https://example.com/c7'}]}]};
 const analyze=await api('analyze',payload,a.token);
 assert.equal(analyze.status,200);
 const {ticket}=await analyze.json();
 assert.equal((await api('watches',{ticket},b.token)).status,403);
 const saved=await api('watches',{ticket},a.token);assert.equal(saved.status,201);
 assert.equal((await saved.json()).watch.latest,7);
 assert.deepEqual((await (await api('watches',null,b.token)).json()).watches,[]);
 const renewed=await api('auth/refresh',{refresh:a.refresh});assert.equal(renewed.status,200);
 assert.equal((await (await api('auth/logout',{revokeKey:a.revokeKey})).json()).revoked,true);
 assert.equal((await api('account',null,(await renewed.json()).token)).status,401);
 assert.equal((await api('auth/refresh',{refresh:a.refresh})).status,401);
 assert.equal((await api('account',null,b.token)).status,200);
 console.log('PASS workerd: signed identity, D1 migrations, HTMLRewriter, preview, register, isolation, refresh and revoke (fixture AI).');
} finally { await instance.dispose(); }
