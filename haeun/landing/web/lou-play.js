/* 루와 놀기 — 기다리는 화면의 만질 수 있는 자리 --------------------------- *
 *
 * 10분 가까이 이 화면을 본다. 위쪽 띠는 지금 어디쯤인지를 기계적으로 적고,
 * 여기 있는 것은 그다음 — **만질 수 있다**는 것. 기다림이 구경거리가 되면
 * 시간이 덜 길다.
 *
 * 반응은 화면에만 있다. 서버로 아무것도 안 보내고, 돌고 있는 작업에도 영향을
 * 주지 않는다. 반응이 끝나면 가만히 있는 루로 돌아간다.
 *
 * 기다리는 화면(index.html)과 화면 구경(demo.html)이 이 파일을 같이 쓴다 —
 * 예전에는 demo 가 같은 로직을 통째로 베껴 갖고 있어서 둘이 갈라졌다.
 * 마크업(#mascot / #mascotStage / #mascotImg / #playSay)이 없으면
 * setupLou() 는 조용히 아무것도 안 한다.
 *
 * 그림은 web/lou/react/ 에 있다 — design-reference/interaction 에서
 * web/lou/_sync_react.py 가 골라 줄여 온 것. 어떤 컷이 있고 어떤 말을 하는지는
 * 그쪽 manifest.json 이 들고 있어서, 컷을 늘리거나 말을 고칠 때 이 파일은
 * 안 건드려도 된다. */
