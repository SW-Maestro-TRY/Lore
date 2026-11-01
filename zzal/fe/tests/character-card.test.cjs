/** 캐릭터 카드 순수 규칙(대표 칩·입력 검사). 화면 모듈을 그대로 transpile 해 돌린다. */
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');const path=require('node:path');const vm=require('node:vm');const ts=require('typescript');
const file=path.resolve(__dirname,'../tamagotchi/yeoul/characterCardRules.ts');
const mod={};vm.runInNewContext(ts.transpileModule(fs.readFileSync(file,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,{exports:mod,require:()=>{throw Error('characterCardRules.ts must stay import-free')}});
const {pickRepresentativeChips:pick,validateName,validateCallMe,NAME_MAX,CALL_ME_MAX}=mod;
const src=(o)=>({authoredPersona:{version:1,fields:['world','job','persona','likes','dislikes','extra'].map(id=>({fieldId:id,chips:o[id]??[],rawText:'',skipped:false}))}});
const plain=v=>JSON.parse(JSON.stringify(v));

test('chips: world 1, job 1, persona 2 in field order',()=>{
 assert.deepEqual(plain(pick(src({world:['판타지','현대'],job:['가게 운영','학생'],persona:['다정함','느긋함','장난기'],likes:['간식']}))),['판타지','가게 운영','다정함','느긋함']);
});
test('chips: empty world is filled from the next fields',()=>{
 assert.deepEqual(plain(pick(src({job:['가게 운영','학생'],persona:['다정함','느긋함','장난기']}))),['가게 운영','다정함','느긋함','학생']);
});
test('chips: fewer than four across the three fields shows only what exists, never likes/dislikes/extra',()=>{
 assert.deepEqual(plain(pick(src({world:['판타지'],persona:['다정함'],likes:['간식'],dislikes:['벌레'],extra:['x']}))),['판타지','다정함']);
});
test('chips: legacy pet without authored source uses legacy personalities',()=>{
 assert.deepEqual(plain(pick({authoredPersona:null,legacySource:{personalities:['GENTLE','SHY'],world:null,note:null,tone:null,genre:null}})),['GENTLE','SHY']);
});
test('chips: no source at all yields no chips',()=>{
 assert.deepEqual(plain(pick({authoredPersona:null,legacySource:null})),[]);
 assert.deepEqual(plain(pick(src({}))),[]);
 assert.deepEqual(plain(pick(null)),[]);
});

test('limits match the server rules',()=>{assert.equal(NAME_MAX,12);assert.equal(CALL_ME_MAX,20);});
test('name: empty or blank',()=>{assert.equal(validateName('','여울'),'empty');assert.equal(validateName('   ','여울'),'empty');});
test('name: over 12 after trimming, 12 passes',()=>{assert.equal(validateName('가'.repeat(13),'여울'),'tooLong');assert.equal(validateName(' '+'가'.repeat(12)+' ','여울'),null);});
test('name: unchanged (also with surrounding spaces) blocks save without message',()=>{assert.equal(validateName('여울','여울'),'unchanged');assert.equal(validateName(' 여울 ','여울'),'unchanged');assert.equal(validateName('바다여울','여울'),null);});
test('callMe: empty or blank',()=>{assert.equal(validateCallMe('  ','주인님'),'empty');});
test('callMe: over 20',()=>{assert.equal(validateCallMe('가'.repeat(21),'주인님'),'tooLong');assert.equal(validateCallMe('가'.repeat(20),'주인님'),null);});
test('callMe: slash means more than one',()=>{assert.equal(validateCallMe('언니/오빠','주인님'),'multiple');assert.equal(validateCallMe('/','주인님'),'multiple');});
test('callMe: "이름 없이" and "이름없이" are refused',()=>{assert.equal(validateCallMe('이름 없이','주인님'),'nameless');assert.equal(validateCallMe('이름없이',null),'nameless');assert.equal(validateCallMe(' 이름  없이 ',null),'nameless');});
test('callMe: unchanged vs a new value; null current treats any value as new',()=>{assert.equal(validateCallMe('주인님','주인님'),'unchanged');assert.equal(validateCallMe('언니','주인님'),null);assert.equal(validateCallMe('언니',null),null);});
test('emoji count follows String.length like the server',()=>{assert.equal(validateName('😀'.repeat(6),'x'),null);assert.equal(validateName('😀'.repeat(7),'x'),'tooLong');});
test('card art only uses the pet own base image and falls back to a silhouette, never spriteUrl',()=>{
 const card=fs.readFileSync(path.resolve(__dirname,'../tamagotchi/yeoul/CharacterCard.tsx'),'utf8').replace(/\/\*[\s\S]*?\*\//g,'').replace(/^\s*\/\/.*$/gm,''); // code only, comments may name the forbidden path
 assert.ok(!/spriteUrl|YEOUL_MOTION/.test(card),'no Yeoul fallback image path');
 assert.match(card,/<CardArt src=\{live\.img\('base'\)\}/);
 assert.match(card,/const missing=!src\|\|geometry\.failed;/);
 assert.match(card,/missing\?<svg className="zcc-silhouette" data-card-silhouette/);
 assert.ok(!/저장했어요/.test(card+fs.readFileSync(path.resolve(__dirname,'../tamagotchi/yeoul/characterCardCopy.ts'),'utf8')),'no save toast copy');
});
