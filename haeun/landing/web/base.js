/* 이 화면이 어느 주소 아래에 얹혀 있는가.
 *
 * 같은 파일을 두 군데가 쓴다.
 *   serve.py     — 뿌리에 얹는다.  홈은 /,          둘러보기는 /works
 *   Next(배포)   — /webtoon 아래.  홈은 /webtoon,   둘러보기는 /webtoon/works
 *
 * 그래서 주소를 파일에 박아 두면 한쪽이 깨진다. <html data-lore-base="..."> 로
 * 받아서 여기서 한 번만 붙인다. 그 표시가 없으면 뿌리다 — 즉 파이썬 서버에서는
 * 이 파일이 아무 일도 안 한 것과 같다.
 *
 * 다른 스크립트보다 **먼저** 실려야 한다. app.js·editor.js 가 첫 화면을 고를 때
 * 이미 LORE.isAt 을 쓴다.
 */
(function () {
  "use strict";

  const BASE = (document.documentElement.dataset.loreBase || "").replace(/\/$/, "");

  window.LORE = {
    BASE,

    /** 홈 주소. 뿌리면 "/", 아니면 "/webtoon" — 끝에 빗금을 붙이지 않는다
     *  (rewrites 가 /webtoon 으로 오지 /webtoon/ 으로 오지 않는다). */
    HOME: BASE || "/",

    /** 화면 안에서 쓸 주소를 만든다. at("/works") -> "/webtoon/works" */
    at(path) {
      return BASE + path;
    },

    /** 지금 보고 있는 주소가 거기인가. isAt("/works") -> /webtoon/works... 이면 참 */
    isAt(path) {
      return location.pathname.startsWith(BASE + path);
    },
  };

  // 화면 안의 절대경로 링크(href="/works" 등)에 base 를 붙인다.
  // HTML 을 두 벌로 관리하지 않으려는 것이다 — 원본은 뿌리 기준 그대로 두고,
  // 얹히는 쪽에서만 이 한 줄이 돈다.
  //
  /* ---- 그림을 그냥 저장해 가지 못하게 --------------------------------- *
   *
   * 가져가는 길은 **「이미지로 뽑기」·「내려받기」 하나**로 모은다. 그 길로
   * 나가는 파일에는 LORE 표시가 붙는데(watermark.for_download), 화면에 뜬
   * 그림을 오른쪽 눌러 바로 저장하면 그 표시 없이 나간다.
   *
   * ⚠ 이건 **막는 것이 아니라 문턱**이다. 주소를 알면 그대로 받을 수 있고
   * (그림은 결국 브라우저까지 와야 보인다), 개발자 도구로도 꺼낼 수 있다.
   * 정말 막으려면 서버가 주는 그림마다 표시를 굽고 주소에 시효를 붙여야
   * 하는데 그건 다른 작업이다. 여기서 없애는 것은 **실수로 새어 나가는
   * 길**이다 — 오른쪽 누르기, 끌어다 놓기, 폰에서 길게 누르기 셋.
   *
   * 화면 찍기는 그대로 둔다(막을 방법도 없고, 막을 것도 아니다).
   *
   * 내려받기 단추는 <a download> 라 여기 안 걸린다 — 그림이 아니다. */
  document.addEventListener("contextmenu", ev => {
    if (ev.target && ev.target.tagName === "IMG") ev.preventDefault();
  });
  // 끌어서 바탕화면·다른 창에 떨구는 길.
  document.addEventListener("dragstart", ev => {
    if (ev.target && ev.target.tagName === "IMG") ev.preventDefault();
  });

  // /static/ 은 건드리지 않는다. 정적 파일은 어느 쪽에서도 뿌리에 있다.
  if (BASE) {
    document.addEventListener("DOMContentLoaded", () => {
      document.querySelectorAll('a[href^="/"]').forEach(a => {
        const href = a.getAttribute("href");
        if (href.startsWith("/static/") || href.startsWith(BASE + "/")) return;
        // href="/" 는 홈이다. BASE + "/" 로 만들면 빗금이 하나 남는다.
        a.setAttribute("href", href === "/" ? window.LORE.HOME : BASE + href);
      });
    });
  }
})();
