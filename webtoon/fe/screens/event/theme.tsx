/* 출석 이벤트의 겉 — 할로윈(10.28~11.03)과 가면무도회(11.04~).
 *
 * 규칙(7칸 · 4일째 5C · 7일째 20C · 하루 쉬면 처음부터)과 일차는 서버가 정하고 두 테마가
 * 같이 쓴다. 여기 있는 것은 문구 · 색 · 아이콘 · 그림뿐이다. 어느 테마인지도 서버가 날짜로
 * 정해 준다(`attendance.theme`) — 브라우저 시계로 가르면 자정 언저리에 서버와 갈린다.
 *
 * 시안: haeun/이벤트-크레딧-2026-10-27/할로윈-B-완성.html · 가면무도회-A-완성.html */
import type { Attendance } from "@common/api/credits";

export type ThemeKey = Attendance["theme"];

const IMG = "/static/event/";

/* 아이콘은 시안의 SVG 를 그대로 옮겼다. 그라데이션 id 가 겹치지 않게 앞에 wt-ev- 를 붙인다. */
export function Candy({ color, hollow }: { color: string; hollow?: boolean }) {
  return (
    <svg className="ic" viewBox="0 0 100 60" aria-hidden="true"
         style={hollow ? { color: "transparent", stroke: "#ffffff55", strokeWidth: 3 } : { color }}>
      <path d="M28 30L6 14l6 16-6 16zM72 30l22-16-6 16 6 16z" fill="currentColor" />
      <ellipse cx="50" cy="30" rx="23" ry="19" fill="currentColor" />
      {!hollow && <path d="M40 13q14 17 0 34M50 11q14 19 0 38M60 13q14 17 0 34" fill="none" stroke="#fff8" strokeWidth="4" />}
    </svg>
  );
}

export function Lantern() {
  return (
    <svg className="ic" viewBox="0 0 100 100" aria-hidden="true" style={{ color: "#ff7b2e" }}>
      <path d="M46 24c0-10 6-14 12-14-4 4-4 10-4 14z" fill="#4e8a3c" />
      <ellipse cx="50" cy="60" rx="44" ry="36" fill="currentColor" />
      <path d="M50 24C40 40 40 80 50 96M50 24c10 16 10 56 0 72M34 27C24 44 24 76 34 93M66 27c10 17 10 49 0 66" fill="none" stroke="#0003" strokeWidth="3" />
      <path d="M30 50l10 12H22zM70 50l10 12H60zM28 72c8 10 36 10 44 0l-8 4-8-4-6 4-6-4-8 4z" fill="#1a1120" />
    </svg>
  );
}

export function Gem() {
  return (
    <svg className="ic" viewBox="0 0 48 48" aria-hidden="true">
      <defs>
        <linearGradient id="wt-ev-gem" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#fff" /><stop offset=".45" stopColor="#c9b8ff" /><stop offset="1" stopColor="#7b4fd6" />
        </linearGradient>
      </defs>
      <path d="M24 4l16 14-16 26L8 18z" fill="url(#wt-ev-gem)" stroke="#5a3aa8" strokeWidth="1.5" />
      <path d="M8 18h32M24 4l-6 14 6 26 6-26z" fill="none" stroke="#fff" strokeOpacity=".7" />
    </svg>
  );
}

export function Mask({ hole = "#14102a", hollow }: { hole?: string; hollow?: boolean }) {
  return (
    <svg className="ic" viewBox="0 0 120 64" aria-hidden="true"
         style={hollow ? { color: "transparent", stroke: "#e9c46a66", strokeWidth: 3 } : { color: "#e9c46a" }}>
      <path d="M60 12C40 2 20 8 8 24 2 36 8 52 24 54c12 2 20-6 26-12l10 4 10-4c6 6 14 14 26 12 16-2 22-18 16-30C100 8 80 2 60 12z" fill="currentColor" />
      {!hollow && <ellipse cx="36" cy="32" rx="12" ry="8" fill={hole} />}
      {!hollow && <ellipse cx="84" cy="32" rx="12" ry="8" fill={hole} />}
    </svg>
  );
}

export function Invite() {
  return (
    <svg className="ic" viewBox="0 0 120 80" aria-hidden="true">
      <rect x="4" y="4" width="112" height="72" rx="6" fill="#fdf6e3" stroke="#e9c46a" strokeWidth="3" />
      <rect x="14" y="14" width="92" height="52" rx="3" fill="none" stroke="#e9c46a" strokeWidth="1.5" />
      <path d="M40 30h40M34 40h52M44 50h32" stroke="#a0304f" strokeWidth="3" strokeLinecap="round" />
    </svg>
  );
}

/** 할로윈 마녀 모자. 리셰트 그림 위에 얹는다. */
export function Hat() {
  return (
    <svg className="wt-ev-hat" viewBox="0 0 160 140" aria-hidden="true">
      <ellipse cx="80" cy="114" rx="78" ry="18" fill="#2b1740" />
      <path d="M38 114c10-40 32-74 74-106-10 40-2 74 10 106z" fill="#2b1740" />
      <path d="M42 106c20 8 60 8 78 0l2 12c-20 8-62 8-82 0z" fill="#ff7b2e" />
      <circle cx="86" cy="112" r="5" fill="#ffd166" />
    </svg>
  );
}

const CANDY = ["#ff7b2e", "#b06cff", "#ffd166", "#6fe0c4", "#ff5d8f", "#ffb067", "#ff7b2e"];

