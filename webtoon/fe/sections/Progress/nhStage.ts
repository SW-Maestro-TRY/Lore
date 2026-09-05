/* 진행 화면이 쓰는 문구·그림 이름.
 *
 * 원본 app.js 의 NH_STAGE_SAY · NH_STAGE_ART · renderNHProgress 와 **같은
 * 값**이다. 서버가 단계 이름(stage)과 사람이 볼 이름(stage_label)을 같이
 * 주므로 목록 자체는 서버가 정한다 — 여기 있는 것은 마스코트가 뭐라고
 * 말하고 어떤 그림을 띄우느냐뿐이다.
 */

/** 단계별 마스코트 한 줄. 서버가 say 를 주면 그쪽이 이긴다(검수 중일 때). */
export const NH_STAGE_SAY: Record<string, string> = {
  story: "루가 이야기를 만들고 있어요",
  sheet: "루가 캐릭터를 디자인하고 있어요",
  board: "루가 고른 방향을 정리하고 있어요",
  pages: "루가 그림을 그리고 있어요",
};

/* webtoon.css 의 .stage-art[data-stage] 그림은 classic 5단계
   (story/sheet/board/art/bind) 이름을 쓴다 — new_harness 의 "pages" 는
   그림을 그리는 단계라 가장 가까운 art 를 빌린다. */
export const NH_STAGE_ART: Record<string, string> = {
  story: "story",
  sheet: "sheet",
  board: "board",
  pages: "art",
};

export interface HeadLine {
  eyebrow: string;
  title: string;
  sub: string;
}

/** 화면 맨 위 세 줄. 원본 renderNHProgress 와 같은 분기다. */
export function headLine(status: string, styleLabel: string): HeadLine {
  if (status === "queued") {
    return {
      eyebrow: "대기 중",
      title: "앞에 만들고 있는 작품이 있습니다",
      sub: "한 번에 한 편씩 만듭니다.",
    };
  }
  if (status === "awaiting_sheet" || status === "awaiting_pick") {
    return {
      eyebrow: "확인이 필요합니다",
      title: "잠깐 봐 주세요",
      sub: "아래에서 확인하고 넘어가 주세요 — 그동안은 아무것도 안 돌아갑니다.",
    };
  }
  return {
    eyebrow: styleLabel || "",
    title: "웹툰을 만들고 있습니다",
    sub: "지금 무엇을 하고 있는지 아래에 그대로 보여드립니다.",
  };
}

/**
 * 마스코트가 하는 말.
 *
 * 순서가 중요하다 — 서버가 준 `say`(검수 중이라는 말)가 **가장 세다.**
 * 그 다음이 그림 단계의 "몇 장째"이고, 마지막이 단계 기본 문구다.
 * 원본 renderNHProgress 도 이 순서로 덮어쓴다.
 */
export function mascotLine(
  status: string,
  stage: string,
  say: string,
  art: { done: number; total: number } | null,
): string {
  if (status === "running" && say) return say;
  if (status === "running" && stage === "pages" && art?.total) {
    return `루가 그림을 그리고 있어요 (${art.done}/${art.total})`;
  }
  return NH_STAGE_SAY[stage] || "루가 만들고 있어요";
}

/** 0:00 꼴. */
export function mmss(seconds: number): string {
  const s = Math.max(0, Math.round(seconds));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
