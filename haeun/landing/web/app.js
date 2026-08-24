/* LORE 랜딩 — 폼 → 진행 → 결과.
 *
 * 상태는 서버에서 통째로 받아 화면을 다시 그린다(0.8초마다). 브라우저가 진행
 * 상황을 따로 들고 있지 않으므로, 새로고침해도 창을 닫았다 열어도 같은 화면이
 * 나온다 — 10분 걸리는 일에서 이건 편의가 아니라 필수다. */

const $  = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const FIELD_KEYS = ["나이", "성별", "직업", "성격", "말투", "과거", "관계", "약점"];

const STYLE_INFO = [
  ["webtoon",   "일반 웹툰",      "깔끔한 선과 셀 채색. 매주 연재하는 그 그림 — 읽히는 속도가 기준입니다."],
  ["romance",   "로맨스 판타지",  "표지 일러스트급 밀도. 보석 같은 눈, 장미와 금박, 레이스까지 하나하나."],
  ["cinematic", "시네마틱 반실사","빛으로 화려해집니다. 역광·공기·얕은 심도·필름 색보정. 얼굴은 웹툰 그대로."],
  ["lineart",   "선화 · 액션",    "선과 여백이 다 합니다. 톤을 거의 안 쓰고 포즈와 실루엣으로 읽힙니다."],
  ["pastel",    "일상툰 감성",    "일부러 덜 완성한 그림. 흔들리는 연필선, 종이 결, 바랜 파스텔 몇 색."],
  ["noir",      "다크 느와르",    "어둠이 주인공입니다. 화면 대부분이 먹으로 덮이고 빛은 얇게 남습니다."],
  ["shoujo",    "순정 · BL",      "얼굴과 둘 사이의 거리. 길고 날카로운 눈, 스크린톤, 여백에 뜬 꽃."],
];

const IDEAS = [
  "몰락한 문파에 혼자 남아 적 앞으로 걸어 나가는 검객",
  "데뷔조에서 잘린 연습생에게 다시 무대가 주어진다",
  "각성 등급 최하위인데 아무도 못 깨는 게이트를 혼자 연다",
  "악역 영애로 빙의했는데 원작 내용을 하나도 모른다",
];
// 장르 단추로 먼저 꺼내 두는 것들. index.html 의 datalist 에는 더 많이 있고,
// 여기 없는 장르도 칸에 직접 쓰면 그대로 간다.
const GENRE_QUICK = [
  "무협", "판타지", "로맨스 판타지", "헌터·게이트",
  "학원로맨스", "현대 판타지", "오컬트 미스터리", "일상",
];

/* ---- 일반 모드 · 전문 모드 ------------------------------------------- *
 *
 * 계정이 없으므로 고른 모드는 브라우저(localStorage)에 남는다. sessionStorage
 * 가 아닌 이유: 창을 닫았다 열 때마다 "어떻게 만들까요?" 를 다시 묻는 것은
 * 한 번 정한 사람에게는 그냥 방해다.
 *
 * 모드가 정하는 것은 두 가지뿐이다 —
 *   1. 폼에서 [data-expert-only] 를 보여줄 것인가 (안 보여줘도 기본값은 간다)
 *   2. 시트 검수 화면에서 외형 사양 편집 폼을 열 것인가
 * 어느 단계에서 멈출지는 **서버가** 정한다(pipeline.checkpoints). 화면이 그
 * 규칙을 베껴 두면 둘이 갈라져서, 고른 사람이 속은 것이 된다. */

const MODES = {
  simple: { label: "일반 모드", desc: "세부 설정 없이 자동으로 만듭니다 — 캐릭터 시트만 확인합니다." },
  expert: { label: "전문 모드", desc: "이야기 · 시트 · 콘티 · 그림 검수, 네 곳에서 멈추고 보여드립니다." },
};

function currentMode() {
  const m = localStorage.getItem("lore_mode");
  return m === "expert" ? "expert" : m === "simple" ? "simple" : null;
}

function isExpert() { return currentMode() === "expert"; }

/* 폼의 전문 전용 칸을 열고 닫는다. 숨긴 칸의 값은 **안 지운다** — 전문 모드로
   골라 놓고 일반으로 바꿔도 라디오의 기본값(연출 fast · 등신 기본 · 검수 2번)이
   그대로 남아서, collect() 가 보내는 값이 일반 모드가 약속한 것과 같다. */
function applyMode() {
  const mode = currentMode() || "simple";
  const expert = mode === "expert";
  document.body.dataset.mode = mode;
  $$("[data-expert-only]").forEach(el => { el.hidden = !expert; });
  const badge = $("#modeBadge");
  if (badge) badge.textContent = MODES[mode].label;
  const desc = $("#modeBarDesc");
  if (desc) desc.textContent = MODES[mode].desc;
  paintCost();
}

function setMode(mode) {
  localStorage.setItem("lore_mode", mode === "expert" ? "expert" : "simple");
  applyMode();
}

let jobId    = sessionStorage.getItem("lore_job") || null;
let poll     = null;
/* 사진은 여러 장 받는다 — 한 사람을 여러 각도로 찍은 것이다.
   한 장으로는 늘 안 보이는 칸(하의·신발·뒤통수)이 남고, 다른 각도가 그 칸을 채운다. */
const MAX_PHOTOS = 4;
let photos = [];          // data URL 목록. 순서가 LOOK 에 붙는 순서다.
let shownCuts = new Set();
let lastStatus = null;

/* ------------------------------------------------------------------ 초기화 */

function buildForm() {
  // 항목 표는 화면에서 뺐다(1걸음은 사진·이름·이야기뿐). 칸이 있으면 채우고
  // 없으면 지나간다 — collect() 의 fields 는 그때 그냥 빈 채로 간다.
  const grid = $("#fieldsGrid");
  if (grid) grid.innerHTML = FIELD_KEYS.map(k => `
    <label><span>${k}</span><input type="text" data-field="${k}" placeholder=""></label>
  `).join("");

  $("#styles").innerHTML = STYLE_INFO.map(([key, label, desc], i) => `
    <label class="style-opt">
      <input type="radio" name="style" value="${key}" ${i === 0 ? "checked" : ""}>
      <span class="style-box"><b>${label}</b><small>${desc}</small></span>
    </label>
  `).join("");

  $("#ideaChips").innerHTML = IDEAS
    .map(t => `<button type="button" class="chip">${t}</button>`).join("");
  $$("#ideaChips .chip").forEach(btn => btn.addEventListener("click", () => {
    $("#storyInput").value = btn.textContent;
    $("#storyInput").focus();
  }));

  // 장르 빠른 고르개. datalist 는 폰에서 안 열리는 브라우저가 있어서, 자주
  // 쓰는 것만 단추로 먼저 꺼내 둔다. 목록에 없는 장르는 그대로 칸에 쓰면 된다.
  const quick = $("#genreQuick");
  if (quick) {
    quick.innerHTML = GENRE_QUICK
      .map(g => `<button type="button" aria-pressed="false">${g}</button>`).join("");
    const input = $("#form").genre;
    const sync = () => $$("button", quick).forEach(b =>
      b.setAttribute("aria-pressed", String(b.textContent === input.value.trim())));
    $$("button", quick).forEach(btn => btn.addEventListener("click", () => {
      // 눌린 것을 다시 누르면 비운다 — 비우면 루가 고른다
      input.value = (input.value.trim() === btn.textContent) ? "" : btn.textContent;
      sync();
    }));
    input.addEventListener("input", sync);
    sync();
  }
}

/* ---- 사용자 피드백 ---------------------------------------------------- *
 *
 * 자유 입력만 두면 대부분 아무것도 안 적고 넘어간다 — 그러면 왜 다시 만들라고
 * 했는지가 남지 않는다. 그래서 자주 나온 불만을 버튼으로 먼저 보여 주고, 그
 * 밖의 말은 그대로 적게 한다. 둘 다 선택이라 아무것도 안 하고 눌러도 된다.
 *
 * 항목 목록은 서버(/api/config)가 준다. 화면에 베껴 두면 pipeline.py 의
 * FEEDBACK_TAGS 와 갈라지고, 화면에만 있는 id 를 보내면 서버가 버린다. */

let fbTagsByStage = {};
let fbTextMax = 500;

function fbChips(stage, wrap, max) {
  wrap.replaceChildren(...(fbTagsByStage[stage] || []).map(t => {
    const b = document.createElement("button");
    b.type = "button";
    b.className = "fb-tag";
    b.dataset.tagId = t.id;
    b.textContent = t.label;
    b.setAttribute("aria-pressed", "false");
    b.addEventListener("click", () =>
      b.setAttribute("aria-pressed",
                     b.getAttribute("aria-pressed") === "true" ? "false" : "true"));
    return b;
  }));
  if (max) max.maxLength = fbTextMax;
}

async function loadFeedbackTags() {
  try {
    const cfg = await getConfig();
    fbTagsByStage = cfg.feedback_tags || {};
    fbTextMax = cfg.feedback_text_max || fbTextMax;
  } catch { return; }      // 못 받으면 자유 입력만 남는다 — 승인 자체는 안 막는다
  document.querySelectorAll(".fb-box").forEach(box =>
    fbChips(box.dataset.fbStage, $(".fb-tags", box), $(".fb-text", box)));
}

/* 그 단계에서 고른 항목과 적은 말. 상자가 없거나 아무것도 안 했으면 빈 값이다. */
function fbRead(box) {
  if (!box) return { tags: [], feedback: "" };
  return {
    tags: [...box.querySelectorAll('.fb-tag[aria-pressed="true"]')]
      .map(b => b.dataset.tagId),
    feedback: ($(".fb-text", box)?.value || "").trim(),
  };
}

/* 보낸 뒤에는 비운다. 다음 판에도 지난번에 고른 것이 눌린 채로 남아 있으면
   사람이 다시 고른 것처럼 보여서 같은 말이 두 번 프롬프트에 실린다. */
function fbClear(box) {
  if (!box) return;
  box.querySelectorAll(".fb-tag").forEach(b => b.setAttribute("aria-pressed", "false"));
  const text = $(".fb-text", box);
  if (text) text.value = "";
}

/* ---- 작품 규칙 (user memory) ------------------------------------------ *
 *
 * 작가가 작품마다 선언하는 규칙. 피드백이 "지난 결과에 대한 말" 이라면 이것은
 * "앞으로 모든 생성이 지킬 것" 이다 — 스토리·콘티·그림 전 단계 프롬프트에
 * 실리고, 다른 설정과 충돌하면 이긴다 (서버 쪽 pipeline.read/write_memory).
 *
 * 저장 형식은 구조(JSON)지만 화면은 줄 단위 텍스트로 편집한다:
 *   항상 적용   한 줄 = 규칙 하나
 *   키워드      「태그1, 태그2 :: 내용」 — :: 앞이 발동 키워드다 */

