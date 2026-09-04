(()=>{'use strict';
const RELEASE='1.0.0-rc2';
const SUPABASE='qjvopzschqukitvudgfz.supabase.co';
function scrub(){
  document.querySelectorAll('.pill').forEach(p=>{if((p.textContent||'').trim()==='FIELDTEST'){p.textContent='RELEASE CANDIDATE';p.classList.remove('warn');p.classList.add('prod')}});
  document.querySelectorAll('p,.muted').forEach(n=>{if((n.textContent||'').includes('Kein produktiv freigegebenes Rechts-/Abrechnungssystem.')) n.textContent='Release Candidate. Rechtliche und abrechnungsrelevante Vorgänge sind bis zur finalen Freigabe prüfpflichtig.'});
}
function backendAlert(message){
  let n=document.getElementById('backendAlert');
  if(!n){n=document.createElement('div');n.id='backendAlert';n.className='offline-block';n.setAttribute('role','alert');const c=document.querySelector('.content')||document.querySelector('.login');if(c)c.prepend(n)}
  if(n)n.innerHTML='<strong>Backend nicht vollständig erreichbar.</strong><br>'+message+' Daten können unvollständig sein.';
}
const priorFetch=window.fetch.bind(window);
window.fetch=async(input,init)=>{
  const r=await priorFetch(input,init);
  try{const u=typeof input==='string'?input:input.url;if(u.includes(SUPABASE)&&u.includes('/rest/v1/')&&!r.ok) backendAlert('HTTP '+r.status+'.');}catch{}
  return r;
};
if('serviceWorker' in navigator){window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js',{scope:'./'}).catch(e=>console.warn('SW registration failed',e)))}
new MutationObserver(scrub).observe(document.documentElement,{childList:true,subtree:true});
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',scrub);else scrub();
window.KALNBACH_RELEASE={version:RELEASE,standalone:true};
})();
