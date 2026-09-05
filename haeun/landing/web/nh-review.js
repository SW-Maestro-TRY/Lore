/* 사용자 검수 화면의 그리기 — 방향 카드 · 콘티.
 *
 * newharness.html(실제 동작)과 demo.html(목업)이 **같은 함수를 쓴다.**
 * 목업이 손으로 베낀 마크업을 들고 있으면, 컷 칸이 하나 늘 때마다 둘이
 * 어긋난다. 여기 있는 것은 전부 "값 -> HTML 문자열"인 순수 함수다 —
 * 누르면 무엇을 하는지(고르기·승인)는 각 페이지가 붙인다. 목업에서는
 * 아무 일도 일어나지 않아야 하고, 실제 화면에서는 서버로 나가야 하므로
 * 그 부분은 공유하지 않는다.
 *
 * 스타일은 nh-review.css 에 같이 있다. */
window.NHReview = (function () {
  const esc = (s) => String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");

  /* 방향 카드 하나. 장면은 접어 둔다 — 고르는 데 필요한 것은 제목·장르·
     줄거리이고, 넷을 견줄 때 장면까지 펼쳐져 있으면 한 화면에 안 들어온다. */
  function directionCardHtml(d) {
    const scenes = (d.scenes || []).map(s => `<li>${esc(s)}</li>`).join("");
    return `
      <h3>${esc(d.n)}. ${esc(d.title)} ${d.genre ? `[${esc(d.genre)}]` : ""}</h3>
      <p>${esc(d.plot)}</p>
      ${scenes ? `<details>
        <summary>장면 ${(d.scenes || []).length}개 보기</summary>
        <ul>${scenes}</ul>
      </details>` : ""}`;
  }

  /* 한 칸이 무엇인지는 흐름마다 다르다 — 콘티 흐름은 "컷"(한 장에 여럿이
     들어간다), 디테일 직행 흐름은 "사건"(하나가 그림 한 장이 된다). 세는
     것과 부르는 말이 어긋나면 검수하는 사람이 몇 장을 그리는지 못 센다. */
  function unitWord(summary) {
    return summary && summary.engine === "detail" ? "사건" : "컷";
  }

  /* 검수하는 사람이 봐야 하는 것은 대사가 아니라 "이 칸이 어떤 그림이
     되는가" 라서 카메라·배경·인물을 같이 보여준다. */
  function cutHtml(c, word) {
    const tags = [c.shot, c.angle, c.size].filter(Boolean)
      .map(t => `<span class="nh-tag">${esc(t)}</span>`).join("");
    const who = (c.characters || []).map(p => `
      <p class="nh-cut-who"><b>${esc(p.name)}</b>${p.expression ? ` · ${esc(p.expression)}` : ""}${
        p.action ? `<br>${esc(p.action)}` : ""}</p>`).join("");
    const lines = (c.dialogue || []).map(d => `
      <p class="nh-line"><i>${esc(d.speaker)}</i>“${esc(d.text)}”</p>`).join("");
    const sfx = (c.sfx || []).length
      ? `<p class="nh-line nh-sfx">효과음 ${(c.sfx || []).map(esc).join(" · ")}</p>` : "";
    return `
      <div class="nh-cut">
        <div class="nh-cut-head"><span class="nh-cut-no">${word || "컷"} ${esc(c.id)}</span>${tags}</div>
        ${c.background ? `<p class="nh-cut-bg">${esc(c.background)}</p>` : ""}
        ${who}${lines}${sfx}
      </div>`;
  }

  function castHtml(cast) {
    return (cast || [])
      .map(c => `<span title="${esc(c.appearance)}">${esc(c.name)}</span>`).join("");
  }

  function countLabel(summary) {
    if (!summary) return "";
    if (summary.engine === "direction") {
      return `장면 ${(summary.scenes || []).length}개`;
    }
    if (!summary.cut_count) return "";
    const word = unitWord(summary);
    const tail = word === "사건" ? ` (그림 ${summary.cut_count}장)` : "";
    return `장면 ${(summary.scenes || []).length}개 · ${word} ${summary.cut_count}개${tail}`;
  }

  /* engine이 "direction"이면(이어그리기 — 콘티·컷 대본이 없다) 컷 토글을
     안 그린다. 어차피 컷 칸이 전부 빈 채로 와서(background·characters·
     dialogue 없음), 펼쳐도 요약 문장만 되풀이해 보여주고 정보가 없다. */
  function simpleScenesHtml(scenes) {
    return (scenes || []).map(s => `
      <div class="nh-scene">
        <div class="nh-scene-head">장면 ${esc(s.id)}</div>
        <p class="nh-scene-sum">${esc(s.summary)}</p>
      </div>`).join("");
  }

  /* 첫 장면만 펴 둔다 — 칸이 20개를 넘는 일이 흔해서, 다 펴 두면
     "이대로 진행" 단추까지 한참 스크롤해야 한다. */
  function scenesHtml(scenes, word) {
    return (scenes || []).map((s, i) => `
      <div class="nh-scene">
        <div class="nh-scene-head">장면 ${esc(s.id)}</div>
        <p class="nh-scene-where">${[s.location, s.time].filter(Boolean).map(esc).join(" · ")}</p>
        <p class="nh-scene-sum">${esc(s.summary)}</p>
        <details ${i === 0 ? "open" : ""}>
          <summary>${word || "컷"} ${(s.cuts || []).length}개 보기</summary>
          <div>${(s.cuts || []).map(c => cutHtml(c, word)).join("")}</div>
        </details>
      </div>`).join("");
  }

  /* 콘티 검수 화면을 통째로 채운다. 세 자리(cast·개수·장면)를 한 번에
     넣으므로, 부르는 쪽은 어느 칸이 어디인지 몰라도 된다. */
  function fillBoard(summary, sel) {
    if (!summary) return;
    const ids = Object.assign(
      { cast: "#boardCast", count: "#boardCount", scenes: "#boardScenes" }, sel || {});
    const cast = document.querySelector(ids.cast);
    const count = document.querySelector(ids.count);
    const scenes = document.querySelector(ids.scenes);
    if (cast) cast.innerHTML = castHtml(summary.cast);
    if (count) count.textContent = countLabel(summary);
    if (scenes) {
      scenes.innerHTML = summary.engine === "direction"
        ? simpleScenesHtml(summary.scenes)
        : scenesHtml(summary.scenes, unitWord(summary));
    }
  }

  /* "다시 만들기" 버튼을 누르면 바로 요청을 보내지 않고, 요청 사항을 적을
     수 있는 칸을 편다 — 비워 두고 확인해도 된다. 이 버튼은 다른 클릭
     리스너를 따로 달지 않는다 — 요청은 항상 이 칸의 "다시 만들기" 확인
     버튼으로만 나간다.
     retryBtn 은 `.nh-approval-actions` 안에 있어야 한다(패널을 그 바로
     뒤에 붙인다). onSubmit(note) 이 실제 요청을 보내는 자리다 — 이
     함수는 UI만 맡고 네트워크 호출은 모른다(목업 페이지는 onSubmit 에서
     아무 것도 안 하면 그만이다). */
  function wireRetryNote(retryBtnId, onSubmit) {
    const btn = document.getElementById(retryBtnId);
    if (!btn) return;
    const actions = btn.closest(".nh-approval-actions") || btn.parentElement;
    const panel = document.createElement("div");
    panel.className = "nh-retry-note";
    panel.hidden = true;
    panel.innerHTML = `
      <textarea placeholder="다시 만들 때 반영할 것이 있으면 적어 주세요"></textarea>
      <div class="nh-retry-note-actions">
        <button type="button" class="btn btn-quiet btn-sm" data-act="cancel">취소</button>
        <button type="button" class="btn btn-primary btn-sm" data-act="confirm">다시 만들기</button>
      </div>`;
    actions.insertAdjacentElement("afterend", panel);
    const textarea = panel.querySelector("textarea");
    btn.addEventListener("click", () => {
      panel.hidden = false;
      textarea.focus();
    });
    panel.querySelector('[data-act="cancel"]').addEventListener("click", () => {
      panel.hidden = true;
    });
    panel.querySelector('[data-act="confirm"]').addEventListener("click", () => {
      const note = textarea.value;
      panel.hidden = true;
      textarea.value = "";
      onSubmit(note);
    });
  }

  /* ---- 시트를 눌러서 크게 보기 ------------------------------------------ *
   *
   * 캐릭터 시트는 한 장 안에 전신·얼굴·디테일이 잘게 들어가 있어서, 검수
   * 화면에 들어가는 크기로는 **눈·흉터 같은 것을 볼 수가 없다.** 그런데
   * 여기서 "이 얼굴로 끝까지 간다"를 정해야 한다.
   *
   * 눌러서 여는 것은 **화면에 꽉 채운 같은 그림**이다. 한 번 더 누르면
   * 원래 크기로 벌어지고 끌어서 볼 수 있다 — 시트는 가로로 길어서 꽉
   * 채워도 디테일 칸이 작다.
   *
   * 내려받기는 그대로 막혀 있다 — base.js 가 모든 그림에 거는 규칙이
   * 여기서 만든 그림에도 그대로 걸린다(오른쪽 누르기·끌어 놓기·길게
   * 누르기). 크게 보는 것과 가져가는 것은 다른 일이다.
   *
   * 화면마다 시트가 뜨는 때가 달라서(본편은 서버 응답 뒤에 src 를 넣는다)
   * 요소마다 거는 대신 **문서에 한 번** 건다. */
  function wireSheetZoom() {
    document.addEventListener("click", ev => {
      const img = ev.target.closest?.(".nh-sheet-img");
      if (!img || !img.getAttribute("src")) return;
      openZoom(img.getAttribute("src"), img.getAttribute("alt") || "캐릭터 시트");
    });
  }

  /* 배율 단계. 꽉 채움(1) 다음은 2·3·4배, 그 다음엔 다시 꽉 채움으로
     돌아온다 — 계속 눌러도 막다른 곳이 없다. 화면 폭 기준이라 원래 크기가
     작은 그림도 똑같이 커진다. */
  const ZOOM_STEPS = [2, 3, 4];

  function openZoom(src, alt) {
    const box = document.createElement("div");
    box.className = "nh-zoom";
    box.innerHTML = `
      <img src="${esc(src)}" alt="${esc(alt)}" draggable="false">
      <p class="nh-zoom-scale" hidden></p>
      <button type="button" class="nh-zoom-close" aria-label="닫기">✕</button>
      <p class="nh-zoom-hint">눌러서 더 크게 · 바깥을 누르면 닫힙니다</p>`;
    const img = box.querySelector("img");
    const scaleTag = box.querySelector(".nh-zoom-scale");
    const hint = box.querySelector(".nh-zoom-hint");
    let step = -1;                                  // -1 = 꽉 채움

    const close = () => {
      box.remove();
      document.removeEventListener("keydown", onKey);
      document.body.classList.remove("nh-zoom-on");
    };
    const onKey = e => { if (e.key === "Escape") close(); };

    /* 배율을 한 칸 올린다. 누른 자리가 화면 가운데로 오게 스크롤을 맞춘다 —
       안 맞추면 확대할 때마다 그림 왼쪽 위로 튀어서, 보고 있던 곳을 매번
       다시 찾아야 한다. */
    function zoomTo(next, atX, atY) {
      // 지금 보고 있는 지점이 그림 전체에서 어디쯤인가 (0~1)
      const before = img.getBoundingClientRect();
      const fx = before.width ? (atX - before.left) / before.width : 0.5;
      const fy = before.height ? (atY - before.top) / before.height : 0.5;

      step = next;
      if (step < 0) {
        box.classList.remove("is-big");
        box.style.removeProperty("--nh-zoom");
        scaleTag.hidden = true;
        hint.textContent = "눌러서 더 크게 · 바깥을 누르면 닫힙니다";
        box.scrollTo(0, 0);
        return;
      }
      box.classList.add("is-big");
      box.style.setProperty("--nh-zoom", String(ZOOM_STEPS[step]));
      scaleTag.hidden = false;
      scaleTag.textContent = `${ZOOM_STEPS[step]}배`;
      hint.textContent = step === ZOOM_STEPS.length - 1
        ? "끌어서 옮기기 · 누르면 처음 크기로"
        : "끌어서 옮기기 · 눌러서 더 크게";

      const after = img.getBoundingClientRect();
      const w = after.width || img.scrollWidth;
      const h = after.height || img.scrollHeight;
      box.scrollTo(Math.max(0, fx * w + box.scrollLeft - box.clientWidth / 2),
                   Math.max(0, fy * h + box.scrollTop - box.clientHeight / 2));
    }

    /* 끌어서 옮기기. transform 대신 **스크롤**을 움직인다 — 스크롤이면
       휠·터치·키보드가 원래 하던 대로 같이 동작한다.

       움직인 거리가 몇 px 안 되면 "누른 것"으로 본다. 안 그러면 손이
       살짝 흔들린 것만으로 배율이 바뀐다. */
    let drag = null;
    let movedPx = 0;                 // 이번 누름에서 손이 움직인 거리

    img.addEventListener("pointerdown", e => {
      movedPx = 0;
      if (!box.classList.contains("is-big")) return;
      drag = { x: e.clientX, y: e.clientY, sl: box.scrollLeft, st: box.scrollTop };
      box.classList.add("is-panning");
      try { img.setPointerCapture(e.pointerId); } catch { /* 못 잡아도 끈다 */ }
      e.preventDefault();
    });
    img.addEventListener("pointermove", e => {
      if (!drag) return;
      const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
      movedPx = Math.max(movedPx, Math.abs(dx) + Math.abs(dy));
      box.scrollTo(drag.sl - dx, drag.st - dy);
    });
    const endDrag = () => { drag = null; box.classList.remove("is-panning"); };
    img.addEventListener("pointerup", endDrag);
    img.addEventListener("pointercancel", endDrag);

    img.addEventListener("click", e => {
      // 끌고 나서 손을 뗀 것은 누른 것이 아니다 — 안 그러면 손이 살짝
      // 흔들린 것만으로 배율이 바뀐다.
      if (movedPx > 6) { movedPx = 0; return; }
      zoomTo(step + 1 >= ZOOM_STEPS.length ? -1 : step + 1, e.clientX, e.clientY);
    });

    box.addEventListener("click", e => {
      // 바깥이나 ✕ 를 누르면 닫는다. 그림 위 누르기는 위에서 처리한다.
      if (e.target === img) return;
      close();
    });
    document.addEventListener("keydown", onKey);
    document.body.classList.add("nh-zoom-on");
    document.body.appendChild(box);
  }

  wireSheetZoom();

  return { directionCardHtml, cutHtml, castHtml, countLabel, scenesHtml,
          simpleScenesHtml, fillBoard, wireRetryNote, openZoom };
})();
