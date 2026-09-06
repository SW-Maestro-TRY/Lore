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
  /* 만드는 중에는 <b>아무 말도 안 얹는다.</b> 바로 위에 마스코트 · 진행
     막대 · 경과 시간 · 단계 목록이 이미 있는데, 그 아래에 그림체 이름과
     "웹툰을 만들고 있습니다" 를 또 적으면 같은 말을 두 번 하는 것이 된다.
     빈 값이면 화면이 이 자리를 통째로 안 그린다. */
  return { eyebrow: "", title: "", sub: "" };
}

/**
 * 단계마다 <b>무엇을 하는지</b>.
 *
 * 목록 자체는 여전히 서버가 정한다(위 주석 참고) — 여기 있는 것은 그 단계의
 * 설명뿐이고, 모르는 단계가 오면 설명 없이 이름만 나온다. 한동안 이 자리에
 * 단계 <b>이름</b>만 있었는데, "story · sheet · board · pages" 넉 줄로는
 * 몇 분씩 기다리는 사람이 무슨 일이 일어나는지 알 수가 없었다.
 */
export const NH_STAGE_DESC: Record<string, string> = {
  story: "이야기의 축을 뽑고 서로 다른 방향 4개를 씁니다. 앞뒤가 안 맞는 곳과 "
    + "처음 읽는 사람이 못 따라갈 곳을 검수한 뒤, 하나를 고릅니다.",
  sheet: "고른 이야기에 맞춰 캐릭터의 생김새·옷·색을 글로 확정하고, "
    + "그 사양대로 앞·옆·뒤 모습과 표정을 한 장에 그립니다.",
  board: "고른 방향을 회차로 확정합니다 — 장면 순서, 함께 나오는 인물, "
    + "이번 화에서 일부러 안 밝히고 남겨 둘 것.",
  pages: "표지 한 장과 장면들을 차례로 그립니다. 한 장을 그릴 때마다 "
    + "앞 장과 이어지는지 · 글이 그림에 담겼는지 검수하고, 걸리면 다시 그립니다.",
};

/** 단계마다 이 화면이 보여줄 수 있는 것. 없으면 펼칠 것이 없다. */
export const NH_STAGE_RESULT: Record<string, string> = {
  story: "지어낸 이야기 4개 보기",
  sheet: "캐릭터 시트 보기",
  board: "확정된 회차 보기",
};

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
