const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
const {webcrypto}=require('node:crypto');
const root=path.resolve(__dirname,'../../..');
function storage(){const values=new Map();return {get length(){return values.size},key:i=>[...values.keys()][i]??null,getItem:k=>values.get(k)??null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k),values};}
function harness(local=storage(),send){
 const pending=[],listeners={}; const session=storage();
 const context={exports:{},crypto:webcrypto,URL,URLSearchParams,Blob,Uint8Array,Date,Set,Map,JSON,process:{env:{NODE_ENV:'production'}},console,
  setTimeout:fn=>{pending.push(fn);return pending.length},clearTimeout:()=>{},
  require:id=>id.includes('zzalAttribution')?{createAttributionReader:()=>()=>({})}:{API_BASE:''},
  window:{localStorage:local,sessionStorage:session,location:{pathname:'/zzal',search:'',href:'https://example.test/zzal',origin:'https://example.test',hostname:'example.test'},addEventListener:(name,fn)=>listeners[name]=fn},
  document:{cookie:'',referrer:'',documentElement:{lang:'ko'},visibilityState:'visible',addEventListener:(name,fn)=>listeners[name]=fn},
  navigator:{sendBeacon:()=>true},fetch:async(...args)=>send(...args)};
 vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(root,'common/fe/analytics.ts'),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,context);
 return {api:context.exports,local,pending,listeners,context,events:()=>[...local.values.entries()].filter(([k])=>k.startsWith('lore_event_v2:')).map(([,v])=>JSON.parse(v))};
}
const ack=body=>({ok:true,json:async()=>({data:{accepted_event_ids:JSON.parse(body).events.map(e=>e.event_id)}})});
test('network failure retains immutable IDs; new tab recovers and ACK deletes',async()=>{
 const local=storage(); let attempts=[];
 const first=harness(local,async(url,{body})=>{attempts.push(JSON.parse(body));throw Error('offline')});
 await first.api.trackConversion('zzal_user_action',{action:'pet',user_action:true});
 const original=first.events()[0]; assert.ok(original.event_id);assert.equal(original.schema_version,2);
 const second=harness(local,async(url,{body})=>{attempts.push(JSON.parse(body));return ack(body)});
 await second.api.trackConversion('zzal_ui_shown',{action:'room'});
 assert.equal(second.events().length,0);assert.equal(attempts[1].events[0].event_id,original.event_id);assert.equal(attempts[1].events[0].ts,original.ts);
});
test('HTTP failure and success without persistence ACK never discard events',async()=>{
 for(const response of [{ok:false},{ok:true,json:async()=>({})}]){
  const h=harness(undefined,async()=>response);await h.api.trackConversion('zzal_user_action',{action:'wake'});assert.equal(h.events().length,1);
 }
});
test('partial ACK removes only acknowledged sent IDs; unrelated ACK cannot delete',async()=>{
 const h=harness(undefined,async(url,{body})=>({ok:true,json:async()=>({data:{accepted_event_ids:[JSON.parse(body).events[0].event_id,'not-sent']}})}));
 h.api.track('zzal_user_action',{action:'pet'});await h.api.trackConversion('zzal_user_action',{action:'wake'});assert.equal(h.events().length,1);assert.equal(h.events()[0].props.action,'wake');
});
test('beacon browser acceptance retains durable events for next acknowledged delivery',async()=>{
 const h=harness(undefined,async()=>{throw Error('unexpected')});h.api.track('zzal_user_action',{action:'sleep'});h.listeners.pagehide();await Promise.resolve();assert.equal(h.events().length,1);
});
test('sample context remains separate and free text is excluded before persistence',async()=>{
 const h=harness(undefined,async()=>({ok:false}));h.api.track('zzal_dex_share',{action:'start',email:'secret@example.test',text:'private chat',persona:'secret'},{context:'sample',source_screen:'album'});
 const e=h.events()[0];assert.equal(e.context,'sample');assert.equal(e.source_screen,'album');assert.deepEqual(Object.keys(e.props),['action']);
});
test('bounded backlog records overflow explicitly',async()=>{
 const h=harness(undefined,async()=>({ok:false}));for(let i=0;i<505;i++)h.api.track('zzal_user_action',{action:'pet'});
 assert.equal(h.events().length,500);assert.equal(h.local.getItem('lore_event_overflow_count'),'5');
});
test('HTTP review origins without randomUUID still generate v4 identities',async()=>{
 const h=harness(undefined,async()=>({ok:false}));h.context.crypto={getRandomValues:a=>webcrypto.getRandomValues(a)};
 await h.api.trackConversion('zzal_user_action',{action:'pet'});
 assert.match(h.events()[0].event_id,/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/);
});
test('permanent server rejection drains poison event and records collection gap',async()=>{
 const h=harness(undefined,async(url,{body})=>({ok:true,json:async()=>({data:{accepted_event_ids:[],rejected_event_ids:[JSON.parse(body).events[0].event_id]}})}));
 await h.api.trackConversion('invalid',{action:'pet'});assert.equal(h.events().length,0);assert.equal(h.local.getItem('lore_event_rejected_count'),'1');
});
test('offline A event preserves capture identity after B login; new event is B',async()=>{
 const local=storage();const first=harness(local,async()=>({ok:false}));first.api.setTelemetryActor('authenticated',101);await first.api.trackConversion('zzal_user_action',{action:'pet'});
 const second=harness(local,async()=>({ok:false}));second.api.setTelemetryActor('authenticated',202);await second.api.trackConversion('zzal_user_action',{action:'wake'});
 const events=second.events();assert.equal(events.find(e=>e.props.action==='pet').actor_id,101);assert.equal(events.find(e=>e.props.action==='wake').actor_id,202);assert.ok(events.every(e=>e.actor_state==='authenticated'));
 second.api.setTelemetryActor('anonymous');second.api.track('zzal_ui_shown',{action:'room'});assert.equal(second.events().at(-1).actor_state,'anonymous');assert.equal(second.events().at(-1).actor_id,undefined);
});
test('unverified initial state never borrows current identity at flush time',async()=>{
 const h=harness(undefined,async()=>({ok:false}));h.api.track('zzal_ui_shown',{action:'room'});h.api.setTelemetryActor('authenticated',202);await h.api.trackConversion('zzal_user_action',{action:'pet'});
 assert.equal(h.events()[0].actor_state,'unknown');assert.equal(h.events()[0].actor_id,undefined);
});
