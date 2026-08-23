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
  $("#fieldsGrid").innerHTML = FIELD_KEYS.map(k => `
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
    $("#photoStrip").innerHTML = photos.map((src, i) => `
      <figure class="shot">
        <img src="${src}" alt="${i + 1}번째 사진">
        <button type="button" class="shot-x" data-i="${i}" aria-label="지우기">✕</button>
      </figure>`).join("");
    $$("#photoStrip .shot-x").forEach(b => b.addEventListener("click", e => {
      e.preventDefault(); e.stopPropagation();
      photos.splice(Number(b.dataset.i), 1); paint();
    }));
    drop.classList.toggle("has-photo", photos.length > 0);
    $("#photoCount").textContent = photos.length
      ? `${photos.length} / ${MAX_PHOTOS}장 · 같은 사람을 여러 각도로`
      : "";
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
  const preview = $("#previewToggle").checked;
  const base = preview ? CREDIT.preview : CREDIT.full;
  const cost = layoutMode() === "webtoon" ? base * WEBTOON_MULT : base;
  $("#costChip").textContent = `−${cost} 크레딧`;
  $("#submitBtn").firstChild.textContent =
    preview ? "미리보기 만들기 " : "웹툰 만들기 ";
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
    preview:    $("#previewToggle").checked,
    photos_data: photos,
  };
}

async function submit(e) {
  e.preventDefault();
  const btn = $("#submitBtn"), note = $("#submitNote");
  btn.disabled = true; btn.firstChild.textContent = "시작하는 중… ";
  note.classList.remove("error");
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
    btn.disabled = false; paintCost();
  }
}

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
    if (lastStatus !== "awaiting_story_approval") setStoryButtonsBusy(false);
  } else {
    storyApprovalBox.hidden = true;
  }

  const boardApprovalBox = $("#boardApproval");
  if (s.status === "awaiting_board_approval") {
    boardApprovalBox.hidden = false;
    $("#boardApprovalReason").textContent = stageReason;
    if (lastStatus !== "awaiting_board_approval") setBoardButtonsBusy(false);
  } else {
    boardApprovalBox.hidden = true;
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
const MASCOT_MOODS = {
  story: ["write", "이야기를 짜는 중이에요. 결말부터 거꾸로 세워 봅니다."],
  sheet: ["draw", "얼굴을 잡는 중이에요 — 여기가 흔들리면 뒤가 다 흔들려서요."],
  board: ["read", "컷을 나누는 중이에요. 어디서 넘길지 세어 봅니다."],
  art:   ["draw", "그리는 중이에요. 한 장씩 나오는 대로 아래에 올려 둘게요."],
  bind:  ["read", "한 편으로 잇는 중이에요. 거의 다 왔습니다."],
};
const MASCOT_WAITING = "확인해 주실 게 있어요 — 아래에서 골라 주세요.";

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
    line = "다 됐어요. 처음부터 한 번 읽어 보세요.";
  } else if (s.status === "error" || s.status === "canceled") {
    mood = "error";
    line = s.status === "canceled" ? "여기서 멈췄어요." : "여기서 막혔어요.";
  } else if (s.status === "queued") {
    mood = "think";
    line = "앞 작품이 끝나면 바로 시작할게요.";
  } else if (currentStage) {
    const hit = MASCOT_MOODS[currentStage.key];
    if (hit) [mood, line] = hit;
  }

  box.dataset.mood = mood;
  $("#mascotLine").textContent = line;
}

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

async function showResult(attempt = 0) {
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

  $("#resGenre").textContent  = [r.genre, r.style_label].filter(Boolean).join(" · ");
  $("#resTitle").textContent  = r.title;
  $("#resLogline").textContent = r.logline || r.intro || "";
  const short = r.preview && r.planned_pages > r.page_count
    ? ` · 미리보기 (콘티 ${r.planned_pages}장 중 앞 ${r.page_count}장만 그렸습니다)` : "";
  // 얼마나 걸렸는지는 결과에도 남긴다 — 다음에 또 만들 때 기다릴 시간을
  // 가늠하는 유일한 근거다. 단계별 내역은 title 로 붙여 둔다.
  const took = r.seconds ? ` · ${mmss(r.seconds)} 걸림` : "";
  $("#resSub").textContent =
    `${r.character ? r.character + " · " : ""}1화 · ${r.page_count}장 / ${r.cut_count}컷` +
    ` · 한 장에 ${r.cuts_per_sheet}컷${short}${took}`;
  $("#resSub").title = (r.stage_times || [])
    .map(s => `${s.title} ${mmss(s.seconds)}`).join("  ·  ");
  $("#downloadBtn").href = `/api/jobs/${jobId}/episode.png`;

  // 장은 틈 없이 이어 붙인다 — episode.png 를 만드는 방식과 같게(episode.stitch).
  // 컷 사이 호흡은 이제 한 장 안에서 모델이 정하므로 여기서 넣을 여백이 없다.
  resultRunId = r.run_id || "";
  $("#reader").innerHTML = r.pages.map(pg => `
    <div class="page" data-scene="${pg.no}">
      <img class="cut-img" src="/api/jobs/${jobId}/page/${pg.no}?w=1080"
           alt="${pg.no}번째 장" loading="lazy">
      ${resultRunId ? pageTools(pg.no) : ""}
    </div>
  `).join("");
  if (resultRunId) {
    wireRegen();
    r.pages.forEach(pg => paintVersions(pg.no));
  }

  $("#scriptBody").innerHTML = r.pages.map(pg => `
    <div class="script-page">
      <div class="script-page-no">${pg.no}번째 장 · 컷 ${pg.cuts.map(c => c.no).join("·")}</div>
      ${pg.cuts.map(scriptCut).join("")}
    </div>`).join("");

  view("result");
  $("#progress").hidden = true;
  $("#result").hidden = false;
  window.scrollTo(0, 0);
}

/* ------------------------------------------------- 장 다시 그리기 (#59)
 *
 * 그림은 컷이 아니라 **장 단위**로 굽는다 — 한 장에 3컷이 함께 그려지므로
 * "컷 하나만" 다시 뽑는 길은 없다. 다시 그리는 최소 단위가 장이다.
 *
 * 크레딧 차감은 없다 (#16 이 백로그). 실제 API 비용은 나간다. */

let resultRunId = "";

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
        <span>글자 없이 그림만 다시 그리기 <small>말풍선 안 글자는 비웁니다</small></span>
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
        body: JSON.stringify({ feedback, textless, tags }) });
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
  img.src = `/api/jobs/${jobId}/page/${no}?w=1080&t=${verBust[no]}`;
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
        `/api/runs/${encodeURIComponent(resultRunId)}/scenes/${no}/versions`)).json()).versions;
    } catch { return; }
  }
  if (!versions || !versions.length) { slot.innerHTML = ""; return; }
  const cur = `
    <span class="ver-thumb is-current" title="지금 걸린 그림">
      <img src="/api/jobs/${jobId}/page/${no}?w=160&t=${verBust[no] || 0}" alt="지금 그림" loading="lazy">
      <span class="ver-label">지금</span>
    </span>`;
  const past = versions.map(v => `
    <button type="button" class="ver-thumb js-revert" data-v="${v.version}"
            title="이 판으로 바꾸기">
      <img src="/api/runs/${encodeURIComponent(resultRunId)}/scenes/${no}/versions/${v.version}?w=160"
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
          body: JSON.stringify({ version: Number(btn.dataset.v) }) });
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
    document.querySelector("#studio").scrollIntoView();
  }
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
  $("#scriptPanel").hidden = true;
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
  $("#previewToggle").addEventListener("change", paintCost);
  // 연출(빠르게/웹툰)이 바뀌면 그림 호출 수가 달라져 비용도 달라진다.
  document.querySelectorAll('input[name="layout_mode"]').forEach(
    el => el.addEventListener("change", paintCost));
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
  $("#againBtn").addEventListener("click", forget);
  $("#scriptBtn").addEventListener("click", () => {
    $("#scriptPanel").hidden = !$("#scriptPanel").hidden;
  });
  $("#scriptClose").addEventListener("click", () => { $("#scriptPanel").hidden = true; });

  // 주소로 바로 열기.
  //   /result           이미 만들어 둔 **마지막** 1화를 결과 화면으로
  //   /?job=<id>        그 작업을 결과 화면으로
  // 폼을 거치지 않고 결과부터 보고 싶을 때가 있어서 둔 길이다.
  const asked = new URLSearchParams(location.search).get("job");
  const wantResult = location.pathname.startsWith("/result");
  if (asked || wantResult) {
    openExisting(asked);
  } else if (jobId) {
    startPolling();          // 새로고침해도 돌던 작업으로 돌아온다
  }
});
