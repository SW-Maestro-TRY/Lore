// 보기·진행 화면 부하 — 생성은 보내지 않는다(돈 안 듦). #646 1단계 재측정(2026-10-26).
//   k6 run -e BASE=https://staging.lorecomic.com -e VUS=300 -e DUR=3m -e RUNS=a,b -e JOBS=x,y browse.js
// 가상 사용자 10명 중 7명은 둘러보기 → 완성본 → 그림 4장을 보고, 3명은 진행 화면처럼 3초마다 작업 상태를 묻는다.
import http from "k6/http";
import { sleep, check } from "k6";

const BASE = __ENV.BASE || "https://staging.lorecomic.com";
const API = BASE + "/api/webtoon/v1";
const RUNS = (__ENV.RUNS || "").split(",").filter(Boolean);
const JOBS = (__ENV.JOBS || "").split(",").filter(Boolean);

export const options = {
  scenarios: { load: { executor: "constant-vus", vus: Number(__ENV.VUS || 50), duration: __ENV.DUR || "3m" } },
  summaryTrendStats: ["avg", "med", "p(95)", "p(99)", "max"],
  // 주소별 응답 시간을 요약에 따로 내려고 거는 느슨한 기준(실패시키려는 것 아님)
  thresholds: Object.fromEntries(["gallery", "result", "page_image", "job_poll"].map((n) => [`http_req_duration{name:${n}}`, ["p(95)<600000"]])),
};

const pick = (xs) => xs[Math.floor(Math.random() * xs.length)];
// 그림은 서버가 302 로 창고 주소를 넘긴다 — 서버 부하만 재려고 따라가지 않는다(redirects: 0).
const ok = (r) => check(r, { "2xx/3xx": (x) => x.status >= 200 && x.status < 400 });

export default function () {
  if (__VU % 10 < 3 && JOBS.length) {
    // 진행 화면: 3초마다 묻는다(Progress.tsx POLL_MS)
    const id = pick(JOBS);
    for (let i = 0; i < 20; i++) {
      ok(http.get(`${API}/nh/jobs/${id}?watching=true`, { tags: { name: "job_poll" } }));
      sleep(3);
    }
    return;
  }
  ok(http.get(`${API}/runs`, { tags: { name: "gallery" } }));
  sleep(2 + Math.random() * 3);
  const run = pick(RUNS);
  ok(http.get(`${API}/runs/${run}/result`, { tags: { name: "result" } }));
  for (let p = 1; p <= 4; p++) {
    ok(http.get(`${API}/runs/${run}/page/${p}?w=320&ep=1`, { tags: { name: "page_image" }, redirects: 0 }));
    sleep(1 + Math.random() * 2);
  }
  sleep(2 + Math.random() * 3);
}
