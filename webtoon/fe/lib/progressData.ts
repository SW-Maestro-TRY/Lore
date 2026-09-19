// 진행 화면 정적 데이터 — 사용자에게 보이는 단계 이름과, 그 단계 안에서
// 무슨 일이 벌어지는지의 목록이다. 서버는 큰 단계(story·sheet·board·art·bind)
// 와 한 줄 문구까지만 올려 주므로, 그 안의 잔걸음은 여기 목록으로 채운다.

export interface StageStep {
  key: string;
  label: string;
}

export interface StageSpec {
  key: string;
  /** 화면 왼쪽 줄에서 몇 번째 걸음에 속하는가 (STEPS 의 색인) */
  step: number;
  title: string;
  desc: string;
  steps: StageStep[];
}

const CUTS_PER_SHEET = 3;

/* 하네스 단계(key) → 화면의 네 걸음(step).
 * 회차 설계(board)는 따로 보여 주지 않고 「페이지 그리기」 안에서 벌어지는
 * 일로 묶는다 — 사용자가 고를 것도 볼 것도 없는 단계라, 걸음으로 세면
 * 기다리는 사람에게 멈춰 있는 칸이 하나 더 생길 뿐이다. */
export const STAGE_SPEC: StageSpec[] = [
  {
    key: "story",
    step: 0,
    title: "이야기 짓기",
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
    step: 1,
    title: "캐릭터 그리기",
    desc: "컷마다 같은 얼굴이 나오도록 기준 그림을 만듭니다",
    steps: [
      { key: "spec", label: "외형 사양 정리" },
      { key: "draw", label: "시트 그리기" },
      { key: "pick", label: "기준 시트 확정" },
    ],
  },
  {
    key: "board",
    step: 2,
    title: "페이지 그리기",
    desc: "1화를 컷으로 나누고 대사를 붙입니다",
    steps: [
      { key: "arc", label: "큰 줄거리 잡기" },
      { key: "episode", label: "1화 설계" },
      { key: "check", label: "연출 검사" },
      { key: "cuts", label: "컷 나누기" },
    ],
  },
  {
    key: "art",
    step: 2,
    title: "페이지 그리기",
    desc: `한 장에 ${CUTS_PER_SHEET}컷씩 그립니다 — 말풍선과 대사가 함께 들어갑니다`,
    steps: [
      { key: "prompt", label: "장면 서술 옮기기" },
      { key: "group", label: `${CUTS_PER_SHEET}컷씩 묶기` },
      { key: "draw", label: "장 그리기" },
    ],
  },
  {
    key: "bind",
    step: 3,
    title: "검수하기",
    desc: "그린 장을 순서대로 이어 붙이고 마지막으로 살펴봅니다",
    steps: [
      { key: "order", label: "장 순서 확인" },
      { key: "strip", label: "세로로 이어 붙이기" },
      { key: "look", label: "빠진 컷·글자 살펴보기" },
    ],
  },
];

export function stageSpec(stage: string | undefined): StageSpec | undefined {
  return STAGE_SPEC.find((s) => s.key === stage);
}

// 걸음 → 루가 하는 말. 사용자는 몇 분 가까이 이 화면을 본다. 왼쪽 줄은
// 무엇을 하는지 기계적으로 적고, 루는 그걸 사람 말로 한 번 더 말한다.
export const MASCOT_LINES: string[] = [
  "루가 이야기를 짜고 있어요",
  "루가 캐릭터를 그리고 있어요",
  "루가 페이지를 그리고 있어요",
  "루가 검수하고 있어요",
];

export function mmss(sec: number): string {
  const s = Math.max(0, Math.round(sec));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}