export interface EventTheme {
  key: ThemeKey;
  /** 시안의 글꼴. 못 받으면 기본 글꼴로 보인다. */
  fonts: string;
  title: React.ReactNode;
  /** 받는 것의 이름 — 사탕 · 가면 */
  item: string;
  badge: string;
  eyebrow: string;
  heroTitle: React.ReactNode;
  period: string;
  ev1Title: string;
  ev1Desc: string;
  ev2Title: string;
  bannerLine: string;
  bannerSub: string;
  bannerGo: string;
  /** 칸 하나의 아이콘. 4일째 · 7일째는 받든 안 받든 보상 아이콘이다. */
  token: (day: number, got: boolean) => React.ReactNode;
  /** 팝업 아래 일곱 개. 안 채운 칸은 부르는 쪽이 흐리게 한다. */
  dot: (day: number) => React.ReactNode;
  finIcon: React.ReactNode;
  finTitle: string;
  finDesc: string;
  /** 「4번째 사탕 · 5크레딧 받았어요」 같은 한 줄 */
  toast: (day: number, granted: number) => string;
  popTitle: string;
  howStep2: string;
  howStep3: string;
  art: { pop: string; hero: string; banner: string };
}

export const THEMES: Record<ThemeKey, EventTheme> = {
  HALLOWEEN: {
    key: "HALLOWEEN",
    fonts: "https://fonts.googleapis.com/css2?family=Titan+One&family=Jua&display=swap",
    title: <>TRICK <i>or</i> TOON!</>,
    item: "사탕",
    badge: "🍬 기간 한정 · 10.28 – 11.03",
    eyebrow: "TRICK or TOON!",
    heroTitle: <>사탕 대신<br /><em>크레딧</em><br />받아 가세요</>,
    period: "2026.10.28(수) ~ 11.03(화)",
    ev1Title: "7일 사탕 바구니",
    ev1Desc: "매일 들어오면 사탕 하나. 4번째는 5크레딧 보석 사탕, 7번째는 호박 바구니 — 20크레딧으로 웹툰 한 편.",
    ev2Title: "할로윈 주간 가입 선물",
    bannerLine: "사탕 대신 크레딧 받아 가세요",
    bannerSub: "10.28 – 11.03 · 7일 출석하면 웹툰 한 편",
    bannerGo: "사탕 받기",
    token: (d, got) => d === 4 ? <Gem /> : d === 7 ? <Lantern /> : <Candy color={CANDY[d - 1]} hollow={!got} />,
    dot: (d) => <Candy color={CANDY[d - 1]} />,
    finIcon: <Lantern />,
    finTitle: "호박 바구니가 가득!",
    finDesc: "7일 동안 빠짐없이 와 줬어요.\n사탕 7개 = 웹툰 한 편",
    toast: (d, g) => g > 0 ? `${d}번째 사탕 · ${g}크레딧 받았어요` : `사탕 ${d}개째 🍬`,
    popTitle: "오늘의 사탕이 도착했어요 🍬",
    howStep2: "매일 들어와 「오늘 사탕 받기」",
    howStep3: "4번째 5크레딧 · 7번째 20크레딧",
    art: { pop: `${IMG}halloween-bust.png`, hero: `${IMG}lishet-full.png`, banner: `${IMG}lishet-full.png` },
  },
  MASQUERADE: {
    key: "MASQUERADE",
    fonts: "https://fonts.googleapis.com/css2?family=Cinzel:wght@700&family=Gowun+Batang:wght@700&display=swap",
    title: <>MASQUERADE</>,
    item: "가면",
    badge: "✦ 일곱 밤의 가면 · 매일 하나",
    eyebrow: "MASQUERADE NIGHT",
    heroTitle: <>가면무도회에<br />7일 출석하면<br /><em>웹툰 한 편</em></>,
    period: "2026.11.04(수)부터 상시",
    ev1Title: "일곱 밤의 가면",
    ev1Desc: "매일 들어오면 가면 하나. 4번째 밤엔 5크레딧, 7번째 밤엔 초대장 — 20크레딧으로 웹툰 한 편.",
    ev2Title: "무도회 첫 입장",
    bannerLine: "가면무도회에 7일 출석하면 웹툰 한 편",
    bannerSub: "일곱 번째 밤, 초대장과 20크레딧",
    bannerGo: "초대장 받기",
    token: (d, got) => d === 4 ? <Gem /> : d === 7 ? <Invite /> : <Mask hollow={!got} />,
    dot: () => <Mask hole="#f8f1e4" />,
    finIcon: <Invite />,
    finTitle: "초대장이 도착했어요",
    finDesc: "일곱 밤을 빠짐없이 와 줬어요.\n가면 7개 = 웹툰 한 편",
    toast: (d, g) => g > 0 ? `${d}번째 밤의 가면 · ${g}크레딧 받았어요` : `가면 ${d}개째 ✦`,
    popTitle: "오늘의 가면이 도착했어요",
    howStep2: "매일 들어와 「오늘 가면 받기」",
    howStep3: "4번째 밤 5크레딧 · 7번째 밤 20크레딧",
    art: { pop: `${IMG}masquerade-pop.jpg`, hero: `${IMG}masquerade-hero.jpg`, banner: `${IMG}masquerade-hero.jpg` },
  },
};

/** 「받으면 3개째! 내일 4개째엔 5크레딧, 7개 모으면 웹툰 한 편」 — 팝업 본문 한 줄. */
export function popLine(th: EventTheme, a: Attendance): string {
  const head = `받으면 ${a.today}개째!`;
  const n = a.next;
  if (!n) return head;
  if (n.inDays === 0) return `${head} 오늘 받으면 ${n.amount}크레딧`;
  const when = n.inDays === 1 ? "내일" : `${n.inDays}일 뒤`;
  const tail = n.day === 7 ? "" : ", 7개 모으면 웹툰 한 편";
  return `${head} ${when} ${n.day}개째엔 ${n.amount}크레딧${tail}`;
}
