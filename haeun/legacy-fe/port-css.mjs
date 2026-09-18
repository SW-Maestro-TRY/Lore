#!/usr/bin/env node
/*
 * 랜딩 프로토타입의 스타일시트를 이 탭의 webtoon.css 로 옮긴다.
 *
 *   node webtoon/fe/port-css.mjs
 *
 * **왜 손으로 안 옮기는가.** 한동안 손으로 옮겼더니 원본과 조용히 갈라졌다 —
 * PC 에서만 커지는 글자가 폰 크기 그대로 뜨고, 다섯 걸음마다 어두워지는
 * 바다가 통째로 빠지고, `@media` 앞에 셀렉터 조각이 남아 블록 하나가
 * 버려졌다. 규칙이 900개가 넘어서 사람 눈으로는 안 잡힌다.
 *
 * 그래서 원본을 **기계로** 옮기고, 앱 안에 들어오면서 달라지는 것만 손으로
 * 쓴다(webtoon.app.css). 원본이 바뀌면 이걸 다시 돌리면 된다.
 *
 * 옮기면서 하는 일은 넷뿐이다:
 *
 *   1. 모든 선택자를 `.webtoon-page` 아래로 가둔다 — 이 화면의 스타일이
 *      Lore 앱의 다른 화면으로 새면 안 된다.
 *   2. `body` 는 이 화면의 바깥 틀(`.webtoon-page`)이다. 원본에서 body 에
 *      매기던 것(`body.ed`, `body[data-step]`)도 그에 맞춰 옮긴다.
 *   3. 화면 전환(`body[data-view=...]`)은 버린다 — 여기서는 React 가 한다.
 *   4. `@keyframes` 이름에 `webtoon-` 을 붙인다 — 앱의 다른 화면과 이름이
 *      겹치면 엉뚱한 애니메이션이 걸린다.
 *
 * `html` 규칙은 버린다. Lore 앱 것이라 못 건드린다 — 원본이 html 에 깔던
 * 물빛은 webtoon.app.css 가 이 화면의 바깥 틀에 직접 깐다.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import postcss from "postcss";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.join(HERE, "../../haeun/landing/web");
const OUT = path.join(HERE, "webtoon.css");
const APP = path.join(HERE, "webtoon.app.css");

/* 싣는 순서가 원본과 같아야 한다. editor.html 은 style.css 를 먼저 싣고
   editor.css 를 나중에 싣는데, style.css 의 `body.ed ...` 규칙이 특정도로
   그것을 이기게 돼 있다 — 순서를 바꾸면 편집실 머리줄이 되살아난다. */
const FILES = ["style.css", "nh-review.css", "editor.css"];

/* 원본은 화면 하나하나가 id 다(한 페이지에 다 들어 있어서). 여기서는 화면이
   React 컴포넌트라 클래스다. 나머지 id(#dockOpen 처럼 편집실 안의 것)는
   마크업에 그대로 있으므로 안 건드린다. */
const ID_TO_CLASS = {
  "#landing": ".landing", "#create": ".create", "#progress": ".progress",
  "#result": ".result", "#works": ".works", "#mypage": ".mypage",
  "#nextEp": ".nextep",
};

const SCOPE = ".webtoon-page";

