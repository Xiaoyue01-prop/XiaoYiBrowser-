// Test-only helpers. The HTTP Worker remains the issuer of every usable credential.
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
export function database() {
 const sql = new DatabaseSync(':memory:');
 for (const name of ['schema.sql','migration-v2.sql','migration-unified-auth.sql'])
  sql.exec(readFileSync(new URL(name,import.meta.url),'utf8'));
 sql.exec('PRAGMA foreign_keys=ON');
 function prepare(q,args=[]) {
  return {bind(...a){return prepare(q,a)},async first(){return sql.prepare(q).get(...args)||null},
   async all(){return {results:sql.prepare(q).all(...args)}},async run(){return sql.prepare(q).run(...args)}};
 }
 const DB={prepare,async batch(stmts){
  sql.exec('BEGIN');
  try {for(const s of stmts) await s.run(); sql.exec('COMMIT');}
  catch(e){sql.exec('ROLLBACK'); throw e;}
 }};
 return {sql,DB};
}
export const clientId='100-test.apps.googleusercontent.com';
export const randomKey=()=>Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url');
export const key=await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,
 publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
export const jwk={...await crypto.subtle.exportKey('jwk',key.publicKey),kid:'key1',alg:'RS256',use:'sig'};
const b64=x=>Buffer.from(x).toString('base64url');
export async function signed(claims,h={alg:'RS256',kid:'key1'}) {
 const data=b64(JSON.stringify(h))+'.'+b64(JSON.stringify({nonce:randomKey(),...claims}));
 return data+'.'+b64(await crypto.subtle.sign('RSASSA-PKCS1-v1_5',key.privateKey,new TextEncoder().encode(data)));
}
export function post(worker,path,body,env) {
 return worker.fetch(new Request('https://worker.example/v1/auth/'+path,
  {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}),env);
}
