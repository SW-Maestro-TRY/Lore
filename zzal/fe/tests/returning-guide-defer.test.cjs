const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');const vm=require('node:vm');const ts=require('typescript');
const compile=file=>ts.transpileModule(fs.readFileSync(file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
const load=(rel,context={})=>{const exports={};vm.runInNewContext(compile(path.resolve(__dirname,rel)),{exports,require:()=>({}),Date,String,...context});return exports;};
const state=load('../tamagotchi/announcements/returningGuideState.ts');
const memory=()=>{const map=new Map();return {map,getItem:k=>map.has(k)?map.get(k):null,setItem:(k,v)=>map.set(k,String(v))};};
const key=state.returningGuideKey(65,61);

test('"나중에 할게요" hides the guide for the rest of the same local day only',()=>{
 const storage=memory(),morning=new Date(2026,9,27,9,0),night=new Date(2026,9,27,23,59),tomorrow=new Date(2026,9,28,0,1);
 assert.equal(state.returningGuideLaterToday(storage,key,morning),false);
 assert.equal(state.deferReturningGuide(storage,key,morning),true);
 assert.equal(storage.map.get(`${key}:later`),'2026-10-27');
 assert.equal(state.returningGuideLaterToday(storage,key,night),true);
 assert.equal(state.returningGuideLaterToday(storage,key,tomorrow),false);
});
test('later is per account and pet, and versioned with the guide key',()=>{
 const storage=memory(),now=new Date(2026,9,27,12);
 state.deferReturningGuide(storage,key,now);
 assert.equal(state.returningGuideLaterToday(storage,state.returningGuideKey(65,62),now),false);
 assert.equal(state.returningGuideLaterToday(storage,state.returningGuideKey(66,61),now),false);
 assert.ok(key.includes(state.RETURNING_GUIDE_VERSION));
});
test('blocked or missing storage never throws and never claims a deferral',()=>{
 const broken={getItem:()=>{throw Error('denied')},setItem:()=>{throw Error('quota')}};
 assert.equal(state.deferReturningGuide(broken,key),false);
 assert.equal(state.returningGuideLaterToday(broken,key),false);
 assert.equal(state.returningGuideLaterToday(null,key),false);
 assert.equal(state.deferReturningGuide(undefined,key),false);
 assert.equal(state.returningGuideLaterToday(memory(),''),false);
});
test('later copy exists in every locale',()=>{
 for(const locale of ['ko','en','ja'])assert.ok(state.returningCopy[locale].later.length>0);
 assert.equal(state.returningCopy.ko.later,'나중에 할게요');
});
test('settings_save failure carries server code and http status inside the analytics allowlist',()=>{
 const events=[];const api={exports:{}};
 vm.runInNewContext(compile(path.resolve(__dirname,'../tamagotchi/focused-room/interactionMetrics.ts')),{...api,require:()=>({track:(name,props)=>events.push(JSON.parse(JSON.stringify({name,props})))})});
 const metrics=api.exports.interactionMetrics({pet:null},false,'settings');
 metrics.result('settings_save','failure',{code:'ZZAL_TRAVELING',reason:'http_409'});
 metrics.result('settings_save','failure',{code:null,reason:'network'});
 metrics.result('settings_save','success');
 assert.deepEqual(events[0],{name:'zzal_action_result',props:{action:'settings_save',outcome:'failure',user_action:false,code:'ZZAL_TRAVELING',reason:'http_409'}});
 assert.deepEqual(events[1].props,{action:'settings_save',outcome:'failure',user_action:false,reason:'network'});
 assert.deepEqual(events[2].props,{action:'settings_save',outcome:'success',user_action:false});
 const allow=fs.readFileSync(path.resolve(__dirname,'../../../common/be/src/main/java/com/lore/common/analytics/AnalyticsService.java'),'utf8').match(/ALLOWED_PROP_KEYS = Set\.of\(([\s\S]*?)\);/)[1];
 for(const k of Object.keys(events[0].props))assert.ok(allow.includes(`"${k}"`),`${k} not in allowlist`);
});
