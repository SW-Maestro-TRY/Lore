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
];

const IDEAS = [
  "몰락한 문파에 혼자 남아 적 앞으로 걸어 나가는 검객",
  "데뷔조에서 잘린 연습생에게 다시 무대가 주어진다",
  "각성 등급 최하위인데 아무도 못 깨는 게이트를 혼자 연다",
  "악역 영애로 빙의했는데 원작 내용을 하나도 모른다",
];

let jobId    = sessionStorage.getItem("lore_job") || null;
let poll     = null;
let photoData = null;
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

function setupPhoto() {
  const drop = $("#photoDrop"), input = $("#photo"), prev = $("#photoPreview");

  const load = file => {
    if (!file || !file.type.startsWith("image/")) return;
    if (file.size > 6 * 1024 * 1024) return toast("사진이 너무 큽니다 (6MB 까지)");
    const fr = new FileReader();
    fr.onload = () => {
      photoData = fr.result;
      prev.src = fr.result; prev.hidden = false;
      drop.classList.add("has-photo");
      $("#photoClear").hidden = false;
    };
    fr.readAsDataURL(file);
  };

  input.addEventListener("change", e => load(e.target.files[0]));
  ["dragenter", "dragover"].forEach(ev => drop.addEventListener(ev, e => {
    e.preventDefault(); drop.classList.add("drag");
  }));
  ["dragleave", "drop"].forEach(ev => drop.addEventListener(ev, e => {
    e.preventDefault(); drop.classList.remove("drag");
  }));
  drop.addEventListener("drop", e => load(e.dataTransfer.files[0]));
  $("#photoClear").addEventListener("click", e => {
    e.preventDefault(); e.stopPropagation();
    photoData = null; input.value = ""; prev.hidden = true;
    drop.classList.remove("has-photo"); $("#photoClear").hidden = true;
  });
}


/* 크레딧은 **목업**입니다 — 실제 과금과 무관하고, 화면에 얼마가 드는지
   보이게 하려고만 둡니다. 편집실(/editor)의 잔액과 같은 값을 씁니다. */
const CREDIT = { full: 240, preview: 60 };

function paintCost() {
  const preview = $("#previewToggle").checked;
  $("#costChip").textContent = `−${preview ? CREDIT.preview : CREDIT.full} 크레딧`;
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
    story:      form.story.value.trim(),
    style:      form.style.value,
    preview:    $("#previewToggle").checked,
    photo_data: photoData || "",
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
    }
  } else {
    approvalBox.hidden = true;
  }

  const storyApprovalBox = $("#storyApproval");
  if (s.status === "awaiting_story_approval") {
    storyApprovalBox.hidden = false;
    if (lastStatus !== "awaiting_story_approval") setStoryButtonsBusy(false);
  } else {
    storyApprovalBox.hidden = true;
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

function setSheetButtonsBusy(busy) {
  $("#sheetApproveBtn").disabled = busy;
  $("#sheetRetryBtn").disabled = busy;
}

async function sendSheetDecision(decision) {
  if (!jobId) return;
  setSheetButtonsBusy(true);
  try {
    const res = await fetch(`/api/jobs/${jobId}/sheet-decision`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ decision }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "전달하지 못했습니다");
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
      body: JSON.stringify({ decision }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || "전달하지 못했습니다");
    // 다음 tick() 이 새 상태를 받아 화면을 바꾼다 — 여기서 직접 안 바꾼다.
  } catch (err) {
    toast(err.message);
    setStoryButtonsBusy(false);
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
  $("#reader").innerHTML = r.pages.map(pg => `
    <div class="page">
      <img class="cut-img" src="/api/jobs/${jobId}/page/${pg.no}?w=1080"
           alt="${pg.no}번째 장" loading="lazy">
    </div>
  `).join("");

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
  setupPhoto();
  $("#form").addEventListener("submit", submit);
  $("#previewToggle").addEventListener("change", paintCost);
  paintCost();

  $("#cancelBtn").addEventListener("click", async () => {
    if (!jobId || !confirm("만드는 것을 중단할까요? 지금까지 그린 컷은 남습니다.")) return;
    await fetch(`/api/jobs/${jobId}/cancel`, { method: "POST" });
  });
  $("#sheetApproveBtn").addEventListener("click", () => sendSheetDecision("approve"));
  $("#sheetRetryBtn").addEventListener("click", () => sendSheetDecision("retry"));
  $("#storyApproveBtn").addEventListener("click", () => sendStoryDecision("approve"));
  $("#storyRetryBtn").addEventListener("click", () => sendStoryDecision("retry"));
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
