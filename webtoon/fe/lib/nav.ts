/* 화면 사이를 오가는 규칙 — 주소가 곧 상태다.
 *
 *   /webtoon                       첫 화면
 *   /webtoon?view=entry            입구 (내 캐릭터로 웹툰 / 캐릭터 만들어보기)
 *   /webtoon?view=create&step=1    웹툰 만들기 1~4 (&character=<id> 로 캐릭터를 골라 들어옴)
 *   /webtoon?view=running&job=<id> 만드는 중 (시트 확인 · 이야기 고르기 · 그리는 중)
 *   /webtoon?run=<id>              완성본 (공유 링크가 이 길이다)
 *   /webtoon?view=editor&run=<id>  편집실
 *   /webtoon?view=works            둘러보기
 *   /webtoon?view=characters       내 캐릭터
 *   /webtoon?view=try              캐릭터 만들어보기 (입력)
 *   /webtoon?view=card&id=<id>     캐릭터 만들어보기 (결과 — 내 것)
 *   /webtoon?card=<id>             공유된 카드 (남이 봄)
 *   /webtoon?view=mypage           마이페이지
 *
 * 화면을 바꿀 때 주소도 같이 민다 — 뒤로가기·새로고침·공유가 전부 주소에
 * 기댄다. 상태만 바꾸면 뒤로가기가 웹툰 탭을 통째로 빠져나간다. */

export type View =
  | "landing" | "entry" | "create" | "running" | "result" | "editor" | "works"
  | "characters" | "try" | "card" | "sharedCard" | "mypage";

export interface GoParams {
  step?: number;
  character?: string;
  job?: string;
  run?: string;
  id?: string;
}

export function hrefOf(view: View, p: GoParams = {}): string {
  const q = new URLSearchParams();
  switch (view) {
    case "landing": return "/webtoon";
    case "result": if (p.run) q.set("run", p.run); return `/webtoon?${q}`;
    case "sharedCard": if (p.id) q.set("card", p.id); return `/webtoon?${q}`;
    default: q.set("view", view);
  }
  if (p.step) q.set("step", String(p.step));
  if (p.character) q.set("character", p.character);
  if (p.job) q.set("job", p.job);
  if (p.run) q.set("run", p.run);
  if (p.id) q.set("id", p.id);
  return `/webtoon?${q}`;
}

/** 화면이 받는 이동 함수. `replace` 는 뒤로가기에 안 남긴다(만들기 → 만드는 중). */
export type Go = (view: View, p?: GoParams, opts?: { replace?: boolean }) => void;