let memLimits = { always: 500, keyword: 1500 };

function memParse(box) {
  const always = $(".mem-always", box).value.split("\n")
    .map(t => t.trim()).filter(Boolean).map(text => ({ text }));
  const keyword = [];
  for (const line of $(".mem-keyword", box).value.split("\n")) {
    const t = line.trim();
    if (!t) continue;
    const i = t.indexOf("::");
    if (i < 0) return { error: `키워드 줄에 :: 가 없습니다 — "${t.slice(0, 24)}"` };
    const tags = t.slice(0, i).split(",").map(x => x.trim()).filter(Boolean);
    const text = t.slice(i + 2).trim();
    if (!tags.length || !text)
      return { error: `키워드 줄이 비었습니다 — "${t.slice(0, 24)}"` };
    keyword.push({ tags, text });
  }
  return { always, keyword };
}

function memFill(box, data) {
  $(".mem-always", box).value =
    (data.always || []).map(e => e.text).join("\n");
  $(".mem-keyword", box).value =
    (data.keyword || []).map(e => `${e.tags.join(", ")} :: ${e.text}`).join("\n");
  memCount(box);
}

function memCount(box) {
  const a = (memParse(box).always || []).reduce((n, e) => n + e.text.length, 0);
  const k = (memParse(box).keyword || []).reduce((n, e) => n + e.text.length, 0);
  const ca = $('.mem-count[data-kind="always"]', box);
  const ck = $('.mem-count[data-kind="keyword"]', box);
  if (ca) { ca.textContent = `${a}/${memLimits.always}`;
            ca.style.color = a > memLimits.always ? "var(--accent)" : ""; }
  if (ck) { ck.textContent = `${k}/${memLimits.keyword}`;
            ck.style.color = k > memLimits.keyword ? "var(--accent)" : ""; }
}

/* runId 의 규칙을 모든 .mem-box 에 채우고 저장 버튼을 잇는다. 화면에 상자가
 * 여럿(승인·결과)이라 마지막으로 연 것이 저장하는 것이 자연스럽다 — 저장하면
 * 다른 상자도 다시 채운다. */
async function wireMemory(runId) {
  if (!runId) return;
  let data = { always: [], keyword: [] };
  try {
    const cfg = await getConfig();
    memLimits = { always: cfg.memory_always_max || 500,
                  keyword: cfg.memory_keyword_max || 1500 };
    data = await (await fetch(`/api/runs/${encodeURIComponent(runId)}/memory`)).json();
  } catch { /* 서버가 없으면 빈 칸 — 편집 자체는 된다 */ }
  $$(".mem-box").forEach(box => {
    memFill(box, data);
    if (box.dataset.memWired) return;          // 리스너는 한 번만
    box.dataset.memWired = "1";
    box.addEventListener("input", () => memCount(box));
    $(".mem-save", box).addEventListener("click", async () => {
      const status = $(".mem-status", box);
      const parsed = memParse(box);
      if (parsed.error) { status.textContent = parsed.error; return; }
      status.textContent = "저장하는 중…";
      try {
        const res = await fetch(`/api/runs/${encodeURIComponent(runId)}/memory`, {
          method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify(parsed) });
        const out = await res.json();
        if (!res.ok) throw new Error(out.error || "저장하지 못했습니다");
        status.textContent = "저장됨 — 다음 생성(다시 만들기·다음 화·다시 그리기)부터 적용됩니다";
        $$(".mem-box").forEach(b => { if (b !== box) memFill(b, out); });
      } catch (err) { status.textContent = err.message; }
    });
  });
}

function fbStageBox(stage) {
  return document.querySelector(`.fb-box[data-fb-stage="${stage}"]`);
}

/* 세계관 프리셋 — 목록은 서버(story-harness/worlds.json)에서 받는다.
   여기에 베껴 두면 두 곳이 갈라지고, 화면에만 있는 키를 고르면 story.py 가
   worlds.json 에서 그 키를 못 찾아 실행이 통째로 멈춘다. */
let configOnce = null;
function getConfig() {
  if (!configOnce) configOnce = fetch("/api/config").then(r => r.json());
  return configOnce;
}

async function loadWorlds() {
  const sel = $("#worldPreset"), hint = $("#worldHint");
  if (!sel) return;
  let worlds = [];
  try {
    worlds = (await getConfig()).worlds || [];
  } catch { return; }               // 못 받아도 자유 입력은 그대로 된다
  if (!worlds.length) return;

  sel.append(...worlds.map(w => {
    const o = document.createElement("option");
    o.value = w.key; o.textContent = w.label; o.dataset.text = w.text || "";
    return o;
  }));

  sel.addEventListener("change", () => {
    const text = sel.selectedOptions[0]?.dataset.text || "";
    hint.textContent = text;
    hint.hidden = !text;
    // 고르면 본문을 입력칸에 채워 준다 — 그대로 써도 되고 고쳐 써도 된다.
    // 이미 직접 쓴 글이 있으면 덮지 않는다. 골랐다고 남의 글을 지우면 안 된다.
    const box = $("#form").world;
    if (text && !box.value.trim()) box.value = text;
  });
}

function setupPhoto() {
  const drop = $("#photoDrop"), input = $("#photo");

  const paint = () => {
    // 올린 사진 + 남은 빈 칸을 **항상 네 칸**으로 채운다. 빈 칸이 안 보이면
    // 더 올릴 수 있다는 것을 모른다(시안 capture/design2.png 의 규칙).
    // UI 는 **타일 한 장**이다. 서버와 photos 배열은 여러 장을 그대로
    // 받지만(하네스 계약), 화면에서는 한 장만 받는다 — 넣으면 타일이 사진이
    // 되고, ✕ 로 지우고 다시 넣는다.
    const shots = photos.map((src, i) => `
      <figure class="shot">
        <img src="${src}" alt="${i + 1}번째 사진">
        <button type="button" class="shot-x" data-i="${i}" aria-label="지우기">✕</button>
      </figure>`);
    const slots = photos.length ? []
      : [`<span class="photo-slot" aria-hidden="true">+</span>`];
    $("#photoStrip").innerHTML = shots.concat(slots).join("");
    $$("#photoStrip .shot-x").forEach(b => b.addEventListener("click", e => {
      e.preventDefault(); e.stopPropagation();
      photos.splice(Number(b.dataset.i), 1); paint();
    }));
    drop.classList.toggle("has-photo", photos.length > 0);
    $("#photoCount").textContent = photos.length ? "" : "눌러서 사진을 올려주세요";
    input.value = "";
  };

  const load = files => {
    const list = [...(files || [])];
    if (!list.length) return;
    const room = MAX_PHOTOS - photos.length;
    if (room <= 0) return toast(`사진은 ${MAX_PHOTOS}장까지 올릴 수 있습니다`);
    if (list.length > room) toast(`${room}장만 추가합니다 (최대 ${MAX_PHOTOS}장)`);
    list.slice(0, room).forEach(file => {
      if (!file.type.startsWith("image/")) return;
      if (file.size > 6 * 1024 * 1024) return toast("사진이 너무 큽니다 (6MB 까지)");
      const fr = new FileReader();
      fr.onload = () => { photos.push(fr.result); paint(); };
      fr.readAsDataURL(file);
    });
  };

  input.addEventListener("change", e => load(e.target.files));
  ["dragenter", "dragover"].forEach(ev => drop.addEventListener(ev, e => {
    e.preventDefault(); drop.classList.add("drag");
  }));
  ["dragleave", "drop"].forEach(ev => drop.addEventListener(ev, e => {
    e.preventDefault(); drop.classList.remove("drag");
  }));
  drop.addEventListener("drop", e => load(e.dataTransfer.files));
  paint();
}


/* 크레딧은 **목업**입니다 — 실제 과금과 무관하고, 화면에 얼마가 드는지
   보이게 하려고만 둡니다. 편집실(/editor)의 잔액과 같은 값을 씁니다. */
const CREDIT = { full: 240, preview: 60 };
// 컷 모드는 컷 하나가 이미지 한 장이라 그림 호출이 3배다 (지금은 한 장에 3컷).
// 이야기 단계 비용은 그대로이므로 그림 몫만 늘려 어림한다.
const WEBTOON_MULT = 3;

function layoutMode() {
  const el = document.querySelector('input[name="layout_mode"]:checked');
  return el ? el.value : "fast";
}

function paintCost() {
  const preview = true;              // 지금은 미리보기만 만든다
  const base = preview ? CREDIT.preview : CREDIT.full;
  const cost = layoutMode() === "webtoon" ? base * WEBTOON_MULT : base;
  $("#costChip").textContent = `−${cost} 크레딧`;
  $("#submitBtn").firstChild.textContent =
    preview ? "미리보기 만들기 " : "웹툰 만들기 ";
}

/* ------------------------------------------------------------- 위저드

   흐름은 사람이 겪는 순서다.

     1걸음  무엇을 만들 건지 — 사진 · 캐릭터 설명 · 이야기 한 줄
     2걸음  **어떻게** 만들지 — 스토리 모드 / 전문가 모드 (갈림길)
     3~5걸음  전문가를 고른 사람에게만 이어진다 (세계 · 그림 · 확인)

   그래서 걸음 수가 고정이 아니다 — 스토리 모드는 2걸음에서 끝나고 바로
   출발하고, 전문가 모드는 5걸음까지 간다. 수심계도 그에 맞춰 늘어난다.
   늘어나는 것 자체가 "더 물어보는 길을 골랐다"는 신호라서 숨기지 않는다.

   칸은 여전히 하나도 안 지웠다 — 전부 같은 <form> 안에 있고 보이는 걸음만
   바뀐다. 그래서 collect() 는 어느 걸음에 서 있든 전부 걷는다. */

const WIZ_FORK = 3;                       // 갈림길이 있는 걸음
const WIZ_SIMPLE_LAST = 3;                // 빠르게 보기는 여기서 출발한다
const WIZ_EXPERT_LAST = 5;
const WIZ_NAMES = ["수면", "항해", "갈림길", "심해", "바닥"];
let wizStep = 1;
/* 이번 실행에서 고른 길. localStorage 의 모드와 **일부러 따로 둔다** —
   지난번에 전문가로 만들었다고 이번에도 말없이 전문가 길로 끌고 가면, 갈림길
   화면이 있으나 마나가 된다. 고르는 것은 매번 다시 한다. (고른 값은 그때
   setMode 로 localStorage 에도 남아서 시트 편집 폼 같은 다른 자리에 쓰인다.) */
let wizChoice = null;

// 지금 길의 마지막 걸음. 아직 안 골랐으면 갈림길까지만 보여준다.
function wizLast() { return wizChoice === "expert" ? WIZ_EXPERT_LAST : WIZ_SIMPLE_LAST; }