function mapSelector(sel) {
  sel = sel.replace(/\s+/g, " ").trim();
  if (!sel) return null;

  // 화면 전환은 React 가 한다 — 원본의 body[data-view=...] 는 버린다.
  //
  // 딱 하나 남긴다: 걸음마다 띠 색을 바꾸는 변수
  // (`body[data-view="create"][data-step="N"] { --foot-bg: ... }`).
  // 이건 화면 전환이 아니라 **만들기 화면 자신의 색**이라, 버리면 마지막
  // 걸음의 단추 띠가 밝은 채로 남아 그 위의 흰 글씨가 안 보인다.
  if (sel.includes("[data-view=")) {
    const m = sel.match(/^body\[data-view="create"\](\[data-step="\d+"\])$/);
    if (!m) return null;
    return `${SCOPE} .create${m[1]}`;
  }
  // html 은 Lore 앱 것이다.
  if (/^html\b/.test(sel)) return null;

  for (const [id, cls] of Object.entries(ID_TO_CLASS)) {
    sel = sel.split(id).join(cls);
  }

  // body 로 시작하는 것은 이 화면의 바깥 틀에 매긴다.
  //   body            -> .webtoon-page
  //   body.ed         -> .webtoon-page .ed          (편집실은 그 안의 한 화면)
  //   body[data-step] -> .webtoon-page .create[...] (걸음은 위자드가 들고 있다)
  if (/^body\b/.test(sel)) {
    const rest = sel.slice(4);
    const m = rest.match(/^(\.ed|\[data-step="\d+"\]|\.dock-open-on)*/);
    const own = m ? m[0] : "";
    const tail = rest.slice(own.length).trim();
    let host = SCOPE;
    if (own.includes(".ed")) host += " .ed" + own.replace(".ed", "").replace(/\[data-step="\d+"\]/, "");
    else if (own.includes("[data-step=")) host += " .create" + own.match(/\[data-step="\d+"\]/)[0];
    else if (own.includes(".dock-open-on")) host += " .ed.dock-open-on";
    else if (own) host += own;                 // 예상 못 한 형태는 그대로 붙인다
    return tail ? `${host} ${tail}` : host;
  }

  // `*` 는 바깥 틀 자신도 포함해야 box-sizing 이 맞는다.
  if (sel === "*") return `${SCOPE}, ${SCOPE} *`;

  // 색·간격 변수는 원본이 :root(=html)에 매긴다. html 은 Lore 앱 것이라
  // 이 화면의 바깥 틀로 내린다 — 안 내리면 변수가 통째로 비어서 단추
  // 배경부터 사라진다(실제로 그랬다).
  if (sel === ":root") return SCOPE;

  return `${SCOPE} ${sel}`;
}

const css = FILES.map((f) => {
  const t = fs.readFileSync(path.join(SRC, f), "utf8");
  return `/* ══════════ ${f} ══════════ */\n${t}`;
}).join("\n\n");

const root = postcss.parse(css);
const frames = new Set();

root.walkAtRules("keyframes", (r) => { frames.add(r.params); r.params = "webtoon-" + r.params; });
root.walkAtRules(/^-\w+-keyframes$/, (r) => { frames.add(r.params); r.params = "webtoon-" + r.params; });

root.walkRules((r) => {
  if (r.parent?.type === "atrule" && /keyframes/.test(r.parent.name)) return;  // 0%/from/to
  const mapped = r.selector.split(",").map(mapSelector).filter(Boolean);
  if (!mapped.length) { r.remove(); return; }
  r.selector = mapped.join(",\n");
});

// 이름을 바꿨으니 부르는 쪽도 같이 바꾼다.
root.walkDecls(/^(animation|animation-name|-webkit-animation|-webkit-animation-name)$/, (d) => {
  for (const name of frames) {
    d.value = d.value.replace(new RegExp(`(^|[\\s,])${name}([\\s,]|$)`, "g"), `$1webtoon-${name}$2`);
  }
});

const header = `/* 이 파일은 **기계가 만든다.** 손으로 고치지 마세요.
 *
 *   node webtoon/fe/port-css.mjs
 *
 * 원본은 haeun/landing/web 의 style.css · nh-review.css · editor.css 이고,
 * 앱 안에 들어오면서 달라지는 것만 webtoon.app.css 에 손으로 씁니다(그 파일이
 * 이 아래에 이어 붙습니다 — 그래서 언제나 원본을 이깁니다).
 *
 * 여기서 고치면 다음번에 원본을 옮길 때 조용히 사라집니다.
 * 자세한 내용은 port-css.mjs 의 머리 주석을 보세요.
 */\n\n`;

const app = fs.existsSync(APP) ? "\n\n" + fs.readFileSync(APP, "utf8") : "";
fs.writeFileSync(OUT, header + root.toString() + app);
console.log(`webtoon.css 생성: 원본 ${FILES.join(" + ")} + webtoon.app.css`);
