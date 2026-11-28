import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const js=readFileSync(new URL('../../app/src/main/assets/notify_me_extract.js',import.meta.url),'utf8');
const parent={id:'chapters',classList:['chapter-list'],parentElement:null};
const anchor=(label,href,p=parent)=>({textContent:label,classList:[],parentElement:p,getAttribute:()=>href});
function run({url='https://example.com/book',password=false,links=[]}={}){
 const location=new URL(url);
 const document={title:'Book',querySelector:s=>s==='input[type="password"]'?(password?{}:null):s==='h1'?{textContent:'A book'}:s.includes('og:type')?{content:'book'}:null,querySelectorAll:()=>links};
 return JSON.parse(JSON.stringify(vm.runInNewContext(js,{URL,location,document})));
}
const p=run({links:[anchor('Chương 128','/c128'),anchor('Chương 129','/c129'),anchor('Chương 200','https://other.example/c200'),anchor('Chương 999','/c999?token=secret'),anchor('Login','/login')]});
assert.equal(p.title,'A book'); assert.equal(p.groups[0].links[0].label,'Chương 129'); assert.equal(p.groups[0].links.length,2); console.log('PASS sorted candidates, cross-origin and query excluded');
assert.equal(run({password:true}).error,'private_or_unsupported_page'); assert.equal(run({url:'https://example.com/book?token=secret'}).error,'private_or_unsupported_page'); assert.equal(run({url:'http://example.com/book'}).error,'private_or_unsupported_page'); console.log('PASS blocked password / query / HTTP');
assert.equal(run({links:[anchor('Advertisement','/ad')]}).error,'no_candidate_links'); console.log('PASS irrelevant links excluded');