function wizPaintGauge() {
  const gauge = $("#wizGauge");
  if (!gauge) return;
  const last = wizLast();
  gauge.innerHTML = WIZ_NAMES.slice(0, last).map((name, i) => {
    const n = i + 1;
    const state = n < wizStep ? "done" : (n === wizStep ? "on" : "");
    return `<li class="wiz-tick ${state}" title="${n}. ${name}"
                ${n === wizStep ? 'aria-current="step"' : ""}></li>`;
  }).join("");
}

// 요약 — 비운 칸은 "루가 정합니다"로 적는다. 안 적었다는 사실 자체가 결과로
// 보여야, 마지막 걸음에서 되돌아갈지 말지를 판단할 수 있다.
function wizPaintSummary() {
  const box = $("#wizSummary");
  if (!box) return;
  const form = $("#form");
  const auto = `<i class="wiz-auto">루가 정합니다</i>`;
  const val = v => (v && v.trim()) ? esc(v.trim()) : auto;
  const cut = (v, n) => {
    const t = (v || "").trim();
    return t ? esc(t.slice(0, n)) + (t.length > n ? "…" : "") : auto;
  };

  const styleEl = form.style;
  const styleLabel = styleEl
    ? (document.querySelector(`.style-opt input[value="${styleEl.value}"]`)
        ?.closest(".style-opt")?.querySelector("b")?.textContent || styleEl.value)
    : "";
  const fieldsFilled = $$("[data-field]", form).filter(el => el.value.trim()).length;

  const rows = [
    ["캐릭터", val(form.name.value)],
    ["설명", cut(form.character.value, 40)],
    ["항목", fieldsFilled ? `${fieldsFilled}개 적음` : auto],
    ["사진", photos.length ? `${photos.length}장` : `<i class="wiz-auto">없음</i>`],
    ["이야기", cut(form.story.value, 40)],
    ["장르", val(form.genre.value)],
    ["세계관", val(form.world.value)],
    ["그림체", styleLabel ? esc(styleLabel) : auto],
    ["연출", layoutMode() === "webtoon" ? "웹툰 · 무게로 묶음" : "빠르게 · 한 장 3컷"],
  ];
  box.innerHTML = rows.map(([k, v]) =>
    `<div class="wiz-row"><span>${k}</span><b>${v}</b></div>`).join("");
}

function wizGo(n, scroll = true) {
  const last = wizLast();
  wizStep = Math.min(last, Math.max(1, n));
  $$(".wiz-step").forEach(p => { p.hidden = Number(p.dataset.step) !== wizStep; });

  document.body.dataset.step = String(wizStep);

  // 갈림길에서는 늘 고르게 한다 — 지난번 모드가 기억돼 있어도 마찬가지다.
  const atFork = wizStep === WIZ_FORK;
  const atEnd  = wizStep === last;

  // 갈림길에서는 아래 단추를 안 쓴다 — 고르는 것이 곧 넘어가는 것이다.
  $("#wizNext").hidden = atFork || atEnd;
  $("#submitBtn").hidden = !(atEnd && !atFork);
  $("#wizSkip").hidden = atFork || atEnd;
  // 1걸음에서도 뒤로 갈 곳이 있다 — 홈. 그래서 안 죽인다.
  $("#wizPrev").textContent = wizStep === 1 ? "홈으로" : "이전";
  $("#submitNote").hidden = !(atEnd && !atFork);
  $(".wiz-foot").hidden = atFork;

  if (atEnd && !atFork) wizPaintSummary();
  wizPaintGauge();

  if (!scroll) return;
  window.scrollTo({ top: 0, behavior: "smooth" });   // 걸음마다 화면 맨 위부터
}

function setupWizard() {
  if (!$("#wizGauge")) return;
  const move = n => wizGo(n);
  // 홈의 시작하기 — 아래로 스크롤이 아니라 **화면 전환**이다. 캔버스에서
  // 홈과 만들기는 다른 아트보드다.
  const openCreate = () => {
    $("#create").hidden = false;
    view("create");
    wizGo(1, false);
    window.scrollTo(0, 0);
  };
  const closeCreate = () => {
    $("#create").hidden = true;
    view("landing");
    window.scrollTo(0, 0);
  };
  const start = $("#startBtn");
  if (start) start.addEventListener("click", openCreate);
  const startTop = $("#startBtnTop");
  if (startTop) startTop.addEventListener("click", openCreate);
  // 1걸음의 약속: 사진과 이름은 필수다. 없이 넘어가려 하면 그 자리에서 말한다.
  const step1ok = () => {
    const form = $("#form");
    if (!photos.length) { toast("캐릭터 사진을 올려주세요"); return false; }
    if (!form.name.value.trim()) { toast("이름을 적어주세요"); form.name.focus(); return false; }
    return true;
  };
  $("#wizNext").addEventListener("click", () => {
    if (wizStep === 1 && !step1ok()) return;
    move(wizStep + 1);
  });
  $("#wizSkip").addEventListener("click", () => {
    if (wizStep === 1 && !step1ok()) return;
    move(wizStep + 1);
  });
  const back = () => {
    if (wizStep === 1) { closeCreate(); return; }   // 1걸음의 뒤 = 홈
    move(wizStep - 1);
  };
  $("#wizPrev").addEventListener("click", back);
  $("#wizBack").addEventListener("click", back);

  // 갈림길 — 고르는 것이 곧 다음 동작이다.
  //   스토리 모드: 여기서 바로 출발한다(더 물어볼 것이 없다).
  //   전문가 모드: 길이 3걸음 늘어나고 그 첫 걸음으로 넘어간다.
  $$(".fork-card").forEach(card => card.addEventListener("click", () => {
    const mode = card.dataset.mode;
    wizChoice = mode;
    setMode(mode);
    $$(".fork-card").forEach(c =>
      c.setAttribute("aria-pressed", String(c === card)));
    if (mode === "expert") { wizGo(WIZ_FORK + 1); return; }
    startRun();   // 1걸음을 지나야 갈림길에 오므로 필수값은 이미 있다
  }));

  wizGo(1, false);
}

/* ------------------------------------------------------------------ 제출 */

function collect() {
  const form = $("#form");
  const fields = {};
  $$("[data-field]", form).forEach(el => {
    if (el.value.trim()) fields[el.dataset.field] = el.value.trim();
  });
  return {
    name:       form.name.value.trim(),
    character:  form.character.value.trim(),
    photo_note: form.photo_note.value.trim(),
    fields,
    genre:      form.genre.value.trim(),
    world:      form.world.value.trim(),
    world_preset: form.world_preset ? form.world_preset.value : "",
    story:      form.story.value.trim(),
    style:      form.style.value,
    // "" | sd | md | ld. 빈 값이면 그림체가 정한 등신 그대로 간다.
    head_ratio: form.head_ratio ? form.head_ratio.value : "",
    // fast(한 장에 3컷) | webtoon(컷마다 한 장). 비우면 fast — 지금까지의 방식이다.
    layout_mode: form.layout_mode ? form.layout_mode.value : "fast",
    // 지금은 **항상 미리보기**다. 한 화를 통째로 굽기 전에 앞 3컷을 먼저
    // 보여주고, 마음에 들면 이어서 그린다(/api/runs/<id>/continue).
    preview:    true,
    // 전문 모드인가. 서버는 이 한 값으로 어디서 멈출지(checkpoints)와 무엇을
    // 고를 수 있는지를 정한다. 이번 실행에 박히므로, 도는 도중에 모드를
    // 바꿔도 이 작업의 검수 지점은 안 바뀐다.
    expert:     isExpert(),
    // 전문 모드에서만 읽힌다 — 일반 모드는 서버가 기본값(2)으로 되돌린다.
    art_qa_regen_max: form.art_qa_regen_max
      ? Number(form.art_qa_regen_max.value) : 2,
    photos_data: photos,
  };
}

/* 실제로 출발시킨다. 갈림길에서 "스토리 모드"를 고른 것과 마지막 걸음에서
   "웹툰 만들기"를 누른 것이 같은 일을 하므로, 단추 핸들러가 아니라 이 함수를
   양쪽이 함께 부른다. 실패하면 그 자리에 남아서 이유를 보여준다 — 여기서
   진행 화면으로 넘어가 버리면 무엇이 잘못됐는지 볼 자리가 없다. */
async function startRun() {
  const btn = $("#submitBtn"), note = $("#submitNote");
  const fork = $$(".fork-card");
  note.hidden = false;
  note.classList.remove("error");
  note.textContent = "루가 바다로 나가는 중…";
  btn.disabled = true;
  fork.forEach(c => { c.disabled = true; });
  try {
    const res = await fetch("/api/create", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(collect()),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "시작하지 못했습니다");
    jobId = data.id;
    sessionStorage.setItem("lore_job", jobId);
    shownCuts = new Set();
    startPolling();
  } catch (err) {
    note.textContent = err.message;
    note.classList.add("error");
  } finally {
    btn.disabled = false;
    fork.forEach(c => { c.disabled = false; });
    paintCost();
  }
}

function submit(e) { e.preventDefault(); startRun(); }

/* ------------------------------------------------------------------ 진행 */

function startPolling() {
  view("running");
  $("#progress").hidden = false;
  $("#result").hidden = true;
  lastStatus = null;
  tick();
  clearInterval(poll);
  poll = setInterval(tick, 800);
}

async function tick() {
  if (!jobId) return;
  let state;
  try {
    const res = await fetch(`/api/jobs/${jobId}`);
    if (res.status === 404) { forget(); return; }
    state = await res.json();
  } catch { return; }             // 잠깐 끊긴 것뿐이면 다음 번에 다시 받는다

  renderProgress(state);

  if (state.status === "done") {
    clearInterval(poll); poll = null;
    showResult();
  } else if (state.status === "error" || state.status === "cancelled") {
    clearInterval(poll); poll = null;
    renderFailure(state);
  }
  lastStatus = state.status;
}

