const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');const vm=require('node:vm');const ts=require('typescript');
const base=path.resolve(__dirname,'../tamagotchi/focused-room');
const compile=file=>ts.transpileModule(fs.readFileSync(file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
function hook(overrides={},sample=false){
 const events=[];const api={exports:{}};
 vm.runInNewContext(compile(path.join(base,'interactionMetrics.ts')),{...api,require:()=>({track:(name,props,context)=>events.push({name,...props,...context})})});
 const live={petId:1,pet:{petId:1,phase:'ALIVE',firstIntro:{version:4,key:'COMPLETE',completed:true},clock:{sleeping:false}},game:{playing:true},doCare:async()=>({ok:true}),doRest:async()=>({ok:true}),startPlay:async()=>null,pickSide:async()=>({result:null,error:'rejected'}),tutorialSeen:async()=>({ok:true}),finishTutorial:async()=>({ok:true}),...overrides};
 const exports={};
 vm.runInNewContext(compile(path.join(base,'useFocusedExperience.ts')),{exports,document:{hidden:true},window:{},performance:{now:()=>0},setTimeout,clearTimeout,setInterval,clearInterval,
 require:id=>{
  if(id==='react')return {useRef:v=>({current:v}),useState:v=>[typeof v==='function'?v():v,()=>{}],useEffect:()=>{}};
  if(id==='./interactionMetrics')return api.exports;
  if(id==='./focusedCopy')return {focusedCopy:()=>({error:'failed'}),fallbackIntroLine:()=>'',endingFallbackLine:()=>'',focusedGameLine:()=>''};
  if(id==='./useTutorialEnding')return {useTutorialEnding:()=>({storyPhase:0,finishBeat:0}),ENDING_STORY:{review:3,invite:4}};
  if(id==='./useFocusedStageSpeech')return {useFocusedStageSpeech:()=>({line:'',source:'test'})};
  return {};
 }});
 return {x:exports.useFocusedExperience(live,'ko',sample),events};
}
test('actual focused care hook separates direct attempt from receipt and marks uncertain',async()=>{
 const {x,events}=hook({doCare:async()=>({ok:false,uncertain:true})});await x.care('PET');
 assert.deepEqual(events.map(e=>[e.name,e.action,e.outcome,e.user_action]),[['zzal_user_action','pet','attempt',true],['zzal_action_result','pet','uncertain',false]]);
});
test('sleep and wake are direct actions without any chat prerequisite',async()=>{
 for(const sleeping of [false,true]){
  const {x,events}=hook({pet:{clock:{sleeping},firstIntro:{completed:true}}});await x.rest();
  assert.equal(events[0].action,sleeping?'wake':'sleep');assert.equal(events[1].outcome,'success');
 }
});
test('sample and tutorial hooks preserve context; render alone never counts as direct action',async()=>{
 const sample=hook({},true);assert.equal(sample.events.length,0);await sample.x.care('PET');assert.ok(sample.events.every(e=>e.context==='sample'));
 const tutorial=hook({pet:{firstIntro:{version:4,key:'FEED',completed:false}}});await tutorial.x.care('FEED');assert.ok(tutorial.events.every(e=>e.context==='tutorial'));
});
test('game failed receipt and thrown error never fabricate success',async()=>{
 const guess=hook();await guess.x.choose('LEFT');assert.equal(guess.events.at(-1).outcome,'failure');
 const start=hook({startPlay:async()=>{throw Error('lost response')}});await start.x.startGame();assert.equal(start.events.at(-1).outcome,'uncertain');
});
test('server NO_OP care receipt remains distinct from successful care',async()=>{
 const {x,events}=hook({doCare:async()=>({ok:true,careReceipt:{status:'NO_OP'}})});await x.care('BATH');assert.equal(events.at(-1).outcome,'no_op');
});
test('room navigation is a separate dimension and tutorial completion is explicitly observed',()=>{
 const events=[];const exports={};
 vm.runInNewContext(compile(path.join(base,'focusedAnalytics.ts')),{exports,require:id=>id.includes('firstIntro')?{FIRST_INTRO_V4_KEYS:['FEED','REVIEW_SEEN']}:{ztrack:()=>{}},Map,Set,Date});
 const tracker=exports.createFocusedMetrics({petId:1001,emit:(name,props)=>events.push({name,...props}),storage:null,now:()=>100});tracker.enter('reload');tracker.observeIntro({version:4,key:'REVIEW_SEEN'});tracker.observeIntro({version:4,key:'COMPLETE',completed:true});
 assert.equal(events[0].type,'real');assert.equal(events[0].navigation,'reload');assert.equal(events.at(-1).name,'zzal_tutorial_completion_observed');
});
