/* 후기씨앗 오프라인: 화면(HTML)은 인터넷 먼저, 안 되면 저장해 둔 걸로. 글꼴·아이콘 같은 정적 파일은 저장본 먼저 */
const CACHE='hugissiat-v1';
self.addEventListener('install',e=>{e.waitUntil(caches.open(CACHE).then(c=>c.addAll(['./','./index.html','./privacy.html'])).catch(()=>{}));self.skipWaiting()});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
self.addEventListener('fetch',e=>{
  const r=e.request;if(r.method!=='GET')return;
  const u=new URL(r.url);
  if(r.mode==='navigate'||(u.origin===location.origin&&/\.html$|\/$/.test(u.pathname))){
    e.respondWith(fetch(r,{cache:'no-store'}).then(res=>{const c=res.clone();caches.open(CACHE).then(k=>k.put(r.mode==='navigate'?'./':r,c));return res}).catch(()=>caches.match(r.mode==='navigate'?'./':r).then(x=>x||caches.match('./'))));
    return;
  }
  if(/cdn\.jsdelivr\.net|fonts\.(googleapis|gstatic)\.com/.test(u.host)){
    e.respondWith(caches.match(r).then(x=>x||fetch(r).then(res=>{const c=res.clone();caches.open(CACHE).then(k=>k.put(r,c));return res})));
  }
});
