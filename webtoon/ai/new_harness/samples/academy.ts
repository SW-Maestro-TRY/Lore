// 마법학교 — 검수된 결과물 풀
// 검수 기준은 romance/index.ts 상단 주석 참고. 정통(01~03) + 반전(04~06).
import type { StoryCard } from "../types";

const CARDS: StoryCard[] = [
  {
    id: "academy-01",
    intro: "입학 첫날 배정받은 기숙사, 100년 만에 처음 문이 열린 곳이었다",
    name: "리안 블랙우드",
    rank: "4번 기숙사 유일한 학생",
    personality: "호기심 많고 규칙을 자주 어김",
    quote:
      "하지 말라는 말에는 대개 그럴 만한 이유가 있겠지. 그런데 어른들은 그 이유를 끝내 알려주지 않잖아. 그럼 결국 직접 확인하는 수밖에 없는 거 아니야?",
    tones: ["somber", "intense"],
    stats: [
      { label: "주문력", kind: "star", stars: 4 },
      { label: "포션 제조", kind: "star", stars: 2 },
      { label: "담력", kind: "star", stars: 5 },
    ],
    appearance: {
      hair: "검은 곱슬머리",
      eyes: "짙은 눈",
      impression: "호기심이 가득함",
      element: "어둠",
    },
    base: { str: 55, int: 78, agi: 62, luck: 40 },
    summon: {
      shape: "검은 부엉이 형상",
      element: "어둠",
      colors: "칠흑 × 남색",
      stats: { str: 40, int: 82, agi: 70, luck: 50 },
    },
    fateBeats: [
      "입학식 날, 100년간 비어 있던 4번 기숙사에 당신 혼자 배정됩니다.",
      "교장은 '착오'라 말하지만, 도서관 사서는 당신을 볼 때마다 표정이 굳습니다.",
      "금서 보관실에서 들려오는 소리는 밤마다 커지고 있습니다.",
      "그 소리는, 당신의 이름을 부르고 있습니다.",
    ],
  },
  {
    id: "academy-02",
    intro: "합격 통지서를 받은 적이 없는데, 입학 명단에는 내 이름이 있었다",
    name: "노아 세인트클레어",
    rank: "명단에 없던 특례 입학생",
    personality: "조용하지만 금서만 보면 눈이 반짝임",
    quote:
      "세상이 감춰둔 것에는 다 이유가 있다고들 하더라. 나도 알아. 아는데, 그 이유가 궁금해서 도저히 견딜 수가 없는 걸 어떡해.",
    tones: ["somber", "serene"],
    stats: [
      { label: "주문력", kind: "star", stars: 3 },
      { label: "포션 제조", kind: "star", stars: 5 },
      { label: "담력", kind: "star", stars: 3 },
    ],
    appearance: {
      hair: "차분한 은발",
      eyes: "옅은 회청색 눈",
      impression: "조용한 몽상가",
      element: "물",
    },
    base: { str: 40, int: 88, agi: 58, luck: 52 },
    summon: {
      shape: "말하는 검은 고양이",
      element: "예지",
      colors: "칠흑 × 금빛 눈",
      stats: { str: 25, int: 92, agi: 86, luck: 70 },
    },
    fateBeats: [
      "입학 명부에서 당신의 이름만 잉크가 번져 읽히지 않습니다.",
      "누군가 당신의 존재를 지우려 한다는 뜻입니다.",
      "유일하게 당신을 기억하는 건, 말을 하는 검은 고양이 한 마리뿐입니다.",
      "고양이는 말합니다. '드디어 돌아왔군요.'",
    ],
  },
  {
    id: "academy-03",
    intro: "마법을 못 쓰는 줄 알았던 당신, 입학식에서 지팡이가 폭주하다",
    name: "엘리 하버포드",
    rank: "1학년 신입생 (관찰 대상)",
    personality: "겁이 많은데 왜인지 위험을 못 지나침",
    quote:
      "나는 겁이 정말 많아. 손이 떨리고 다리에 힘이 풀리고, 매번 도망치고 싶어져. 그런데도 발이 자꾸 그쪽으로 향하는 걸, 나도 어쩌지 못하겠어.",
    tones: ["intense", "radiant"],
    stats: [
      { label: "주문력", kind: "star", stars: 5 },
      { label: "포션 제조", kind: "star", stars: 2 },
      { label: "담력", kind: "star", stars: 3 },
    ],
    appearance: {
      hair: "붉은 갈색 웨이브",
      eyes: "겁먹은 큰 눈",
      impression: "소심한데 이상하게 무모함",
      element: "불",
    },
    base: { str: 38, int: 60, agi: 55, luck: 48 },
    summon: {
      shape: "작은 불도마뱀 형상",
      element: "불",
      colors: "주홍 × 검정",
      stats: { str: 35, int: 45, agi: 74, luck: 40 },
    },
    fateBeats: [
      "배정받은 지팡이가 주인을 거부하고 스스로 폭주합니다.",
      "그 광경을 본 상급생 하나가 조용히 당신에게 다가옵니다.",
      "그는 100년 전 사라진 마법사의 마지막 제자라 자신을 소개합니다.",
      "그날 밤, 당신의 창문에 낯선 문양이 새겨집니다.",
    ],
  },
  {
    id: "academy-04",
    // ★ 신입생인 줄 알았는데 흑막 본인 — "왜?" 유발
    intro: "신입생인 줄 알았는데, 교수 전원이 나를 감시하고 있었다",
    name: "카이 웨스트모어",
    rank: "1학년 · 100년 전 학교를 무너뜨린 그 마법사와 동일 마력파장",
    personality: "장난기 많고 사고는 늘 크게 침",
    quote:
      "일부러 그런 건 아니었어. 진짜야. 근데 매번 이렇게 되는 걸 보면 어쩌면 나한테 문제가 있는 걸지도 모르겠네. …그래도 재밌었잖아, 안 그래?",
    tones: ["intense", "somber"],
    stats: [
      { label: "주문력", kind: "star", stars: 5 },
      { label: "포션 제조", kind: "star", stars: 1 },
      { label: "담력", kind: "star", stars: 5 },
    ],
    appearance: {
      hair: "헝클어진 백금발",
      eyes: "장난기 어린 눈",
      impression: "사고뭉치 같은 미소",
      element: "파괴",
    },
    base: { str: 60, int: 80, agi: 78, luck: 34 },
    summon: {
      shape: "그림자 여우 형상",
      element: "파괴",
      colors: "칠흑 × 진홍",
      stats: { str: 70, int: 72, agi: 82, luck: 45 },
    },
    fateBeats: [
      "첫 수업부터 교수들이 당신 자리만 유독 오래 쳐다봅니다.",
      "학교 지하에 봉인된 것이 당신을 보고 반응했기 때문입니다.",
      "당신의 마력 파장은 100년 전 이 학교를 무너뜨린 자와 정확히 같습니다.",
      "교장이 묻습니다. '자네, 올해 몇 살이라고 했지?'",
    ],
  },
  {
    id: "academy-05",
    // ★ 뜬금없는 배정 — 웃겨서 공유하는 카드
    intro: "명문 마법학교 합격! 그런데 배정 학과가 '마수 사육학'",
    name: "핍 오르넬리",
    rank: "마수 사육학과 1학년 (전교생 3명)",
    personality: "마법보다 동물이랑 있을 때 표정이 훨씬 좋음",
    quote:
      "사람 말은 너무 어려워. 웃어야 할 때를 자꾸 놓치고, 하고 나면 늘 후회해. 그런데 얘네는 거짓말을 안 하잖아. 나는 그게 그렇게 편하더라.",
    tones: ["radiant", "serene"],
    stats: [
      { label: "주문력", kind: "star", stars: 1 },
      { label: "마수 교감", kind: "star", stars: 5 },
      { label: "담력", kind: "star", stars: 4 },
    ],
    appearance: {
      hair: "부스스한 갈색 머리",
      eyes: "순한 눈",
      impression: "동물 앞에서만 표정이 밝음",
      element: "교감",
    },
    base: { str: 42, int: 50, agi: 60, luck: 58 },
    summon: {
      shape: "3층 높이의 검은 늑대",
      element: "대지",
      colors: "흑 × 은",
      stats: { str: 96, int: 42, agi: 54, luck: 60 },
    },
    fateBeats: [
      "당신이 배정받은 담당 마수는 3층짜리 검은 늑대입니다.",
      "그 늑대는 다른 사람은 다 물었는데, 당신에게만 배를 보입니다.",
      "알고 보니 그 늑대는 교장의 옛 연인이 변한 모습입니다.",
      "그리고 늑대는 지금, 당신에게 무언가를 말하려 하고 있습니다.",
    ],
  },
  {
    id: "academy-06",
    // ★ 시점이 뒤집히는 카드
    intro: "마법학교에 들어왔는데, 나만 마법을 못 쓰는 유일한 '일반인'",
    name: "테오 마르셀",
    rank: "마력 수치 0 · 서류상 존재할 수 없는 학생",
    personality: "주눅 들 법도 한데 이상하게 당당함",
    quote:
      "너는 아무것도 할 수 없다는 말, 지겹도록 들으며 자랐어. 이젠 익숙해질 법도 한데 여전히 아프더라. 그런데 아무것도 통하지 않는다는 건… 조금 다른 얘기 아닐까?",
    tones: ["serene", "radiant"],
    stats: [
      { label: "주문력", kind: "star", stars: 1 },
      { label: "포션 제조", kind: "star", stars: 4 },
      { label: "눈치", kind: "star", stars: 5 },
    ],
    appearance: {
      hair: "평범한 흑발",
      eyes: "담담한 눈",
      impression: "주눅 들 법도 한데 당당함",
      element: "무 (無)",
    },
    base: { str: 45, int: 70, agi: 52, luck: 82 },
    summon: {
      shape: "흰 나비 형상",
      element: "무",
      colors: "투명 × 은",
      stats: { str: 5, int: 60, agi: 85, luck: 90 },
    },
    fateBeats: [
      "측정기에 손을 올리자 마력 수치가 0.00으로 찍힙니다.",
      "그런데 당신 앞에서는 어떤 마법도 발동하지 않습니다. 저주까지도요.",
      "학교는 당신을 낙제시키는 대신, 조용히 교장실로 부릅니다.",
      "'자네 같은 학생을, 우리는 백 년째 기다렸다네.'",
    ],
  },
];

export default CARDS;