function mmss(sec) {
  const s = Math.max(0, Math.round(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

function renderProgress(s) {
  $("#clock").textContent = mmss(s.elapsed);

  // 검수 화면은 **그 작업이 시작될 때의 모드**를 따른다 (localStorage 가 아니라
  // 서버가 준 s.expert). 도는 중에 모드를 바꾼 사람에게, 시작할 때 약속한 것과
  // 다른 화면이 뜨면 안 된다.
  const jobExpert = !!s.expert;
  const sheetEdit = $(".sheet-approval-edit");
  if (sheetEdit) sheetEdit.hidden = !jobExpert;
  // 일반 모드에는 외형 편집 폼이 없으니 "수정 반영해서" 라고 하면 안 맞는다 —
  // 그 모드의 다시 만들기는 시트를 한 번 더 뽑는 것이다.
  $("#sheetRetryBtn").textContent =
    jobExpert ? "수정 반영해서 다시 만들기" : "다시 만들기";

  const approvalBox = $("#sheetApproval");
  if (s.status === "awaiting_sheet_approval") {
    approvalBox.hidden = false;
    // 매번 새로 그리지 않는다 — '다시 만들기'로 두 번째 시트가 나왔을 때만
    // 이미지 src 를 바꾼다. no-store 라 캐시는 안 걸리지만, 같은 문자열로
    // src 를 다시 대입하면 브라우저가 재요청하지 않는 경우가 있어 캐시
    // 버스터를 붙인다.
    if (lastStatus !== "awaiting_sheet_approval") {
      const v = Date.now();
      $("#approvalSheet").src = `/api/jobs/${jobId}/sheet?v=${v}`;
      const photoBox = $("#approvalPhotoBox");
      if (s.has_photo) {
        photoBox.hidden = false;
        $("#approvalPhoto").src = `/api/jobs/${jobId}/photo?v=${v}`;
      } else {
        photoBox.hidden = true;
      }
      setSheetButtonsBusy(false);
      loadSheetFields();
    }
  } else {
    approvalBox.hidden = true;
  }

  // 사람 확인이 필요한 이유 — 그 단계가 남긴 note 를 그대로 보여준다.
  // "멈췄습니다"만 뜨고 왜 멈췄는지 안 보이면 사용자가 판단할 근거가 없다.
  const currentStage = s.stages && s.stages[s.stage_index];
  const stageReason = (currentStage && currentStage.note) || "";

  const storyApprovalBox = $("#storyApproval");
  if (s.status === "awaiting_story_approval") {
    storyApprovalBox.hidden = false;
    $("#storyApprovalReason").textContent = stageReason;
    if (lastStatus !== "awaiting_story_approval") {
      setStoryButtonsBusy(false);
      wireMemory(s.run_id);
    }
  } else {
    storyApprovalBox.hidden = true;
  }

  const boardApprovalBox = $("#boardApproval");
  if (s.status === "awaiting_board_approval") {
    boardApprovalBox.hidden = false;
    $("#boardApprovalReason").textContent = stageReason;
    if (lastStatus !== "awaiting_board_approval") {
      setBoardButtonsBusy(false);
      wireMemory(s.run_id);
    }
  } else {
    boardApprovalBox.hidden = true;
  }

  const artqaBox = $("#artqaApproval");
  if (s.status === "awaiting_artqa_approval") {
    artqaBox.hidden = false;
    if (lastStatus !== "awaiting_artqa_approval") {
      renderArtQa(s.art_qa || {});
      $("#artqaApproveBtn").disabled = false;
    }
    // 이 자리에서는 중단할 것이 없다 — 그림은 이미 다 나왔다. 버튼을 남겨 두면
    // "다 만든 것을 버리는 버튼" 으로 읽힌다(서버는 어느 쪽이든 완성으로
    // 끝내지만, 누르는 사람은 그걸 모른다).
    $("#cancelBtn").hidden = true;
  } else {
    artqaBox.hidden = true;
    $("#cancelBtn").hidden = false;
  }

  if (s.status === "queued") {
    $("#progEyebrow").textContent = "대기 중";
    $("#progTitle").textContent = "앞에 만들고 있는 작품이 있습니다";
    $("#progSub").textContent =
      `한 번에 한 편씩 만듭니다 — 앞에 ${s.queue_position}편이 있습니다.`;
  } else if (s.status === "running") {
    $("#progEyebrow").textContent = `${s.style_label}${s.preview ? " · 미리보기" : ""}`;
    $("#progTitle").textContent = "웹툰을 만들고 있습니다";
    const art = s.art;
    $("#progSub").textContent = art && art.eta_sec
      ? `그림 단계입니다 — 남은 시간 약 ${mmss(art.eta_sec)}.`
      : "지금 무엇을 하고 있는지 아래에 그대로 보여드립니다.";
  } else if (s.status && s.status.startsWith("awaiting_")) {
    // 멈춰서 기다리는 중인데 "만들고 있습니다" 가 그대로 떠 있으면, 사용자는
    // 자기 차례인 줄 모르고 계속 기다린다 — 실제로 아무것도 안 돌아간다.
    $("#progEyebrow").textContent = "확인이 필요합니다";
    $("#progTitle").textContent = "잠깐 봐 주세요";
    $("#progSub").textContent = s.status === "awaiting_artqa_approval"
      ? "그림은 다 나왔습니다. 검수 결과만 확인하면 끝납니다."
      : "아래에서 확인하고 넘어가 주세요 — 그동안은 아무것도 안 돌아갑니다.";
  }

  paintMascot(s, currentStage);
  paintRefusals(s.refusals);

  $("#rail").innerHTML = s.stages.map((st, i) => {
    const num = String(i + 1).padStart(2, "0");
    const mark = st.state === "done" ? "✓" : st.state === "error" ? "!" : num;
    const steps = st.steps.filter(x => x.state !== "skip").map(x => `
      <li data-state="${x.state}">
        <span class="tick">${x.state === "done" ? "✓" : ""}</span>${x.label}
      </li>`).join("");
    const showSteps = st.state === "active" || st.state === "error";
    const bar = (st.key === "art" && s.art && s.art.total)
      ? `<div class="bar"><i style="width:${Math.round(s.art.done / s.art.total * 100)}%"></i></div>`
      : "";
    const time = st.seconds != null ? `${mmss(st.seconds)}` : "";
    return `
      <li class="stage" data-state="${st.state}">
        <span class="stage-dot">${mark}</span>
        <div class="stage-main">
          <h3>${st.title}</h3>
          <p class="stage-desc">${st.desc}</p>
          ${st.note && showSteps ? `<p class="stage-note">${esc(st.note)}</p>` : ""}
          ${showSteps ? `<ul class="steps">${steps}</ul>${bar}` : ""}
        </div>
        <span class="stage-time">${time}</span>
      </li>`;
  }).join("");

  // 그려진 장은 나오는 대로 보여준다 — 10분을 빈 화면으로 기다리게 하지 않는다.
  if (s.ready_cuts.length) {
    $("#cutstrip").hidden = false;
    $("#cutCount").textContent = s.art
      ? `${s.art.done} / ${s.art.total}장` : `${s.ready_cuts.length}장`;
    for (const n of s.ready_cuts) {
      if (shownCuts.has(n)) continue;
      shownCuts.add(n);
      const fig = document.createElement("figure");
      fig.innerHTML = `<img src="/api/jobs/${jobId}/page/${n}?w=260" alt="${n}번째 장" loading="lazy">
                       <figcaption>${n}</figcaption>`;
      $("#cutGrid").append(fig);
    }
  }

  $("#logBox").textContent = s.log.join("\n");
}

// ------------------------------------------------------------------ 거절
// 이미지 모델이 "못 그리겠다"고 답한 장을 사용자에게 그대로 보여준다. 사유를
// 숨기고 "생성 실패"라고만 쓰면 사용자는 무엇을 고쳐야 할지 알 수 없다.
function paintRefusals(list) {
  const box = $("#refusals");
  if (!box) return;
  if (!list || !list.length) { box.hidden = true; return; }
  box.hidden = false;
  $("#refusalList").innerHTML = list.map(r => `
    <li class="refusal">
      <div class="refusal-top">
        <span class="refusal-code">${esc(r.reason)}</span>
        <span class="refusal-where">${r.cut_number != null
          ? `${esc(String(r.cut_number))}번째 ${esc(r.unit || "장")}` : ""}</span>
      </div>
      <p class="refusal-hint">${esc(r.hint)}</p>
      ${r.model_said ? `<p class="refusal-said">모델이 한 말 — ${esc(r.model_said)}</p>` : ""}
      ${r.description ? `<p class="refusal-desc">해당 장면 — ${esc(r.description)}</p>` : ""}
    </li>`).join("");
}

// ---------------------------------------------------------------- 마스코트
// 단계 key → 표정 + 한 줄. 사용자는 10분 가까이 이 화면을 본다. rail 은 무엇을
// 하는지 기계적으로 적고, 마스코트는 그걸 사람 말로 한 번 더 말한다.
/* 단계 문구는 **지금 실제로 무엇을 하는지**만 말한다. 여기에 "루가 춤추고
   있어요" 같은 걸 섞으면 진행 표시가 아니라 잡담이 된다 — 노는 것은 아래
   상호작용 자리에서 따로 한다. 문구는 레퍼런스(design-reference/loading/*)의
   말을 그대로 따른다. */
const MASCOT_MOODS = {
  story: ["write", "루가 이야기를 만들고 있어요"],
  sheet: ["draw", "루가 캐릭터를 디자인하고 있어요"],
  board: ["read", "루가 콘티를 짜고 있어요"],
  art:   ["draw", "루가 그림을 그리고 있어요"],
  bind:  ["read", "루가 완성도를 확인하고 있어요"],
};
const MASCOT_WAITING = "루가 확인을 기다리고 있어요";

/* 전체 진행률. 다섯 단계를 같은 무게로 치고, 그림 단계 안에서는 그린 장
   수(s.art.done/total)로 더 잘게 나눈다. 정확한 예측이 아니라 "움직이고
   있다"를 보여주는 숫자다 — 그래서 단계가 뒤로 돌아가도 숫자는 안 줄인다. */
let louPctShown = 0;
function louPercent(s) {
  const stages = (s.stages || []).filter(st => st.state !== "skip");
  if (!stages.length) return 0;
  let done = 0, frac = 0;
  stages.forEach((st, i) => {
    if (st.state === "done") done += 1;
    else if (i === s.stage_index) {
      if (st.key === "art" && s.art && s.art.total) frac = s.art.done / s.art.total;
      else frac = 0.35;              // 단계 중간쯤이라고 친다
    }
  });
  let pct = Math.round((done + frac) / stages.length * 100);
  if (s.status === "done") pct = 100;
  louPctShown = Math.max(louPctShown, Math.min(100, pct));
  return louPctShown;
}

function paintLouProgress(s) {
  const fill = $("#louBarFill"), label = $("#louPct");
  if (!fill) return;
  const pct = louPercent(s);
  fill.style.width = pct + "%";
  label.textContent = pct + "%";
  const box = $("#louProgress");
  if (box) box.setAttribute("aria-valuenow", String(pct));
}

function paintMascot(s, currentStage) {
  const box = $("#mascot");
  if (!box) return;
  let mood = "think";
  let line = "";

  if (s.status && s.status.startsWith("awaiting_")) {
    mood = "ask";
    line = MASCOT_WAITING;
  } else if (s.status === "done") {
    mood = "done";
    line = "루가 다 그렸어요!";
  } else if (s.status === "error" || s.status === "canceled") {
    mood = "error";
    line = s.status === "canceled" ? "루가 멈췄어요" : "루가 여기서 막혔어요";
  } else if (s.status === "queued") {
    mood = "think";
    line = "루가 차례를 기다리고 있어요";
  } else if (currentStage) {
    const hit = MASCOT_MOODS[currentStage.key];
    if (hit) [mood, line] = hit;
  }

  box.dataset.mood = mood;
  paintLouProgress(s);
  // 단계 key 도 함께 적는다 — 루는 단계마다 다른 그림을 쓴다(style.css 참고).
  // 사람 확인을 기다리는 중이면 단계 그림 대신 "물어보는" 얼굴이 맞으므로 비운다.
  if (currentStage && currentStage.key && mood !== "ask") box.dataset.stage = currentStage.key;
  else delete box.dataset.stage;
  $("#mascotLine").textContent = line;
}

/* ---- 루와 놀기 ------------------------------------------------------- *
 *
 * 10분 가까이 이 화면을 본다. rail 은 무엇을 하는지 기계적으로 적고,
 * 마스코트 줄은 그걸 사람 말로 한 번 더 말한다. 여기 있는 것은 세 번째 —
 * **만질 수 있다**는 것. 기다림이 구경거리가 되면 시간이 덜 길다.
 *
 * 반응은 화면에만 있다. 서버로 아무것도 안 보내고, 돌고 있는 작업에도
 * 영향을 주지 않는다. 반응이 끝나면 원래 단계 그림으로 돌아간다. */


/* 루와 노는 자리의 실제 동작은 web/lou-play.js 에 있다 — 기다리는 화면(index)과
   화면 구경(demo)이 같은 코드를 쓰게 하려고 뺐다. 여기서는 setupLou() 만 부른다.
   (예전에는 demo.html 이 같은 로직을 통째로 베껴 갖고 있어서 둘이 갈라졌다.) */

function setSheetButtonsBusy(busy) {
  $("#sheetApproveBtn").disabled = busy;
  $("#sheetRetryBtn").disabled = busy;
}

// 승인 화면이 뜰 때마다 현재 p1.json 값을 수정 폼에 채운다. 실패해도(아직
// run_id 가 없거나 p1.json 이 없거나) 폼을 빈 채로 두고 그냥 넘어간다 —
// 수정은 선택 사항이라 이것 때문에 승인 화면 자체를 막지 않는다.
async function loadSheetFields() {
  if (!jobId) return;
  try {
    const res = await fetch(`/api/jobs/${jobId}/sheet-fields`);
    if (!res.ok) return;
    const f = await res.json();
    $("#sheetEditName").value = f.name || "";
    $("#sheetEditAppearance").value = f.appearance_en || "";
    $("#sheetEditDetails").value = (f.design_details || []).join("\n");
  } catch (err) {
    // 조용히 무시 — 수정 폼은 선택 사항이다.
  }
}

function sheetEditFields() {
  return {
    name: $("#sheetEditName").value,
    appearance_en: $("#sheetEditAppearance").value,
    design_details: $("#sheetEditDetails").value
      .split("\n").map(s => s.trim()).filter(Boolean),
  };
}

async function sendSheetDecision(decision) {
  if (!jobId) return;
  setSheetButtonsBusy(true);
  try {
    // 고른 항목·적은 말은 approve 에도 보낸다 — "이대로 진행"을 누르면서도
    // 불만은 적는 사람이 있고, 그게 다음 판을 고칠 근거가 된다.
    const body = { decision, ...fbRead(fbStageBox("sheet")) };
    // 수정한 값은 approve 에는 의미가 없다 — 이미 채택한 그림을 텍스트만
    // 바꿔서 바꿀 수는 없으므로, 반영하려면 retry 로 다시 그려야 한다.
    if (decision === "retry") body.fields = sheetEditFields();
    const res = await fetch(`/api/jobs/${jobId}/sheet-decision`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "전달하지 못했습니다");
    fbClear(fbStageBox("sheet"));
    // 다음 tick() 이 새 상태를 받아 화면을 바꾼다 — 여기서 직접 안 바꾼다.
  } catch (err) {
    toast(err.message);
    setSheetButtonsBusy(false);
  }
}

function setStoryButtonsBusy(busy) {
  $("#storyApproveBtn").disabled = busy;
  $("#storyRetryBtn").disabled = busy;
}

async function sendStoryDecision(decision) {
  if (!jobId) return;
  setStoryButtonsBusy(true);
  try {
    const res = await fetch(`/api/jobs/${jobId}/story-decision`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ decision, ...fbRead(fbStageBox("story")) }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "전달하지 못했습니다");
    fbClear(fbStageBox("story"));
    // 다음 tick() 이 새 상태를 받아 화면을 바꾼다 — 여기서 직접 안 바꾼다.
  } catch (err) {
    toast(err.message);
    setStoryButtonsBusy(false);
  }
}

function setBoardButtonsBusy(busy) {
  $("#boardApproveBtn").disabled = busy;
  $("#boardRetryBtn").disabled = busy;
}

async function sendBoardDecision(decision) {
  if (!jobId) return;
  setBoardButtonsBusy(true);
  try {
    const res = await fetch(`/api/jobs/${jobId}/board-decision`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ decision, ...fbRead(fbStageBox("board")) }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "전달하지 못했습니다");
    fbClear(fbStageBox("board"));
    // 다음 tick() 이 새 상태를 받아 화면을 바꾼다 — 여기서 직접 안 바꾼다.
  } catch (err) {
    toast(err.message);
    setBoardButtonsBusy(false);
  }
}

/* ---- 그림 검수 확인 (전문 모드) --------------------------------------- */

function qaItems(list, render) {
  return list.map(render).join("");
}

function renderArtQa(qa) {
  const fixed = qa.fixed || [], unresolved = qa.unresolved || [];
  const checked = qa.checked || 0;

  const parts = [`장 ${checked}개를 검수했습니다.`];
  if (fixed.length) parts.push(`${fixed.length}개는 다시 그려서 고쳤습니다.`);
  if (unresolved.length) parts.push(`${unresolved.length}개는 못 고쳤습니다.`);
  if (!fixed.length && !unresolved.length) parts.push("걸린 것은 없습니다.");
  $("#artqaSummary").textContent = parts.join(" ");

  const unresolvedBox = $("#artqaUnresolvedBox");
  unresolvedBox.hidden = !unresolved.length;
  $("#artqaUnresolved").innerHTML = qaItems(unresolved, u => `
    <li>
      <b>${esc(u.scene)}장</b>
      <span class="qa-rounds">${u.rounds ? `${esc(u.rounds)}번 다시 그림` : "다시 안 그림"}</span>
      <ul class="qa-issues">
        ${(u.issues || []).map(i => `<li>${esc(i.what)}</li>`).join("")}
      </ul>
    </li>`);

  const fixedBox = $("#artqaFixedBox");
  fixedBox.hidden = !fixed.length;
  $("#artqaFixed").innerHTML = qaItems(fixed, f => `
    <li><b>${esc(f.scene)}장</b>
      <span class="qa-rounds">${esc(f.rounds)}번 다시 그려서 통과</span></li>`);
}

async function sendArtqaDecision() {
  if (!jobId) return;
  const btn = $("#artqaApproveBtn");
  btn.disabled = true;
  // 이 화면의 상자를 직접 집는다 — data-fb-stage="scene" 은 결과 화면의 장별
  // 다시 그리기 상자도 쓰므로, fbStageBox("scene") 은 엉뚱한 것을 집을 수 있다.
  const box = $(".fb-box", $("#artqaApproval"));
  try {
    const res = await fetch(`/api/jobs/${jobId}/artqa-decision`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(fbRead(box)),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "전달하지 못했습니다");
    fbClear(box);
    // 다음 tick() 이 done 을 받아 결과 화면으로 넘긴다.
  } catch (err) {
    toast(err.message);
    btn.disabled = false;
  }
}

function renderFailure(s) {
  $("#progEyebrow").textContent = s.status === "cancelled" ? "중단됨" : "실패";
  $("#progTitle").textContent = s.status === "cancelled"
    ? "중단했습니다" : "여기서 멈췄습니다";
  $("#progSub").textContent = s.error || "알 수 없는 오류";
  $("#clockLabel").textContent = "걸린 시간";
  $("#cancelBtn").textContent = "처음으로";
  $("#cancelBtn").onclick = forget;      // 실패한 작업을 계속 붙들고 있지 않는다
}

/* ------------------------------------------------------------------ 결과 */

/* 결과 화면이 그림과 내려받기를 **어디서** 가져오는가.
 *
 * 같은 완성본을 두 길로 연다: 방금 만든 것은 작업(job)으로, "내 웹툰" 목록에서
 * 고른 것은 run_id 로. 그림과 대사는 같고 주소만 다르므로, 주소를 만드는 함수만
 * 갈아 끼우고 그리는 코드는 하나로 둔다. */
let resultSrc = null;

function jobSource(id) {
  return {
    page: (no, w = 1080) => `/api/jobs/${id}/page/${no}?w=${w}`,
    download: `/api/jobs/${id}/episode.png`,
  };
}
function runSource(runId, ep) {
  const q = `ep=${ep}`;
  return {
    page: (no, w = 1080) =>
      `/api/runs/${encodeURIComponent(runId)}/page/${no}?w=${w}&${q}`,
    download: `/api/runs/${encodeURIComponent(runId)}/episode.png?${q}`,
  };
}

async function showResult(attempt = 0) {
  resultSrc = jobSource(jobId);
  const res = await fetch(`/api/jobs/${jobId}/result`);
  const r = await res.json();
  if (!r.pages || !r.pages.length) {
    // 작업이 done 이 된 직후라 그림 파일이 아직 다 씌어지지 않았을 수 있다.
    // 예전에는 여기서 한 번 비면 화면이 그대로 멈췄다 — 다 만들어 놓고도
    // 못 보는 상태가 되고, 새로고침 말고는 빠져나갈 길이 없었다.
    if (attempt < 4) {
      $("#progSub").textContent = "컷을 불러오는 중입니다…";
      setTimeout(() => showResult(attempt + 1), 900 * (attempt + 1));
      return;
    }
    // 그래도 비면 **막다른 길로 두지 않는다.** 다시 시도할 단추와, 이미 그려진
    // 것이 있으면 편집실로 바로 갈 길을 준다.
    const sub = $("#progSub");
    sub.innerHTML =
      "완성했지만 컷을 읽지 못했습니다. " +
      `<button type="button" class="btn btn-quiet btn-sm" id="retryResult">다시 불러오기</button>` +
      (r.run_id
        ? ` <a class="btn btn-quiet btn-sm" href="/editor?run=${encodeURIComponent(r.run_id)}">편집실에서 열기</a>`
        : "");
    document.getElementById("retryResult")?.addEventListener(
      "click", () => showResult(0));
    return;
  }
  paintResult(r);
}

/* 목록에서 고른 완성본. 작업(job)을 거치지 않으므로 하네스를 직접 돌린 회차나
   이어 만들어 job 기록이 없는 회차도 똑같이 열린다 (초롱 2화가 그랬다). */
async function showRunResult(runId, ep) {
  resultSrc = runSource(runId, ep);
  let r;
  try {
    const res = await fetch(
      `/api/runs/${encodeURIComponent(runId)}/result?ep=${ep}`);
    r = await res.json();
    if (!res.ok) throw new Error(r.error || "열지 못했습니다");
  } catch (err) {
    return toast(err.message);
  }
  if (!r.pages || !r.pages.length) {
    return toast(`${ep}화는 아직 그려진 장이 없습니다.`);
  }
  // 이 회차를 방금 만든 작업이 아니므로, 결과 화면에 남아 있던 job 을 끊는다 —
  // 안 끊으면 "새로 만들기" 나 새로고침이 엉뚱한 작업으로 돌아간다.
  jobId = null;
  sessionStorage.removeItem("lore_job");
  paintResult(r);
  history.replaceState(null, "",
    `/works?run=${encodeURIComponent(runId)}&ep=${ep}`);
}

function paintResult(r) {
  $("#resGenre").textContent  = [r.genre, r.style_label].filter(Boolean).join(" · ");
  $("#resTitle").textContent  = r.title;
  $("#resLogline").textContent = r.logline || r.intro || "";
  const short = r.preview && r.planned_pages > r.page_count
    ? ` · 미리보기 (콘티 ${r.planned_pages}장 중 앞 ${r.page_count}장만 그렸습니다)` : "";
  // 얼마나 걸렸는지는 결과에도 남긴다 — 다음에 또 만들 때 기다릴 시간을
  // 가늠하는 유일한 근거다. 단계별 내역은 title 로 붙여 둔다.
  const took = r.seconds ? ` · ${mmss(r.seconds)} 걸림` : "";
  const epNo = r.episode || 1;
  $("#resSub").textContent =
    `${r.character ? r.character + " · " : ""}${epNo}화 · ${r.page_count}장 / ${r.cut_count}컷` +
    ` · 한 장에 ${r.cuts_per_sheet}컷${short}${took}`;
  $("#resSub").title = (r.stage_times || [])
    .map(s => `${s.title} ${mmss(s.seconds)}`).join("  ·  ");
  $("#downloadBtn").href = resultSrc.download;

  // 장은 틈 없이 이어 붙인다 — episode.png 를 만드는 방식과 같게(episode.stitch).
  // 컷 사이 호흡은 이제 한 장 안에서 모델이 정하므로 여기서 넣을 여백이 없다.
  resultRunId = r.run_id || "";
  resultEpisode = epNo;
  wireMemory(resultRunId);
  $("#reader").innerHTML = r.pages.map(pg => `
    <div class="page" data-scene="${pg.no}">
      <img class="cut-img" src="${resultSrc.page(pg.no)}"
           alt="${pg.no}번째 장" loading="lazy">
      ${resultRunId ? pageTools(pg.no) : ""}
    </div>
  `).join("");
  if (resultRunId) {
    wireRegen();
    r.pages.forEach(pg => paintVersions(pg.no));
    paintArtQA();
  }

  $("#scriptBody").innerHTML = r.pages.map(pg => `
    <div class="script-page">
      <div class="script-page-no">${pg.no}번째 장 · 컷 ${pg.cuts.map(c => c.no).join("·")}</div>
      ${pg.cuts.map(scriptCut).join("")}
    </div>`).join("");

  paintEpisodeTabs(r);
  // 편집실 링크도 지금 보고 있는 작품·회차로 맞춘다. 예전에는 늘 목업으로
  // 갔다 — 다 만들어 놓고 "편집실에서 열기"를 누르면 남의 샘플이 떴다.
  $("#editorLink").href = resultRunId
    ? `/editor?run=${encodeURIComponent(resultRunId)}&ep=${epNo}` : "/editor";

  // 이어 만들기 단추 — 그린 작품이 있어야 뜻이 있다.
  nextEpCtx = resultRunId
    ? { runId: resultRunId, next: r.next_episode || (epNo + 1),
        character: r.character || "", title: r.title || "" }
    : null;
  $("#nextEpBtn").hidden = !nextEpCtx;
  if (nextEpCtx) $("#nextEpBtn").textContent = `${nextEpCtx.next}화 만들기`;

  // 이어 그리기 — 콘티에 아직 안 그린 컷이 남아 있을 때만 뜬다.
  // 한 화를 통째로 굽지 않고 앞부분부터 보여주는 것이 지금의 방식이라,
  // 대부분의 결과 화면에는 이 단추가 있다.
  moreCtx = (resultRunId && r.more_cuts)
    ? { runId: resultRunId, episode: epNo,
        drawn: r.drawn_units || 0, total: r.planned_cuts || 0 }
    : null;
  $("#moreCutsBtn").hidden = !moreCtx;
  if (moreCtx) {
    const shown = moreCtx.drawn * Number(r.cuts_per_sheet || 3);
    $("#moreCutsBtn").textContent =
      `다음 장면 이어서 보기 (${shown}/${moreCtx.total}컷)`;
  }

  view("result");
  $("#progress").hidden = true;
  $("#works").hidden = true;
  $("#result").hidden = false;
  window.scrollTo(0, 0);
}

/* ---- 그림 QA — 검수가 잡았지만 못 고친 것 ------------------------------
 *
 * 하네스가 그리면서 명백한 실패(작화 사고 · 서술과 다른 인원/대상/배경)를
 * 검수하고 한도 안에서 다시 그린다. 그래도 남은 것이 여기로 온다 — 검수는
 * "틀렸다"까지만 알고 "어떻게 고칠지"는 사용자가 아니까, 표시하고 다시
 * 그리기(피드백 창)로 잇는 것이 이 화면의 몫이다.
 * QA 를 안 켠 예전 run 은 빈 응답이라 아무것도 안 뜬다. */
async function paintArtQA() {
  let scenes;
  try {
    scenes = (await (await fetch(
      `/api/runs/${encodeURIComponent(resultRunId)}/art-qa?ep=${resultEpisode}`
    )).json()).scenes || {};
  } catch { return; }                      // 못 읽으면 표시만 빠진다
  for (const [no, rec] of Object.entries(scenes)) {
    if (!rec.issues || !rec.issues.length) continue;
    const page = $(`#reader .page[data-scene="${no}"]`);
    if (!page || $(".qa-note", page)) continue;
    const note = document.createElement("div");
    note.className = "qa-note";
    note.innerHTML =
      `<b>검수에서 잡았지만 못 고친 것</b>` +
      (rec.rounds ? `<small> — ${rec.rounds}번 다시 그려 봤습니다</small>` : "") +
      `<ul>${rec.issues.map(i => `<li>${esc(i.what)}</li>`).join("")}</ul>` +
      `<button type="button" class="btn btn-quiet btn-sm js-qa-regen">직접 고치기 — 다시 그리기</button>`;
    // 도구 줄 바로 뒤에 끼운다 — 그림 밑, 판 목록 위.
    const tools = $(".page-tools", page);
    if (tools) tools.insertAdjacentElement("afterend", note);
    else page.append(note);
    $(".js-qa-regen", note).addEventListener("click", () => {
      const box = $(".regen-box", page);
      if (!box) return;
      box.hidden = false;
      // 검수가 찾은 말을 피드백 칸에 미리 실어 준다 — 빈손으로 다시 그리면
      // 같은 분포에서 랜덤 뽑기라, 문제를 명시하는 쪽이 방향이 생긴다.
      const text = $(".js-regen-note", box);
      if (text && !text.value.trim()) {
        text.value = rec.issues.map(i => i.what).join(" / ").slice(0, 480);
      }
      box.scrollIntoView({ behavior: "smooth", block: "center" });
      text?.focus();
    });
  }
}

/* 회차 탭. 한 편밖에 없으면 안 그린다 — 고를 것이 없는 자리에 고르개를 두면
   "여기 뭔가 더 있나" 하고 누르게 된다. */
function paintEpisodeTabs(r) {
  const host = $("#resEpisodes");
  const eps = r.episodes || [];
  const cur = r.episode || 1;
  if (!r.run_id || eps.length < 2) { host.hidden = true; host.innerHTML = ""; return; }
  host.hidden = false;
  host.innerHTML = eps.map(n =>
    `<button type="button" class="ep-tab" data-ep="${n}"` +
    `${n === cur ? ' aria-current="true"' : ""}>${n}화</button>`).join("");
  $$(".ep-tab", host).forEach(b => b.addEventListener("click", () => {
    if (b.getAttribute("aria-current") === "true") return;
    showRunResult(r.run_id, Number(b.dataset.ep));
  }));
}

/* ------------------------------------------------- 장 다시 그리기 (#59)
 *
 * 그림은 컷이 아니라 **장 단위**로 굽는다 — 한 장에 3컷이 함께 그려지므로
 * "컷 하나만" 다시 뽑는 길은 없다. 다시 그리는 최소 단위가 장이다.
 *
 * 크레딧 차감은 없다 (#16 이 백로그). 실제 API 비용은 나간다. */

let resultRunId = "";
// 지금 결과 화면이 몇 화인가. 다시 그리기·되돌리기·판 목록이 전부 이 값을
// 보내야 한다 — 안 보내면 서버가 1화로 알아듣고 2화 화면을 보면서 **1화
// 그림을 덮어쓴다** (실제 코드 감사에서 발견).
let resultEpisode = 1;

function pageTools(no) {
  return `
    <div class="page-tools">
      <button type="button" class="btn btn-quiet btn-sm js-regen-open">이 장 다시 그리기</button>
    </div>
    <div class="regen-box fb-box" data-fb-stage="scene" hidden>
      <p class="fb-lead">무엇이 마음에 안 드나요?
        <small>고르면 다시 그릴 때 반영됩니다 — 안 골라도 됩니다</small></p>
      <div class="fb-tags"></div>
      <label class="field">
        <span>무엇을 고칠까요? <small>비워도 됩니다 — 그냥 한 번 더 그립니다</small></span>
        <textarea rows="2" class="js-regen-note fb-text" maxlength="500"
          placeholder="예: 표정을 더 밝게 / 배경을 밤으로 / 인물을 왼쪽에"></textarea>
      </label>
      <label class="check-line">
        <input type="checkbox" class="js-regen-textless">
        <span>말풍선 없이 그림만 다시 그리기 <small>말풍선까지 안 그립니다 — 대사는 나중에 편집실에서 얹으세요</small></span>
      </label>
      <div class="regen-actions">
        <button type="button" class="btn btn-primary btn-sm js-regen-go">다시 그리기</button>
        <button type="button" class="btn btn-quiet btn-sm js-regen-cancel">닫기</button>
        <span class="regen-note js-regen-status"></span>
      </div>
    </div>
    <div class="page-versions" data-versions="${no}"></div>`;
}

function wireRegen() {
  $$("#reader .page").forEach(page => {
    const no  = Number(page.dataset.scene);
    const box = $(".regen-box", page);
    // 장마다 상자가 하나씩이라 항목도 장마다 새로 그린다.
    fbChips("scene", $(".fb-tags", box), $(".fb-text", box));
    $(".js-regen-open", page).addEventListener("click", () => {
      box.hidden = !box.hidden;
      if (!box.hidden) $(".js-regen-note", box).focus();
    });
    $(".js-regen-cancel", box).addEventListener("click", () => { box.hidden = true; });
    $(".js-regen-go", box).addEventListener("click", () => runRegen(no, box, page));
  });
}

async function runRegen(no, box, page) {
  const { tags, feedback } = fbRead(box);
  const textless = $(".js-regen-textless", box).checked;
  const status = $(".js-regen-status", page);
  const go     = $(".js-regen-go", page);
  go.disabled = true;
  status.textContent = "시작하는 중…";
  let job;
  try {
    const res = await fetch(
      `/api/runs/${encodeURIComponent(resultRunId)}/scenes/${no}/regen`,
      { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ feedback, textless, tags, episode: resultEpisode }) });
    job = await res.json();
    if (!res.ok) throw new Error(job.error || "시작하지 못했습니다");
    fbClear(box);
  } catch (err) {
    go.disabled = false;
    status.textContent = "";
    return toast(err.message);
  }

  // 폴링. 한 장 굽는 데 1~2분이라 2초면 충분하다.
  while (true) {
    await new Promise(r => setTimeout(r, 2000));
    let s;
    try { s = await (await fetch(`/api/regens/${job.id}`)).json(); }
    catch { continue; }                       // 잠깐 끊겨도 다음 번에 이어진다
    status.textContent = s.note || s.status;
    if (s.status === "done") {
      bustImage(page, no);
      paintVersions(no, s.versions);
      status.textContent = "새로 그렸습니다";
      toast(`${no}번째 장을 다시 그렸습니다`);
      break;
    }
    if (s.status === "error" || s.status === "cancelled") {
      // 실패해도 원래 그림은 서버가 되돌려 놓는다. 화면도 그대로 두면 된다.
      status.textContent = s.note || s.error || "실패했습니다";
      toast(s.error || "다시 그리지 못했습니다 — 원래 그림은 그대로입니다");
      break;
    }
  }
  go.disabled = false;
}

