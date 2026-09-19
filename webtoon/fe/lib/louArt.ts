/* 루의 상황별 그림 — 자리마다 어느 고래가 나올지 여기서 고른다.
 * (haeun/landing/web/lou-art.js 를 옮긴 것. 원화는 sync-landing.sh 가 떠 오는
 *  /static/lou/art/ 에 있다.)
 *
 * 루는 일부러 두 마리가 그려져 있다. 같은 상황이라도 어느 쪽이 나올지 모르는
 * 편이 살아 있는 느낌이라, 두 마리가 다 그려진 자리는 화면에 뜰 때마다 하나를
 * 뽑는다. 한 마리뿐인 자리(빈 화면·길안내)는 후보가 하나라 늘 같은 그림이다. */
const A = "/static/lou/art/";

const LOU_ART: Record<string, string[]> = {
  error: [A + "error-1.png", A + "error-2.png"],
  loading: [A + "loading-1.png", A + "loading-2.png"],
  generating: [A + "generating-1.png", A + "generating-2.png"],
  notice: [A + "notice-1.png", A + "notice-2.png"],
  empty: [A + "empty-2.png"],
  guide: [A + "guide-2.png"],
};

export function louArt(slot: keyof typeof LOU_ART | string): string {
  const list = LOU_ART[slot];
  if (!list?.length) return "";
  return list[Math.floor(Math.random() * list.length)];
}

/* 만드는 중 — 단계마다 다른 루. 하네스 단계 이름(story·sheet·board·art·bind)
 * 그대로 파일이 있어서 그 이름으로 찾는다. 같은 화면을 몇 분씩 보는 자리라,
 * 단계가 넘어갈 때 그림이 바뀌는 것이 "지금 뭘 하는 중인지" 를 문구보다 먼저
 * 알려 준다. 모르는 단계 이름이면 만드는 중 그림으로 돌아간다. */
const STAGE_LOU: Record<string, string> = {
  story: "/static/lou/stage/story.webp",
  sheet: "/static/lou/stage/sheet.webp",
  board: "/static/lou/stage/board.webp",
  art: "/static/lou/stage/art.webp",
  bind: "/static/lou/stage/bind.webp",
  done: "/static/lou/stage/done.webp",
};

export function louStage(stage: string | undefined): string {
  return (stage && STAGE_LOU[stage]) || louArt("generating");
}

/* 놀이터의 루 — 첫 프레임. 여기서부터 idle 이 돈다(mascotPlay). */
export const LOU_IDLE = "/static/lou/react/idle/01.webp";

/* 헤더 로고 — 표정 원화에서 고래 몸통만 잘라 둔 12장(whale1 · whale2 × 6).
 * 위자드가 걸음마다 이 중 하나를 뽑아 앉힌다. */
export const LOU_LOGOS = ["curious", "default", "discover", "happy", "sleepy", "thinking"]
  .flatMap((e) => [`/static/lou/logo-1-${e}.png`, `/static/lou/logo-2-${e}.png`]);

/* 홈의 큰 루. 두 마리가 그려져 있어서, 들어올 때마다 하나를 뽑는다 —
 * 어느 쪽이 나올지 모르는 편이 살아 있는 느낌이다(원본 HERO_LOUS). */
export const HERO_LOUS = ["/static/lou/hero-whale2.png", "/static/lou/hero-whale1.png"];

export function pickOne(list: string[], not: string[] = []): string {
  const pool = list.filter((x) => !not.includes(x));
  const from = pool.length ? pool : list;
  return from[Math.floor(Math.random() * from.length)];
}