(() => {
"use strict";

const $ = s => document.querySelector(s);
const ART = "/static/lou/react";

let art = null;         // manifest.json — 못 읽으면 만지기를 조용히 끈다
let busy = false;       // 반응을 재생하는 중인가
let timer = null;
let idleTimer = null;
let idleFrame = 0;
let touchedAt = 0;      // 마지막으로 사람이 만진 시각

const pick = list => list[Math.floor(Math.random() * list.length)];

function say(text) {
  const el = $("#playSay");
  if (el) el.textContent = text;
}

function show(kind, i) {
  const set = art && art[kind];
  const img = $("#mascotImg");
  if (!set || !img) return;
  const n = Math.max(0, Math.min(i, set.frames.length - 1));
  img.src = `${ART}/${kind}/${set.frames[n]}`;
  // 말은 컷 수와 길이가 같으면 컷을 따라가고, 아니면 그중 아무거나 고른다.
  if (set.say && set.say.length) {
    say(set.say.length === set.frames.length ? set.say[n] : pick(set.say));
  }
}

/* 한 흐름을 처음부터 끝까지 재생한다. */
function play(kind, step = 520, hold = 1200) {
  const set = art && art[kind];
  if (!set) return;
  busy = true;
  const box = $("#mascot");
  if (box) box.dataset.react = kind;
  clearTimeout(timer);
  let i = 0;
  (function next() {
    show(kind, i);
    if (i < set.frames.length - 1) { i += 1; timer = setTimeout(next, step); return; }
    timer = setTimeout(rest, hold);
  })();
}

/* 한 컷만 띄운다 — "누를 때마다 아무 반응이나 하나" 쪽. */
function one(kind, hold = 1500) {
  const set = art && art[kind];
  if (!set) return;
  busy = true;
  const box = $("#mascot");
  if (box) box.dataset.react = kind;
  clearTimeout(timer);
  show(kind, Math.floor(Math.random() * set.frames.length));
  timer = setTimeout(rest, hold);
}

function rest() {
  busy = false;
  const box = $("#mascot");
  if (box) delete box.dataset.react;
  say("루를 눌러 보세요");
  idleFrame = 0;
  idleTick();
}

/* 가만히 둘 때. 한동안 아무도 안 만지면 잠들고, 누르면 깬다. */
function idleTick() {
  if (busy || !art) return;
  // 기다리는 화면이 아직 안 떴으면(폼을 채우는 중) 그림을 갈아 끼울 이유가 없다
  const box = $("#mascot");
  if (!box || !box.offsetParent) return;
  const asleep = Date.now() - touchedAt > 20000;
  const kind = asleep && art.sleep ? "sleep" : "idle";
  const set = art[kind];
  if (!set) return;
  idleFrame = asleep
    ? Math.min(idleFrame + 1, set.frames.length - 2)   // 잠들면 거기서 멈춘다
    : (idleFrame + 1) % set.frames.length;
  const img = $("#mascotImg");
  if (img) img.src = `${ART}/${kind}/${set.frames[idleFrame]}`;
}

/* 다음에 쓸 그림을 미리 받아 둔다 — 처음 누를 때 한 박자 늦게 뜨면
   "반응이 없다"로 읽힌다. 38컷 전부 합쳐 300KB 가 안 되지만, 랜딩을 열자마자
   다 받을 이유는 없다: 가만히 있는 컷만 먼저 받고 나머지는 처음 만질 때 받는다. */
let preloaded = false;
function preload(kinds) {
  for (const kind of kinds) {
    const set = art[kind];
    if (!set) continue;
    for (const f of set.frames) { new Image().src = `${ART}/${kind}/${f}`; }
  }
}
function preloadRest() {
  if (preloaded) return;
  preloaded = true;
  preload(Object.keys(art).filter(k => k !== "idle" && k !== "sleep"));
}

/* ---- 흔들기 (모바일) -------------------------------------------------- *
 * iOS 13+ 는 사람이 허락해야 가속도를 알려주고, 그 물어보는 창은 **사람이
 * 무언가를 누른 직후에만** 열 수 있다. 그래서 루를 처음 누를 때 같이 묻고,
 * 아직 안 물어봤거나 거절한 기기를 위해 버튼도 하나 둔다. */
let shakeOn = false;
let shakeAsked = false;
let shakeAt = 0;

function onShake(e) {
  const a = e.accelerationIncludingGravity;
  if (!a || busy) return;
  const power = Math.abs(a.x || 0) + Math.abs(a.y || 0) + Math.abs(a.z || 0);
  if (power < 34 || Date.now() - shakeAt < 2600) return;
  shakeAt = Date.now();
  touchedAt = Date.now();
  play("shake", 380, 1500);
}

function startShake() {
  if (shakeOn) return;
  shakeOn = true;
  window.addEventListener("devicemotion", onShake);
  const btn = $("#shakeAllow");
  if (btn) btn.hidden = true;
  const hint = $("#playHint");
  if (hint) hint.textContent = "누르기 · 연달아 누르기 · 꾹 누르기 · 끌어당기기 · 폰 흔들기";
}

async function askShake() {
  if (shakeOn || shakeAsked) return;
  shakeAsked = true;
  const DME = window.DeviceMotionEvent;
  if (!DME) return;
  if (typeof DME.requestPermission !== "function") { startShake(); return; }
  try {
    if (await DME.requestPermission() === "granted") return startShake();
  } catch (err) { /* 아래에서 버튼을 보여 준다 */ }
  const b = $("#shakeAllow");
  if (b) b.hidden = false;
}

function setupShake() {
  const DME = window.DeviceMotionEvent;
  const btn = $("#shakeAllow");
  if (!DME) return;
  // 허락이 필요 없는 기기(안드로이드·데스크톱)는 바로 켠다
  if (typeof DME.requestPermission !== "function") { startShake(); return; }
  if (!btn) return;
  btn.hidden = false;
  btn.addEventListener("click", async () => {
    shakeAsked = true;
    try { if (await DME.requestPermission() === "granted") startShake(); }
    catch (err) { /* 거절하면 그냥 흔들기만 없다 */ }
  });
}

/* ---- 배선 ------------------------------------------------------------- */
async function setupLou() {
  const box = $("#mascot");
  const stage = $("#mascotStage");
  if (!box || !stage) return;
  try {
    const res = await fetch(`${ART}/manifest.json`);
    if (!res.ok) throw new Error("no manifest");
    art = await res.json();
  } catch (err) {
    return;                      // 그림이 없으면 만지기 자체를 끈다 (조용히)
  }
  preload(["idle", "sleep"]);
  touchedAt = Date.now();
  clearInterval(idleTimer);
  idleTimer = setInterval(idleTick, 1500);
  idleTick();

  let down = false, sx = 0, sy = 0, ox = 0, oy = 0, at = 0;
  let far = 0, turns = 0, lastDir = 0, dragging = false;
  let clicks = 0, clickWindow = null, holdTimer = null;

  const home = () => {
    box.dataset.home = "1";
    box.style.left = ""; box.style.top = "";
    setTimeout(() => { delete box.dataset.home; }, 500);
  };

  const tap = () => {
    clicks += 1;
    clearTimeout(clickWindow);
    clickWindow = setTimeout(() => { clicks = 0; }, 1500);
    if (clicks === 1) { one("click"); return; }
    // 연달아 누르면 점점 화난다 — 누른 횟수가 그대로 컷 번호가 된다
    clearTimeout(timer);
    busy = true;
    box.dataset.react = "multiclick";
    show("multiclick", Math.min(clicks - 1, art.multiclick.frames.length - 1));
    timer = setTimeout(rest, 1800);
  };

  box.addEventListener("pointerdown", e => {
    box.setPointerCapture(e.pointerId);
    down = true; sx = e.clientX; sy = e.clientY; at = Date.now();
    far = 0; turns = 0; lastDir = 0; dragging = false;
    touchedAt = Date.now();
    const r = box.getBoundingClientRect();
    ox = e.clientX - (r.left + r.width / 2);
    oy = e.clientY - (r.top + r.height / 2);
    clearTimeout(holdTimer);
    holdTimer = setTimeout(() => {          // 꾹 누르기 — 떼기 전에 시작한다
      if (!down || far > 14 || dragging) return;
      play("longpress", 900, 900);
    }, 550);
    preloadRest();                          // 처음 만지는 순간 나머지 컷을 받는다
    askShake();                             // iOS 는 사람 손짓이 있어야 물어볼 수 있다
  });

  stage.addEventListener("pointermove", e => {
    if (!down) return;
    const dx = e.clientX - sx, dy = e.clientY - sy;
    far = Math.max(far, Math.hypot(dx, dy));
    const dir = Math.sign(e.movementX || 0);
    if (dir && lastDir && dir !== lastDir) turns += 1;
    if (dir) lastDir = dir;
    touchedAt = Date.now();

    if (far > 60) {                          // 멀리 끌면 따라온다
      if (!dragging) {
        dragging = true;
        clearTimeout(holdTimer); clearTimeout(timer);
        busy = true;
        box.dataset.grab = "1";
        box.dataset.react = "drag";
        show("drag", 0);
      }
      const sr = stage.getBoundingClientRect();
      const w = box.offsetWidth / 2, h = box.offsetHeight / 2;
      box.style.left = `${Math.max(w, Math.min(e.clientX - sr.left - ox, sr.width - w))}px`;
      box.style.top = `${Math.max(h, Math.min(e.clientY - sr.top - oy, sr.height - h))}px`;
    } else if (turns >= 3 && !dragging) {    // 제자리에서 문지르면 쓰다듬기
      turns = 0;
      clearTimeout(holdTimer);
      play("pet", 620, 1400);
    }
  });

  stage.addEventListener("pointerup", () => {
    if (!down) return;
    down = false;
    clearTimeout(holdTimer);
    delete box.dataset.grab;
    const held = Date.now() - at;
    touchedAt = Date.now();

    if (dragging) {                          // 놓아주면 기뻐하고 제자리로
      home();
      show("drag", art.drag.frames.length - 1);
      clearTimeout(timer);
      timer = setTimeout(rest, 1400);
      return;
    }
    if (busy && held > 550) return;          // 꾹 누르기·쓰다듬기가 재생 중이면 둔다
    if (far > 14) return;
    tap();
  });

  stage.addEventListener("pointercancel", () => {
    down = false; delete box.dataset.grab; home();
  });

  // 키보드로도 만질 수 있어야 한다 — button 이라 Enter/Space 가 온다
  box.addEventListener("keydown", e => {
    if (e.key !== "Enter" && e.key !== " ") return;
    e.preventDefault();
    preloadRest();
    touchedAt = Date.now();
    tap();
  });

  setupShake();
}

window.setupLou = setupLou;
})();