// 장마다 지금 그림을 마지막으로 새로 그린 시각. 판 목록의 "지금" 썸네일도
// 같은 값으로 캐시를 깨야 나란히 놓았을 때 옛 그림이 안 남는다.
const verBust = {};

/* 브라우저가 같은 주소를 캐시하므로, 새로 그려도 주소가 같으면 옛 그림이 뜬다. */
function bustImage(page, no) {
  verBust[no] = Date.now();
  const img = $(".cut-img", page);
  img.src = `${resultSrc.page(no)}&t=${verBust[no]}`;
}

/* 판 목록 — 고르는 자리가 아니라 **둘러보는** 자리다. 지금 그림과 지난 판을
 * 나란히 작게 늘어놓고, 아무 때나 눌러서 그때그때 바꿔 볼 수 있게 한다.
 * "새로 그린 걸 채택할지 고르세요" 모달을 만들지 않은 이유이기도 하다 —
 * 채택은 한 번뿐인 결정이 아니라, 나중에 다시 봐도 계속 바뀔 수 있는 것이다. */
async function paintVersions(no, versions) {
  const slot = $(`[data-versions="${no}"]`);
  if (!slot) return;
  if (!versions) {
    try {
      versions = (await (await fetch(
        `/api/runs/${encodeURIComponent(resultRunId)}/scenes/${no}/versions?ep=${resultEpisode}`)).json()).versions;
    } catch { return; }
  }
  if (!versions || !versions.length) { slot.innerHTML = ""; return; }
  const cur = `
    <span class="ver-thumb is-current" title="지금 걸린 그림">
      <img src="${resultSrc.page(no, 160)}&t=${verBust[no] || 0}" alt="지금 그림" loading="lazy">
      <span class="ver-label">지금</span>
    </span>`;
  const past = versions.map(v => `
    <button type="button" class="ver-thumb js-revert" data-v="${v.version}"
            title="이 판으로 바꾸기">
      <img src="/api/runs/${encodeURIComponent(resultRunId)}/scenes/${no}/versions/${v.version}?w=160&ep=${resultEpisode}"
           alt="v${v.version}" loading="lazy">
      <span class="ver-label">v${v.version}</span>
    </button>`).join("");
  slot.innerHTML = `
    <span class="ver-strip-label">지난 판 — 눌러서 바꿔 보기</span>
    <div class="ver-strip">${cur}${past}</div>`;
  $$(".js-revert", slot).forEach(btn => btn.addEventListener("click", async () => {
    btn.disabled = true;
    try {
      const res = await fetch(
        `/api/runs/${encodeURIComponent(resultRunId)}/scenes/${no}/revert`,
        { method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ version: Number(btn.dataset.v), episode: resultEpisode }) });
      const out = await res.json();
      if (!res.ok) throw new Error(out.error || "되돌리지 못했습니다");
      bustImage($(`#reader .page[data-scene="${no}"]`), no);
      paintVersions(no, out.versions);
      toast(`${no}번째 장을 v${btn.dataset.v} 로 바꿨습니다`);
    } catch (err) {
      toast(err.message);
    }
    btn.disabled = false;
  }));
}

