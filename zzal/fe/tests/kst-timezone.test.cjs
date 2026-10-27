// lib/kst.ts 시간대 산수 시험 — `node --test zzal/fe/tests/kst-timezone.test.cjs`
//
// 1. 서울: 옛 함수(UTC+9 덧셈)와 새 함수(Intl, zone=Asia/Seoul)가 하루를 5분 간격으로 돌며 전부 같다 → 한국 사용자는 그대로.
// 2. 로스앤젤레스 2026-11-01(서머타임 끝, 25시간 날)·2026-03-08(시작, 23시간 날): 19:00·23:00·07:00·10:00 이 정확한 순간.
// 3. 콜카타(+5:30): 정각이 아닌 오프셋에서도 23:00 이 맞다.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
const root=path.resolve(__dirname,'../../..');

function load(){
  const src=fs.readFileSync(path.join(root,'zzal/fe/lib/kst.ts'),'utf8');
  const out=ts.transpileModule(src,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
  const LIGHT_PHASES=[{key:'MORNING',from:7,to:11},{key:'DAY',from:11,to:17},{key:'SUNSET',from:17,to:19},{key:'NIGHT',from:19,to:7}];
  const context={exports:{},Intl,Date,Math,Number,String,Map,Object,Array,require:()=>({LIGHT_PHASES})};
  vm.runInNewContext(out,context);
  return context.exports;
}

// 옛 kst.ts(origin/main b55648b6)의 식 그대로.
const OLD=(()=>{const K=9*3600_000,D=86_400_000,H=3600_000,M=60_000;
  const dayStart=ms=>Math.floor((ms+K)/D)*D-K;const timeOfDay=ms=>ms-dayStart(ms);
  const at=(ms,h,m=0)=>dayStart(ms)+h*H+m*M;const dayIndex=ms=>Math.floor((ms+K)/D);
  const hm=ms=>{const t=timeOfDay(ms);return{hour:Math.floor(t/H),minute:Math.floor((t%H)/M)}};
  const light=ms=>{const {hour}=hm(ms);return hour>=7&&hour<11?'MORNING':hour>=11&&hour<17?'DAY':hour>=17&&hour<19?'SUNSET':'NIGHT'};
  return{dayStart,timeOfDay,at,dayIndex,hm,light};})();

const iso=s=>Date.parse(s);

test('서울 — 옛 +9h 함수와 새 Intl 함수가 하루 288칸(5분) 전부 같다',()=>{
  const k=load();k.setPetZones(null);
  assert.equal(k.getRulesZone(),'Asia/Seoul');assert.equal(k.zonesAreSeoul(),true);
  for(const day of ['2026-10-26','2026-03-08','2026-11-01','2026-12-31']){
    const base=iso(`${day}T00:00:00+09:00`);
    for(let i=0;i<288;i++){
      const t=base+i*300_000+17_123; // 초·ms 까지 섞는다
      assert.equal(k.dayStart(t),OLD.dayStart(t),`dayStart ${new Date(t).toISOString()}`);
      assert.equal(k.timeOfDay(t),OLD.timeOfDay(t));
      assert.equal(k.dayIndex(t),OLD.dayIndex(t));
      assert.deepEqual({...k.kstHourMinute(t)},OLD.hm(t));
      assert.equal(k.lightPhaseAt(t),OLD.light(t));
      for(const h of [7,10,14,19,23]) assert.equal(k.at(t,h),OLD.at(t,h));
      assert.equal(k.addDays(t,1),t+86_400_000);
    }
  }
});

test('로스앤젤레스 2026-11-01(25시간 날) — 창 시각이 현지 벽시계로 정확하다',()=>{
  const k=load();k.setPetZones({timeZone:'America/Los_Angeles',rulesTimeZone:'America/Los_Angeles'});
  const noon=iso('2026-11-01T12:00:00-08:00'); // 서머타임 끝난 뒤(PST)
  assert.equal(k.dayStart(noon),iso('2026-11-01T00:00:00-07:00')); // 자정은 아직 PDT
  assert.equal(k.at(noon,19),iso('2026-11-01T19:00:00-08:00'));
  assert.equal(k.at(noon,23),iso('2026-11-01T23:00:00-08:00'));
  assert.equal(k.at(noon,7),iso('2026-11-01T07:00:00-08:00'));
  assert.equal(k.at(noon,10),iso('2026-11-01T10:00:00-08:00'));
  assert.equal(k.at(noon,1),iso('2026-11-01T01:00:00-07:00')); // 두 번 오는 1시는 앞의 것(java.time 과 같다)
  // 옛 덧셈식이라면 dayStart+19h 는 18:00 PST 로 한 시간 일찍 열린다.
  assert.notEqual(k.dayStart(noon)+19*3600_000,k.at(noon,19));
  assert.equal(k.timeOfDay(noon),12*3600_000);
  assert.equal(k.addDays(iso('2026-10-31T19:00:00-07:00'),1),iso('2026-11-01T19:00:00-08:00')); // 25시간 뒤
  assert.equal(k.dayIndex(iso('2026-11-01T23:59:00-08:00')),k.dayIndex(iso('2026-11-01T00:01:00-07:00')));
  assert.equal(k.lightPhaseAt(iso('2026-11-01T19:30:00-08:00')),'NIGHT');
  assert.equal(k.lightPhaseAt(iso('2026-11-01T07:30:00-08:00')),'MORNING');
});

test('로스앤젤레스 2026-03-08(23시간 날) — 없는 02:30 은 03:30 으로 밀린다',()=>{
  const k=load();k.setPetZones({timeZone:'America/Los_Angeles',rulesTimeZone:'America/Los_Angeles'});
  const noon=iso('2026-03-08T12:00:00-07:00');
  assert.equal(k.dayStart(noon),iso('2026-03-08T00:00:00-08:00'));
  assert.equal(k.at(noon,19),iso('2026-03-08T19:00:00-07:00'));
  assert.equal(k.at(noon,2,30),iso('2026-03-08T03:30:00-07:00'));
  assert.equal(k.addDays(iso('2026-03-07T23:00:00-08:00'),1),iso('2026-03-08T23:00:00-07:00'));
});

test('콜카타(+5:30) — 23:00 이 정각 오프셋이 아니어도 맞다',()=>{
  const k=load();k.setPetZones({timeZone:'Asia/Kolkata',rulesTimeZone:'Asia/Kolkata'});
  const t=iso('2026-10-26T12:00:00+05:30');
  assert.equal(k.at(t,23),iso('2026-10-26T23:00:00+05:30'));
  assert.equal(new Date(k.at(t,23)).toISOString(),'2026-10-26T17:30:00.000Z');
  assert.deepEqual({...k.kstHourMinute(iso('2026-10-26T17:45:00Z'))},{hour:23,minute:15});
});

test('1단계 — 규칙은 서울, 표시는 LA: 서울 19시는 LA 새벽 3시(PDT)·2시(PST)',()=>{
  const k=load();k.setPetZones({timeZone:'America/Los_Angeles',rulesTimeZone:'Asia/Seoul'});
  assert.equal(k.rulesAreLocal(),false);assert.equal(k.zonesAreSeoul(),false);
  const oct=iso('2026-10-26T12:00:00+09:00');
  assert.deepEqual({...k.ruleHourInDisplay(19,0,oct)},{hour:3,minute:0});
  assert.equal(k.ruleHourLabel(19,'ko',oct),'오전 3시');
  assert.equal(k.ruleHourLabel(19,'en',oct),'3 a.m.');
  const nov=iso('2026-11-10T12:00:00+09:00');
  assert.deepEqual({...k.ruleHourInDisplay(19,0,nov)},{hour:2,minute:0});
  // 규칙 함수는 여전히 서울 벽시계 — 목 서버·빛이 서버와 같은 창을 본다.
  assert.equal(k.at(oct,19),iso('2026-10-26T19:00:00+09:00'));
  assert.equal(k.formatInZone(iso('2026-10-26T10:00:00Z'),'en-US',{hour:'numeric',hour12:false}),'03');
});

test('못 읽는 시간대는 서울로 둔다',()=>{
  const k=load();k.setPetZones({timeZone:'Mars/Olympus',rulesTimeZone:''});
  assert.equal(k.getDisplayZone(),'Asia/Seoul');assert.equal(k.getRulesZone(),'Asia/Seoul');
});

function loadWindowCopy(k){
  const src=fs.readFileSync(path.join(root,'zzal/fe/lib/windowCopy.ts'),'utf8');
  const out=ts.transpileModule(src,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
  const rules={SLEEP_WINDOW:{from:19,to:23},WAKE_WINDOW:{from:7,to:10}};
  const context={exports:{},Date,require:id=>id.endsWith('/kst')?k:rules};
  vm.runInNewContext(out,context);
  return context.exports;
}

test('창 문구 — 한국 사용자는 null(옛 문구 그대로), LA 1단계는 실제로 눌리는 현지 시각',()=>{
  const k=load();const w=loadWindowCopy(k);
  k.setPetZones({timeZone:'Asia/Seoul',rulesTimeZone:'Asia/Seoul'});
  for(const v of ['저녁 7시부터 재울 수 있어요.','아침에 깨워 주세요. 07~10시엔 깨워도 돼요.','저녁 7시부터'])
    for(const l of ['ko','en','ja']) assert.equal(w.windowUiText(v,l),null);
  assert.equal(w.windowCopy('restSleep','en'),null);
  assert.equal(w.windowUiText('밥 주기','en'),null);
  k.setPetZones({timeZone:'America/Los_Angeles',rulesTimeZone:'Asia/Seoul'});
  const s=w.windowUiText('저녁 7시부터 재울 수 있어요.','ko');
  // 서울 19시 = LA 03시(PDT) 또는 02시(PST) — 시험 날짜에 따라 둘 중 하나.
  assert.match(s,/^오전 [23]시부터 재울 수 있어요\(현지 시각\)\.$/);
  assert.match(w.windowCopy('restSleep','en'),/^You can put them to bed from [23] a\.m\.; they sleep on their own at [67] a\.m\., your time\.$/);
  k.setPetZones({timeZone:'America/Los_Angeles',rulesTimeZone:'America/Los_Angeles'}); // 2단계 모양
  assert.equal(w.windowUiText('저녁 7시부터 재울 수 있어요.','ko'),'오후 7시부터 재울 수 있어요(현지 시각).');
  assert.equal(w.windowUiText('아침 7~10시','en'),'7 a.m.–10 a.m.');
});
