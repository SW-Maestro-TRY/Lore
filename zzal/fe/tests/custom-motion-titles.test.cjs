// 맞춤 동작 영어 원제 표시 사전 시험 — `node --test zzal/fe/tests/custom-motion-titles.test.cjs`
//
// 1. 사전에 있는 영어 원제는 ko/ja 화면에서 번역되고, en 화면에서는 원문 그대로.
// 2. 사전에 없는 제목(새 계획의 한국어 제목 포함)은 어느 언어든 원문 그대로.
// 3. 포커스 앨범 목록이 locale을 받으면 맞춤 동작 이름을 번역해 내보낸다.
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const ts=require('typescript');
const root=path.resolve(__dirname,'../../..');

function load(rel,modules={}){
  const src=fs.readFileSync(path.join(root,rel),'utf8');
  const out=ts.transpileModule(src,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
  const context={exports:{},Object,Array,Set,String,require:name=>{if(name in modules)return modules[name];throw new Error('unexpected import '+name)}};
  vm.runInNewContext(out,context);
  return context.exports;
}
const titles=load('zzal/fe/lib/customMotionTitles.ts');

test('사전에 있는 영어 원제는 ko·ja로 번역된다',()=>{
  assert.equal(titles.localizeCustomMotionTitle('Chocolate Victory Pose','ko'),'초콜릿 승리 포즈');
  assert.equal(titles.localizeCustomMotionTitle('Quiet Window Watch','ja'),'静かに窓の外を見る');
  assert.equal(titles.localizeCustomMotionTitle('Chocolate Victory Pose','en'),'Chocolate Victory Pose');
});

test('사전에 없는 제목은 원문 그대로',()=>{
  assert.equal(titles.localizeCustomMotionTitle('Unknown Future Title','ko'),'Unknown Future Title');
  assert.equal(titles.localizeCustomMotionTitle('찻잔 들어 보이기','ja'),'찻잔 들어 보이기');
});

test('포커스 앨범 목록은 locale에 맞춰 맞춤 동작 이름을 보인다',()=>{
  const inv=load('zzal/fe/tamagotchi/focused-room/focusedAlbumInventory.ts',{'../../lib/customMotionTitles':titles});
  const pet={motions:[],customMotions:[{id:'c1',title:'Cloudbound Leap',description:'',imageKey:'k1',sourceKind:'CUSTOM'},{id:'c2',title:'찻잔 들어 보이기',description:'',imageKey:'k2',sourceKind:'CUSTOM'}]};
  assert.deepEqual(inv.focusedAlbumInventory(pet,'ko').map(m=>m.label),['구름 위로 점프','찻잔 들어 보이기']);
  assert.deepEqual(inv.focusedAlbumInventory(pet).map(m=>m.label),['Cloudbound Leap','찻잔 들어 보이기']);
});