function scriptCut(c) {
  const lines = [];
  if (c.narration) lines.push(`<p class="script-line narration">${esc(c.narration)}</p>`);
  if (c.dialogue)  lines.push(`<p class="script-line"><span class="who">${esc(c.speaker || "?")}</span> ${esc(c.dialogue)}</p>`);
  if (c.thought)   lines.push(`<p class="script-line thought">(${esc(c.thought)})</p>`);
  if (c.sfx)       lines.push(`<p class="script-line sfx">${esc(c.sfx)}</p>`);
  if (!lines.length) lines.push(`<p class="script-line narration">— 대사 없음</p>`);
  return `<div class="script-cut">
    <div class="script-no">CUT ${String(c.no).padStart(2, "0")}${c.shot ? " · " + esc(c.shot) : ""}</div>
    ${lines.join("")}
    <p class="script-desc">${esc(c.description)}</p>
  </div>`;
}


/* 이미 끝난 작업을 결과 화면으로 바로 연다 (진행 화면을 거치지 않는다). */
async function openExisting(id) {
  try {
    if (!id) {
      const res = await fetch("/api/latest");
      const d = await res.json();          // 본문은 한 번만 읽을 수 있다
      if (!res.ok) throw new Error(d.error || "없습니다");
      id = d.id;
    }
    const state = await (await fetch(`/api/jobs/${id}`)).json();
    if (state.error) throw new Error(state.error);
    jobId = id;
    sessionStorage.setItem("lore_job", jobId);
    if (state.status === "done") { await showResult(); return; }
    startPolling();                       // 아직 도는 중이면 진행 화면으로
  } catch (err) {
    toast(`${err.message} — 먼저 한 편 만들어 주세요.`);
    view("landing");
    window.scrollTo(0, 0);
  }
}

