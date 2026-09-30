// 헌터 — 검수된 결과물 풀
// 검수 기준은 romance/index.ts 상단 주석 참고. 정통(01~03) + 반전(04~06).
import type { StoryCard } from "../types";

const CARDS: StoryCard[] = [
  {
    id: "hunter-01",
    intro: "평범한 회사원이었던 당신, 각성 하루 만에 S급 판정",
    name: "강도현 (헌터명: 리버)",
    rank: "무소속 프리랜서 헌터",
    personality: "무뚝뚝하지만 팀원은 절대 안 버림",
    quote:
      "나는 말주변이 없어서, 걱정된다는 말 한마디도 제대로 못 해. 그러니까 그냥 앞에 서 있을게. 그게 내가 아는 유일한 방식이야.",
    tones: ["intense", "radiant"],
    stats: [
      { label: "전투력", kind: "star", stars: 5 },
      { label: "각성 등급", kind: "grade", grade: "S" },
      { label: "협동성", kind: "star", stars: 3 },
    ],
    appearance: {
      hair: "검은 숏컷",
      eyes: "짙은 눈",
      impression: "무뚝뚝하고 듬직함",
      element: "강철",
    },
    base: { str: 92, int: 60, agi: 76, luck: 55 },
    summon: {
      shape: "늑대 형상",
      element: "강철",
      colors: "흑철 × 은",
      stats: { str: 85, int: 50, agi: 80, luck: 58 },
    },
    fateBeats: [
      "각성 하루 만에 3대 길드에서 동시에 스카우트 제의가 들어옵니다.",
      "그런데 그중 한 곳은 당신의 각성을 미리 알고 있었던 눈치입니다.",
      "다음 달, 예고 없는 게이트 폭주가 예정되어 있습니다.",
      "그날 당신의 선택이 서울 절반의 생사를 가릅니다.",
    ],
  },
  {
    id: "hunter-02",
    intro: "만년 F급이던 당신, 던전 붕괴 직전 숨은 등급이 깨어나다",
    name: "서지훈 (헌터명: 크로우)",
    rank: "협회 미등록 각성자",
    personality: "귀찮음이 많지만 실전에선 냉정하게 판단",
    quote:
      "움직이는 건 딱 필요할 때 한 번이면 충분해. 대신 그 한 번은 절대 헛되지 않게 하고. …이게 게으른 거라고들 하던데, 나는 효율이라고 부르거든.",
    tones: ["somber", "intense"],
    stats: [
      { label: "전투력", kind: "star", stars: 4 },
      { label: "각성 등급", kind: "grade", grade: "S" },
      { label: "협동성", kind: "star", stars: 2 },
    ],
    appearance: {
      hair: "부스스한 흑발",
      eyes: "나른한 눈",
      impression: "귀찮은 표정",
      element: "그림자",
    },
    base: { str: 78, int: 74, agi: 70, luck: 66 },
    summon: {
      shape: "까마귀 형상",
      element: "어둠",
      colors: "칠흑 × 보라",
      stats: { str: 40, int: 70, agi: 92, luck: 60 },
    },
    fateBeats: [
      "재측정 결과, 당신의 등급은 '측정 불가'로 나옵니다.",
      "협회는 당신을 격리하려 하고, 길드는 당신을 빼돌리려 합니다.",
      "그 사이, 당신 안의 무언가가 점점 깨어나고 있습니다.",
      "일주일 뒤, 국가 등급 헌터 소집령이 내려집니다.",
    ],
  },
  {
    id: "hunter-03",
    intro: "게임 폐인 대학생, 현실에 열린 게이트에서 능력이 발현되다",
    name: "한세아 (헌터명: 프로스트)",
    rank: "신생 길드의 임시 에이스",
    personality: "말수는 적어도 위기엔 가장 앞에 섬",
    quote:
      "할 말이 없어서 조용한 게 아니야. 떠들 시간에 한 발 더 움직이는 편이 낫다고 생각할 뿐이지. 그래서 나는 늘, 제일 앞에 서 있게 되더라고.",
    tones: ["serene", "intense"],
    stats: [
      { label: "전투력", kind: "star", stars: 4 },
      { label: "각성 등급", kind: "grade", grade: "A" },
      { label: "협동성", kind: "star", stars: 4 },
    ],
    appearance: {
      hair: "하늘빛 포니테일",
      eyes: "옅은 청색 눈",
      impression: "조용하고 단단함",
      element: "얼음",
    },
    base: { str: 70, int: 68, agi: 84, luck: 58 },
    summon: {
      shape: "흰여우 형상",
      element: "얼음",
      colors: "설백 × 하늘",
      stats: { str: 55, int: 72, agi: 90, luck: 62 },
    },
    fateBeats: [
      "당신만 감지할 수 있는 균열이 도심 한복판에 열립니다.",
      "협회는 그것을 부정하지만, 한 헌터가 조용히 당신을 찾아옵니다.",
      "그는 10년 전 실종된 S급, 이미 죽었다고 알려진 사람입니다.",
      "그가 건넨 첫 마디는 '드디어 찾았다'였습니다.",
    ],
  },
  {
    id: "hunter-04",
    // ★ 쓸모없어 보이는 능력 → 사실상 최강. "왜?"를 유발
    intro: "각성은 했는데 능력이 '유통기한 되돌리기'인 S급 폐기물 처리반",
    name: "오민재 (헌터명: 리와인드)",
    rank: "협회 폐기물 처리 3팀 (계약직)",
    personality: "자기 능력이 하찮은 줄 알고 6년째 편의점 알바 병행",
    quote:
      "내 능력은 대단할 게 없어. 지나가 버린 걸 아주 조금 되돌리는 정도지. 그런데 있잖아 — 세상 사람들이 목숨 걸고 갖고 싶어 하는 게, 결국 그거 아니야?",
    tones: ["serene", "radiant"],
    stats: [
      { label: "전투력", kind: "star", stars: 1 },
      { label: "각성 등급", kind: "grade", grade: "F" },
      { label: "치트성", kind: "star", stars: 5 },
    ],
    appearance: {
      hair: "평범한 갈색 머리",
      eyes: "순한 눈",
      impression: "편의점 알바 같은 인상",
      element: "시간",
    },
    base: { str: 30, int: 68, agi: 50, luck: 97 },
    summon: {
      shape: "달팽이 형상",
      element: "시간",
      colors: "청록 × 금",
      stats: { str: 10, int: 82, agi: 14, luck: 90 },
    },
    fateBeats: [
      "당신의 능력은 지난 삼각김밥의 유통기한을 되돌리는 것뿐이었습니다.",
      "그런데 어느 날, 죽은 동료 헌터의 손을 잡았을 때도 능력이 발동합니다.",
      "협회는 이 사실을 알면 안 됩니다. 알면 당신은 사람이 아니게 됩니다.",
      "그리고 이미 한 사람이, 그 장면을 봤습니다.",
    ],
  },
  {
    id: "hunter-05",
    // ★ 아예 편이 뒤집히는 카드
    intro: "각성은 했는데 판정 결과가 '인간 아님', 던전 몬스터로 등록된 당신",
    name: "코드네임 K-07 (구 이름: 김하람)",
    rank: "협회 관리대상 개체 (헌터 자격 박탈)",
    personality: "사람 쪽에 서고 싶은데 사람이 안 받아줌",
    quote:
      "괴물이라고 불러도 상관없어. 어차피 서류엔 그렇게 적혀 있으니까. 다만 내가 무엇을 지키려고 여기 서 있는지는, 언젠가 한 번쯤 봐줬으면 좋겠어.",
    tones: ["somber", "intense"],
    stats: [
      { label: "전투력", kind: "star", stars: 5 },
      { label: "각성 등급", kind: "grade", grade: "S" },
      { label: "협동성", kind: "star", stars: 1 },
    ],
    appearance: {
      hair: "흐트러진 백발",
      eyes: "붉게 빛나는 눈",
      impression: "인간 같은데 어딘가 다름",
      element: "심연",
    },
    base: { str: 95, int: 62, agi: 82, luck: 24 },
    summon: {
      shape: "그림자 늑대떼",
      element: "심연",
      colors: "칠흑 × 핏빛",
      stats: { str: 88, int: 55, agi: 85, luck: 40 },
    },
    fateBeats: [
      "각성 검사 결과지에 등급 대신 '개체 분류: 미상'이 찍힙니다.",
      "다음 날부터 당신을 잡는 토벌 의뢰가 헌터 앱에 올라옵니다.",
      "현상금 1순위인 당신을 유일하게 감싸는 건, 어제 구해준 F급 헌터뿐입니다.",
      "그리고 던전 안쪽에서, 당신을 '왕'이라 부르는 목소리가 들립니다.",
    ],
  },
  {
    id: "hunter-06",
    // ★ 회귀물 비틀기 — 내가 주인공이 아니라 주인공의 남편
    intro: "S급 헌터로 각성한 줄 알았더니, 최강 길드마스터의 회귀 전 남편",
    name: "정우빈 (헌터명: 없음)",
    rank: "D급 헌터 · 그리고 그 사람의 전 배우자",
    personality: "약한데 이상하게 안 죽음",
    quote:
      "강해지고 싶었던 적은 한 번도 없어. 나는 그냥 끝까지 살아남고 싶었을 뿐이야. 그런데 그게, 세상에서 제일 어려운 재능이더라고.",
    tones: ["serene", "somber"],
    stats: [
      { label: "전투력", kind: "star", stars: 2 },
      { label: "각성 등급", kind: "grade", grade: "D" },
      { label: "생존력", kind: "star", stars: 5 },
    ],
    appearance: {
      hair: "차분한 흑발",
      eyes: "순한 갈색 눈",
      impression: "평범하고 순함",
      element: "무 (無)",
    },
    base: { str: 34, int: 58, agi: 54, luck: 99 },
    summon: {
      shape: "길고양이 형상",
      element: "무",
      colors: "치즈색 × 흰",
      stats: { str: 20, int: 50, agi: 72, luck: 95 },
    },
    fateBeats: [
      "국내 최강 길드마스터가 D급인 당신을 직접 지명해 데려갑니다.",
      "이유를 묻자 그는 대답 대신 당신의 왼손 약지를 바라봅니다.",
      "그는 3년 뒤에서 돌아왔고, 그 미래에서 당신은 이미 죽은 사람입니다.",
      "그가 처음 한 말은 '이번엔 던전 근처도 가지 마'였습니다.",
    ],
  },
];

export default CARDS;
