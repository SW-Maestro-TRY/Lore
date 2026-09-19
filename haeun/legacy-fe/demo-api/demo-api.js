/* 데모 모드 API — 파이썬 서버(haeun/landing/serve.py) 없이 화면만 돌린다.
 *
 * 이 프로토타입은 원래 serve.py 가 띄워 주는 것이고, 화면이 뜨는 데 필요한
 * 서버 응답은 GET 세 개(/api/config · /api/credits · /api/account/me)뿐이다.
 * 그 세 개를 **실제 서버에서 받아 둔 스냅샷**(demo-api/*.json)으로 바꿔치기하면
 * 정적 파일만으로 랜딩이 그대로 산다.
 *
 * 나머지 /api/* — 실제 생성·결제·로그인 — 은 서버가 있어야 하는 일이라
 * 여기서 막는다. 조용히 실패시키지 않고 error 를 실어 보내는 이유:
 * app.js 가 그 문구를 폼 아래 안내(note)에 그대로 띄운다(app.js 의 startRun).
 * 그래야 눌러 본 사람이 "고장" 이 아니라 "여기선 여기까지" 로 읽는다.
 *
 * app.js 보다 **먼저** 실려야 한다 — sync-landing.sh 가 그 자리에 끼워 넣는다.
 */
(function () {
  "use strict";

  const real = window.fetch.bind(window);

  // GET 으로 오는 것만 스냅샷으로 받는다. 값을 여기 적어 두지 않고 파일로 둔
  // 이유: config 는 파이썬(pipeline·credits)이 유일한 출처라, 값이 바뀌면
  // sync-landing.sh 로 다시 떠 오면 되게 하려는 것이다.
  const SNAPSHOT = {
    "/api/config": "/static/demo-api/config.json",
    "/api/credits": "/static/demo-api/credits.json",
    "/api/account/me": "/static/demo-api/account-me.json",
    // 둘러보기는 **빈 목록**이다. 실제 작품의 그림은 story-harness 결과 폴더에
    // 있어서 여기(public/static)에 없다 — 목록만 채우면 표지가 전부 깨진 칸으로
    // 뜬다. 빈 목록이면 화면이 "아직 없습니다" 를 그린다.
    "/api/runs": "/static/demo-api/runs.json",
  };

  const BLOCKED =
    "데모 모드예요 — 이 화면은 실제 생성 없이 보는 판이라 여기서 멈춥니다. " +
    "진짜로 만들려면 haeun/landing 의 serve.py 를 띄워 주세요.";

  function json(body, status) {
    return new Response(JSON.stringify(body), {
      status: status || 200,
      headers: { "Content-Type": "application/json; charset=utf-8" },
    });
  }

  window.fetch = function (input, init) {
    const url = new URL(
      typeof input === "string" ? input : input.url,
      location.href
    );
    if (url.origin !== location.origin || !url.pathname.startsWith("/api/")) {
      return real(input, init);
    }

    const method = ((init && init.method) || (input && input.method) || "GET")
      .toUpperCase();
    const file = method === "GET" ? SNAPSHOT[url.pathname] : null;
    if (file) return real(file);

    // 마지막 결과물 — 데모에는 "지난 작업" 이 없다. 404 는 app.js 가 이미
    // 아는 답이라(없으면 폼으로 되돌아간다) 새 화면을 만들 필요가 없다.
    if (method === "GET" && url.pathname === "/api/latest") {
      return Promise.resolve(json({ error: "아직 만든 것이 없습니다" }, 404));
    }

    return Promise.resolve(json({ error: BLOCKED, demo: true }, 503));
  };
})();