/* 이어 그리기 — 같은 화의 다음 3컷. 회차가 안 늘어나므로 다음 화 만들기와
   다른 자리다. 시작하면 진행 화면으로 넘어가고, 끝나면 결과가 다시 그려진다. */
async function continueCuts() {
  if (!moreCtx) return;
  const btn = $("#moreCutsBtn");
  btn.disabled = true;
  const label = btn.textContent;
  btn.textContent = "루가 이어 그리는 중…";
  try {
    const res = await fetch(
      `/api/runs/${encodeURIComponent(moreCtx.runId)}/continue`,
      { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ episode: moreCtx.episode }) });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "이어 그리지 못했습니다");
    jobId = data.id;
    sessionStorage.setItem("lore_job", jobId);
    shownCuts = new Set();
    startPolling();
  } catch (err) {
    toast(err.message);
  } finally {
    btn.disabled = false; btn.textContent = label;
  }
}

/* ------------------------------------------------- 다음 화 이어서 만들기 (#72)
 *
 * 1화용 진행 화면(#progress)을 쓰지 않는다. 이어 만들기는 도는 단계가 셋뿐이고
 * (콘티 · 그림 · 잇기), 이야기와 캐릭터 시트는 1화 것을 그대로 쓴다. 사람이
 * 궁금해하는 것도 다르다 — "인물이 그대로 따라오는가", "몇 화가 나오는가".
 *
 * 회차 번호는 **서버가 정한다.** 화면이 보낸 번호를 믿으면 창을 두 개 띄워
 * 놓고 눌렀을 때 같은 번호를 두 번 만들려 든다. */

let nextEpCtx = null;
let moreCtx = null;      // { runId, next, character, title }
let nextEpJob = null;      // 도는 중인 작업 id
let nextEpPoll = null;

function openNextEp() {
  if (!nextEpCtx) return;
  $("#nextEpWork").textContent = [nextEpCtx.character, nextEpCtx.title]
    .filter(Boolean).join(" · ");
  $("#nextEpTitle").textContent = `${nextEpCtx.next}화 만들기`;
  $("#nextEpSub").textContent =
    `${nextEpCtx.next - 1}화에 이어서 만듭니다. 이야기와 캐릭터는 다시 만들지 않습니다.`;
  $("#nextEpAsk").hidden = false;
  $("#nextEpRun").hidden = true;
  $("#nextEpNote").value = "";
  $("#nextEp").hidden = false;
  $("#result").hidden = true;
  view("nextep");
  window.scrollTo(0, 0);
}

function closeNextEp() {
  clearInterval(nextEpPoll); nextEpPoll = null; nextEpJob = null;
  $("#nextEp").hidden = true;
  $("#result").hidden = false;
  view("result");
}

async function startNextEp() {
  if (!nextEpCtx) return;
  const go = $("#nextEpGo");
  go.disabled = true;
  try {
    const res = await fetch(
      `/api/runs/${encodeURIComponent(nextEpCtx.runId)}/next-episode`,
      { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ author_note: $("#nextEpNote").value.trim() }) });
    const out = await res.json();
    if (!res.ok) throw new Error(out.error || "시작하지 못했습니다");
    nextEpJob = out.id;
    // 서버가 정한 번호로 맞춘다 — 화면이 짐작한 것과 다를 수 있다.
    if (out.episode) {
      nextEpCtx.next = out.episode;
      $("#nextEpTitle").textContent = `${out.episode}화 만들기`;
    }
    $("#nextEpAsk").hidden = true;
    $("#nextEpRun").hidden = false;
    nextEpPoll = setInterval(tickNextEp, 1500);
    tickNextEp();
  } catch (err) {
    toast(err.message);
  }
  go.disabled = false;
}

