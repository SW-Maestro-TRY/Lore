// Run with: node scripts/test-anchor-canvases.cjs
const fs = require('node:fs');
const assert = require('node:assert/strict');
const ts = require('typescript');
require.extensions['.ts'] = (module, filename) => {
  const source = fs.readFileSync(filename, 'utf8');
  module._compile(ts.transpileModule(source, {
    compilerOptions: {module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true},
    fileName: filename,
  }).outputText, filename);
};
const {mergeAnchors} = require('../zzal/fe/tamagotchi/props/anchors.ts');
const {charFit} = require('../zzal/fe/tamagotchi/props/layout.ts');
const p = (h, foot) => ({canvas: [312,h], bbox: {x:90,y:45,w:114,h:257}, head_top:{x:147,y:45},
  head_side:{y:80,left_x:90,right_x:204}, hand_front:{y:180,left_x:90,right_x:204},
  feet:{y:foot,left_x:90,right_x:204,center_x:147}});
const raw = {version:'1',K:257,Hw:114,canvas:{w:312,h:366},poses:{base:p(366,302),eat_rice:p(424,360)}};
const a = mergeAnchors(raw);
assert.deepEqual(a.poses.eat_rice.canvas,[312,424]);
assert.equal(charFit(a,'base').belowFoot,64/366);
assert.equal(charFit(a,'eat_rice').belowFoot,64/424);
assert.equal(charFit(a,'eat_rice').boxHPerK,424/257);
assert.equal(charFit(a,'base').boxHPerK,366/257);
const legacy = structuredClone(raw);
delete legacy.poses.base.canvas;
assert.equal(charFit(mergeAnchors(legacy),'base').belowFoot,64/366);
const invalid = structuredClone(raw);
invalid.poses.base.canvas = [-1,0];
assert.equal(charFit(mergeAnchors(invalid),'base').belowFoot,64/366);
console.log('7 anchor canvas assertions passed');
