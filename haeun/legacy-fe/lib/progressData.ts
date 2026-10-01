// 진행 화면 정적 데이터 — haeun/landing/pipeline.py 의 STAGE_SPEC · app.js 의
// MASCOT_MOODS 를 그대로 옮겼다. 실제로는 서버가 단계·문구를 정하지만
// (사용자에게 보이는 이름일 뿐 하네스 내부 단계 이름은 안 올린다는 원칙도
// 서버 쪽 값이다), 지금은 백엔드가 없어서 여기 상수로 둔다.

export interface StageStep {
  key: string;
  label: string;
}

export interface StageSpec {
  key: string;
  title: string;
  desc: string;
  steps: StageStep[];
}

const CUTS_PER_SHEET = 3;

export const STAGE_SPEC: StageSpec[] = [
  {
    key: "story",
    title: "이야기 설계",
    desc: "캐릭터에서 이야기를 만듭니다",
    steps: [
      { key: "look", label: "사진에서 외형 읽기" },
      { key: "seed", label: "장르·세계관 정하기" },
      { key: "card", label: "캐릭터 카드 쓰기" },
      { key: "premise", label: "이야기 뼈대 세우기" },
      { key: "judge", label: "구조 검수" },
      { key: "scene", label: "첫 장면 쓰기" },
    ],
  },
  {
    key: "sheet",
    title: "캐릭터 시트",
    desc: "컷마다 같은 얼굴이 나오도록 기준 그림을 만듭니다",
    steps: [
      { key: "spec", label: "외형 사양 정리" },
      { key: "draw", label: "시트 그리기" },
      { key: "pick", label: "기준 시트 확정" },
    ],
  },
  {
    key: "board",
    title: "회차 설계 · 콘티",
    desc: "1화를 컷으로 나누고 대사를 붙입니다",
    steps: [
      { key: "arc", label: "큰 줄거리" },
      { key: "episode", label: "1화 설계" },
      { key: "check", label: "연출 검사" },
      { key: "cuts", label: "컷 나누기" },
    ],
  },
  {
    key: "art",
    title: "그림 그리기",
    desc: `한 장에 ${CUTS_PER_SHEET}컷씩 그립니다 — 말풍선과 대사가 함께 들어갑니다`,
    steps: [
      { key: "prompt", label: "장면 서술 옮기기" },
      { key: "group", label: `${CUTS_PER_SHEET}컷씩 묶기` },
      { key: "draw", label: "장 그리기" },
    ],
  },
  {
    key: "bind",
    title: "한 편으로 잇기",
    desc: "그린 장을 순서대로 세로로 이어 붙입니다",
    steps: [{ key: "strip", label: "이어 붙이기" }],
  },
];

// 단계 key → 표정 + 한 줄. 사용자는 몇 분 가까이 이 화면을 본다. rail 은
// 무엇을 하는지 기계적으로 적고, 마스코트는 그걸 사람 말로 한 번 더 말한다.
export const MASCOT_MOODS: Record<string, [mood: string, line: string]> = {
  story: ["write", "루가 이야기를 만들고 있어요"],
  sheet: ["draw", "루가 캐릭터를 디자인하고 있어요"],
  board: ["read", "루가 콘티를 짜고 있어요"],
  art: ["draw", "루가 그림을 그리고 있어요"],
  bind: ["read", "루가 완성도를 확인하고 있어요"],
};

export function mmss(sec: number): string {
  const s = Math.max(0, Math.round(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