async function tickNextEp() {
  if (!nextEpJob) return;
  let s;
  try { s = await (await fetch(`/api/jobs/${nextEpJob}`)).json(); }
  catch { return; }                        // 잠깐 끊겨도 다음 번에 이어진다

  const stages = s.stages || [];
  $("#nextEpSteps").innerHTML = stages.map((st, i) => {
    const cls = st.state === "done" ? "is-done"
              : (i === s.stage_index ? "is-active" : "");
    return `<li class="${cls}"><span class="dot"></span>
      <span>${esc(st.title)}</span>
      <small style="margin-left:auto;color:var(--muted,#8a8a94)">${esc(st.desc || "")}</small>
    </li>`;
  }).join("");
  const cur = stages[s.stage_index] || {};
  $("#nextEpNote2").textContent = cur.note || s.error || "";

  if (s.status === "done") {
    clearInterval(nextEpPoll); nextEpPoll = null;
    // 완성본은 1화와 같은 결과 화면에서 본다 — 읽는 화면은 회차가 달라도 같다.
    jobId = nextEpJob;
    sessionStorage.setItem("lore_job", jobId);
    nextEpJob = null;
    $("#nextEp").hidden = true;
    toast(`${nextEpCtx.next}화가 나왔습니다`);
    showResult();
    return;
  }
  if (s.status === "error" || s.status === "cancelled") {
    clearInterval(nextEpPoll); nextEpPoll = null;
    $("#nextEpNote2").textContent = s.error || "만들지 못했습니다";
    $("#nextEpAsk").hidden = false;       // 다시 눌러 볼 수 있게 되돌린다
    nextEpJob = null;
  }
  // 콘티 승인이 필요한 상태 — 이어 만들기에서는 "다시 짜기" 를 못 한다
  // (스토리 하네스가 회차를 되돌리는 길을 아직 안 준다). 진행만 물어본다.
  if (s.status === "awaiting_board_approval") {
    $("#nextEpNote2").innerHTML =
      `${esc(s.stages?.[s.stage_index]?.note || "콘티를 확인해 주세요")}<br>` +
      `<button type="button" class="btn btn-primary btn-sm" id="nextEpApprove">이대로 진행</button>`;
    document.getElementById("nextEpApprove")?.addEventListener("click", async () => {
      await fetch(`/api/jobs/${nextEpJob}/board-decision`,
        { method: "POST", headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ decision: "approve" }) });
    }, { once: true });
  }
}

/* ------------------------------------------------- 내 웹툰 목록 (/works)
 *
 * 지금까지 만든 것을 볼 길이 편집실뿐이었다. 편집실은 **고치는 자리**라 도구가
 * 늘 곁에 붙어 있어서, 읽으려고 여는 곳으로는 맞지 않았다. 여기는 그냥 읽는
 * 자리다 — 고르면 완성본 화면(#result)이 그대로 열린다.
 *
 * 목록은 편집실과 같은 /api/runs 를 쓴다. 작업(job)을 안 거치므로 하네스를
 * 직접 돌린 것도, 이어 만들어 job 기록이 없는 회차도 빠짐없이 나온다. */

async function showWorks() {
  view("works");
  $("#progress").hidden = true;
  $("#result").hidden = true;
  $("#nextEp").hidden = true;
  $("#works").hidden = false;
  window.scrollTo(0, 0);

  const host = $("#worksGrid");
  host.innerHTML = `<p class="works-empty">불러오는 중…</p>`;
  let runs = null;
  try { runs = (await (await fetch("/api/runs")).json()).runs || []; }
  catch { /* 아래에서 */ }

  if (runs === null) {
    host.innerHTML = `<p class="works-empty">목록을 불러오지 못했습니다.` +
      ` 서버(serve.py)가 떠 있는지 확인해 주세요.</p>`;
    return;
  }
  if (!runs.length) {
    host.innerHTML = `<p class="works-empty">아직 만든 웹툰이 없습니다.` +
      `<br><a class="inline-link" href="/#studio">첫 작품 만들러 가기 →</a></p>`;
    return;
  }
  host.innerHTML = runs.map(workCard).join("");
  // 회차 단추가 카드 안에 있어서, 카드 자체를 누르면 첫 회차를 연다.
  $$("#worksGrid [data-open]", host).forEach(b => b.addEventListener("click", () =>
    showRunResult(b.dataset.open, Number(b.dataset.ep))));
}

function workCard(r) {
  const eps = r.episodes || [];
  const first = eps[0] || 1;
  const cover = r.cover_page
    ? `<img src="/api/runs/${encodeURIComponent(r.run_id)}/page/${r.cover_page}` +
      `?w=320&ep=${r.cover_episode || first}" alt="" loading="lazy">`
    : `<span class="works-cover-empty" aria-hidden="true">🖼</span>`;
  // 회차마다 단추를 준다 — "몇 편이 있다"를 세는 것과 "그 편을 연다"가 같은
  // 자리에 있어야, 2화가 있는데 1화만 열리는 일이 안 생긴다.
  const epBtns = eps.map(n =>
    `<button type="button" class="works-ep" data-open="${esc(r.run_id)}" data-ep="${n}">`
    + `${n}화</button>`).join("");
  return `
    <article class="works-card">
      <button type="button" class="works-cover" data-open="${esc(r.run_id)}"
              data-ep="${first}" aria-label="${esc(r.character || r.run_id)} 열기">
        ${cover}
      </button>
      <div class="works-body">
        <h3>${esc(r.character || "이름 없음")}</h3>
        <p class="works-sub">${esc([r.genre, r.title].filter(Boolean).join(" · "))}</p>
        <p class="works-count">${eps.length}편 · ${r.page_count}장</p>
        <div class="works-eps">${epBtns}</div>
        <a class="works-edit" href="/editor?run=${encodeURIComponent(r.run_id)}&ep=${first}">편집실에서 열기 →</a>
      </div>
    </article>`;
}

/* ------------------------------------------------------------------ 잡동사니 */

function view(name) { document.body.dataset.view = name; }
function esc(s) {
  return String(s ?? "").replace(/[&<>"]/g,
    ch => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[ch]));
}
function forget() {
  sessionStorage.removeItem("lore_job");
  jobId = null; clearInterval(poll); poll = null;
  shownCuts = new Set(); $("#cutGrid").innerHTML = ""; $("#cutstrip").hidden = true;
  $("#cancelBtn").textContent = "중단"; $("#cancelBtn").onclick = null;
  $("#clockLabel").textContent = "경과";
  view("landing"); $("#progress").hidden = true; $("#result").hidden = true;
  $("#works").hidden = true;
  $("#scriptPanel").hidden = true;
  // 이어 만들기 화면도 같이 닫는다 — 안 닫으면 "새로 만들기" 를 눌러도
  // 앞 작품의 다음 화 화면이 뒤에 남는다.
  clearInterval(nextEpPoll); nextEpPoll = null; nextEpJob = null; nextEpCtx = null;
  $("#nextEp").hidden = true; $("#nextEpBtn").hidden = true;
  $("#moreCutsBtn").hidden = true;
  // /result 로 들어왔으면 주소도 되돌린다 — 안 그러면 새로고침에 다시 결과가 뜬다.
  if (location.pathname !== "/" || location.search) history.replaceState(null, "", "/");
  document.querySelector("#studio").scrollIntoView();
}
let toastTimer = null;
function toast(msg) {
  const el = $("#toast");
  el.textContent = msg; el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 2600);
}

document.addEventListener("DOMContentLoaded", () => {
  buildForm();
  loadWorlds();
  loadFeedbackTags();
  setupPhoto();
  $("#form").addEventListener("submit", submit);
  // 연출(빠르게/웹툰)이 바뀌면 그림 호출 수가 달라져 비용도 달라진다.
  document.querySelectorAll('input[name="layout_mode"]').forEach(
    el => el.addEventListener("change", paintCost));

  // 모드 — 폼을 만든 **뒤에** 적용해야 한다(applyMode 가 폼의 칸을 여닫는다).
  applyMode();
  // 위저드도 폼을 만든 뒤에 켠다(그림체·항목 칸이 있어야 요약을 그릴 수 있다).
  setupWizard();
  setupLou();
  paintCost();

  $("#cancelBtn").addEventListener("click", async () => {
    if (!jobId || !confirm("만드는 것을 중단할까요? 지금까지 그린 컷은 남습니다.")) return;
    await fetch(`/api/jobs/${jobId}/cancel`, { method: "POST" });
  });
  $("#sheetApproveBtn").addEventListener("click", () => sendSheetDecision("approve"));
  $("#sheetRetryBtn").addEventListener("click", () => sendSheetDecision("retry"));
  $("#storyApproveBtn").addEventListener("click", () => sendStoryDecision("approve"));
  $("#storyRetryBtn").addEventListener("click", () => sendStoryDecision("retry"));
  $("#boardApproveBtn").addEventListener("click", () => sendBoardDecision("approve"));
  $("#boardRetryBtn").addEventListener("click", () => sendBoardDecision("retry"));
  $("#artqaApproveBtn").addEventListener("click", sendArtqaDecision);
  $("#againBtn").addEventListener("click", forget);
  $("#nextEpBtn").addEventListener("click", openNextEp);
  $("#moreCutsBtn").addEventListener("click", continueCuts);
  $("#nextEpGo").addEventListener("click", startNextEp);
  $("#nextEpBack").addEventListener("click", closeNextEp);
  $("#nextEpCancel").addEventListener("click", async () => {
    if (nextEpJob) {
      try { await fetch(`/api/jobs/${nextEpJob}/cancel`, { method: "POST" }); }
      catch { /* 이미 끝났을 수 있다 */ }
    }
    closeNextEp();
  });
  $("#scriptBtn").addEventListener("click", () => {
    $("#scriptPanel").hidden = !$("#scriptPanel").hidden;
  });
  $("#scriptClose").addEventListener("click", () => { $("#scriptPanel").hidden = true; });
  // 완성본에서 목록으로 되돌아가는 길. 목록을 다시 그리는 이유: 그 사이에
  // 이어 만든 회차가 생겼을 수 있다.
  $("#backToWorks").addEventListener("click", () => showWorks());

  // 주소로 바로 열기.
  //   /result                이미 만들어 둔 **마지막** 1화를 결과 화면으로
  //   /?job=<id>             그 작업을 결과 화면으로
  //   /works                 내가 만든 웹툰 목록
  //   /works?run=<id>&ep=N   그 작품의 그 회차를 완성본 화면으로
  // 폼을 거치지 않고 결과부터 보고 싶을 때가 있어서 둔 길이다.
  const params = new URLSearchParams(location.search);
  const asked = params.get("job");
  const wantResult = location.pathname.startsWith("/result");
  const wantWorks = location.pathname.startsWith("/works");
  if (wantWorks) {
    const run = params.get("run");
    if (run) showRunResult(run, Number(params.get("ep")) || 1);
    else showWorks();
  } else if (asked || wantResult) {
    openExisting(asked);
  } else if (jobId) {
    startPolling();          // 새로고침해도 돌던 작업으로 돌아온다
  }
});
