(()=>{'use strict';
const appUrl=()=>location.origin+location.pathname;
const nativeFetch=window.fetch.bind(window);
window.fetch=async(input,init)=>{
  try{
    const raw=typeof input==='string'?input:input.url;
    if(/qjvopzschqukitvudgfz\.supabase\.co\/auth\/v1\/(recover|signup)/.test(raw)){
      const u=new URL(raw);
      if(u.searchParams.has('redirect_to')){
        const current=u.searchParams.get('redirect_to')||'';
        const mode=current.includes('auth=recovery')?'recovery':'verified';
        u.searchParams.set('redirect_to',appUrl()+'?auth='+mode);
        input=typeof input==='string'?u.toString():new Request(u.toString(),input);
      }
    }
  }catch{}
  return nativeFetch(input,init);
};
})();
