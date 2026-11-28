/* Read only the top document. No storage, cookies, scripts, forms or image data. */
(() => {
  const clean = (s,n) => String(s || '').replace(/\s+/g,' ').trim().slice(0,n);
  const release = /^(?:chương|chapter|chap|tập|episode|ep\.?|第)\s*\d/i;
  const safeName = /^[a-zA-Z_][\w-]{0,59}$/;
  const safeURL = raw => {try {
    const u=new URL(raw,location.href); if(u.protocol!=='https:'||u.origin!==location.origin||u.username||u.password||u.search||u.href.length>1000)return null;
    u.hash=''; return u.href;
  }catch{return null}};
  const url=safeURL(location.href);
  if(!url || document.querySelector('input[type="password"]'))return {error:'private_or_unsupported_page'};
  const groups=new Map();
  for(const a of Array.from(document.querySelectorAll('a[href]')).slice(0,3000)){
    const label=clean(a.textContent,120), link=safeURL(a.getAttribute('href'));
    if(!release.test(label.normalize('NFKC'))||!link||link===url)continue;
    const selectors=new Set();
    for(const c of Array.from(a.classList).filter(x=>safeName.test(x)).slice(0,3))selectors.add('a.'+c);
    let parent=a.parentElement;
    for(let depth=0;parent&&depth<5;depth++,parent=parent.parentElement){
      if(safeName.test(parent.id||''))selectors.add('#'+parent.id+' a');
      for(const c of Array.from(parent.classList).filter(x=>safeName.test(x)).slice(0,3))selectors.add('.'+c+' a');
    }
    for(const selector of selectors){
      if(!groups.has(selector)){if(groups.size>=150)continue;groups.set(selector,[])}
      const items=groups.get(selector);if(!items.some(x=>x.url===link)&&items.length<300)items.push({label,url:link});
    }
  }
  const number=l=>Number((l.label.normalize('NFKC').match(/\d+(?:[.,]\d+)?/)||['0'])[0].replace(',','.'));
  const candidates=Array.from(groups.entries()).sort((a,b)=>b[1].length-a[1].length).slice(0,5).map(([selector,links])=>({selector,links:links.sort((a,b)=>number(b)-number(a)).slice(0,10)}));
  if(!candidates.length)return {error:'no_candidate_links'};
  const meta=key=>clean(document.querySelector('meta[property="'+key+'"]')?.content,140);
  return {url,title:clean(document.querySelector('h1')?.textContent||meta('og:title')||document.title,140)||location.hostname,metadata:clean([meta('og:type'),meta('og:title')].filter(Boolean).join(' | '),300),groups:candidates};
})()
