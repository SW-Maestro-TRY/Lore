// 아이돌 — 검수된 결과물 풀
// 검수 기준은 romance/index.ts 상단 주석 참고. 정통(01~03) + 반전(04~06).
import type { StoryCard } from "../types";

const CARDS: StoryCard[] = [
  {
    id: "idol-01",
    intro: "오디션 탈락 3번, 그런데 이번엔 대표가 직접 연락했다",
    name: "서하은 (활동명: 하니)",
    rank: "6년차 연습생 (기수 최고령)",
    personality: "무대에서만 다른 사람이 됨",
    quote:
      "평소의 나는 솔직히 별로 볼 게 없어. 눈도 잘 못 맞추고 말도 더듬거리고. 그런데 조명이 켜지는 그 순간만큼은, 나도 나를 좋아하게 되더라.",
    tones: ["serene", "somber"],
    stats: [
      { label: "보컬", kind: "star", stars: 5 },
      { label: "춤", kind: "star", stars: 3 },
      { label: "무대 존재감", kind: "star", stars: 4 },
    ],
    appearance: {
      hair: "밝은 갈색 롱 생머리",
      eyes: "크고 순한 눈",
      impression: "무대 밖에선 소심함",
      element: "청량",
    },
    base: { str: 48, int: 62, agi: 68, luck: 36 },
    fateBeats: [
      "6년 만에 처음으로 센터 자리에 당신의 이름이 적힙니다.",
      "그런데 원래 그 자리의 주인이 갑자기 활동을 중단합니다.",
      "그 이유가 당신과 관련 있다는 걸, 회사 사람 전부가 압니다. 당신만 빼고.",
      "쇼케이스까지, 이제 30일 남았습니다.",
    ],
  },
  {
    id: "idol-02",
    intro: "학교 축제 무대가 우연히 찍혀 천만 조회수를 찍은 당신",
    name: "정유라 (활동명: 유리)",
    rank: "데뷔조 경쟁 중인 2년차 연습생",
    personality: "평소엔 소심해도 카메라만 켜지면 돌변",
    quote:
      "사람들 앞에 서면 목소리부터 안 나와. 그런데 무대 위는 좀 이상해. 거기선 내가 나여야 할 필요가 없으니까, 무엇이든 될 수 있거든.",
    tones: ["radiant", "intense"],
    stats: [
      { label: "보컬", kind: "star", stars: 4 },
      { label: "춤", kind: "star", stars: 4 },
      { label: "무대 존재감", kind: "star", stars: 5 },
    ],
    appearance: {
      hair: "흑발 단발",
      eyes: "또렷한 눈",
      impression: "카메라만 켜지면 돌변",
      element: "발랄",
    },
    base: { str: 52, int: 58, agi: 82, luck: 64 },
    fateBeats: [
      "이번 달 데뷔조 발표에서 당신의 이름이 불립니다.",
      "하지만 같은 팀에 배정된 연습생은 당신에게 오래된 오해를 품고 있습니다.",
      "데뷔 무대까지 D-100, 진실을 밝힐 시간이 얼마 없습니다.",
      "그리고 그 무대를, 그때 영상을 찍은 사람이 지켜보고 있습니다.",
    ],
  },
  {
    id: "idol-03",
    intro: "연습생 계약 만료 하루 전, 걸려온 한 통의 전화",
    name: "김도아 (활동명: 도아)",
    rank: "계약 만료 D-1 연습생",
    personality: "완벽주의라 연습실을 제일 늦게 나감",
    quote:
      "완벽하지 않으면 잠이 안 와. 다들 그만해도 된다고, 충분하다고 말해주는데 — 나는 그만두는 법을 한 번도 배운 적이 없어서.",
    tones: ["somber", "serene"],
    stats: [
      { label: "보컬", kind: "star", stars: 3 },
      { label: "춤", kind: "star", stars: 5 },
      { label: "무대 존재감", kind: "star", stars: 4 },
    ],
    appearance: {
      hair: "애쉬브라운 미디움",
      eyes: "차분한 눈",
      impression: "서늘한 완벽주의",
      element: "몽환",
    },
    base: { str: 50, int: 74, agi: 76, luck: 42 },
    fateBeats: [
      "익명의 팬 계정 하나가 당신만 집요하게 밀어주기 시작합니다.",
      "그 계정의 정체가 회사 대표라는 소문이 돕니다.",
      "데뷔조 발표 전날, 당신에게만 다른 곡이 전달됩니다.",
      "이 곡을 부르는 순간, 계약서의 조항 하나가 발동합니다.",
    ],
  },
  {
    id: "idol-04",
    // ★ 유저가 직접 예로 든 케이스 — 컨셉·성별이 통째로 뒤집힘
    intro: "데뷔 D-1에 몸이 바뀌었다. 귀염둥이 여돌 → 도도 섹시 남자 연습생",
    name: "차유운 (활동명: 유운)",
    rank: "라이벌 팀 비주얼 센터 (원래 내 몸 아님)",
    personality: "표정은 도도한데 속은 아직 귀염둥이 컨셉",
    quote:
      "차가워 보인다는 말은 이제 익숙해. 다들 그렇게만 보니까 나도 그런 척하게 되고. 그런데 아무도 안 볼 때 내가 어떤 얼굴인지 알면, 아마 다들 놀랄걸?",
    tones: ["intense", "radiant"],
    stats: [
      { label: "보컬", kind: "star", stars: 4 },
      { label: "춤", kind: "star", stars: 5 },
      { label: "무대 존재감", kind: "star", stars: 5 },
    ],
    appearance: {
      hair: "흑발 시크컷",
      eyes: "날카로운 눈",
      impression: "도도한데 속은 귀염둥이",
      element: "시크",
    },
    base: { str: 70, int: 60, agi: 86, luck: 50 },
    fateBeats: [
      "내일이 데뷔 쇼케이스인데, 당신은 이 몸의 안무를 한 번도 춰본 적 없습니다.",
      "그런데 무대에 서는 순간, 몸이 먼저 동선을 기억합니다.",
      "원래 이 몸의 주인은 지금 당신의 몸으로 당신 팀 연습실에 앉아 있습니다.",
      "그리고 그는, 돌아올 생각이 전혀 없어 보입니다.",
    ],
  },
  {
    id: "idol-05",
    // ★ 뜬금없음 — 캡처해서 보내고 싶은 카드
    intro: "드디어 데뷔 확정. 그런데 나머지 멤버 넷이 전부 AI였다",
    name: "박시온 (활동명: 시온)",
    rank: "5인조 그룹의 유일한 인간 멤버",
    personality: "긴장을 웃음으로 감추는 분위기 메이커",
    quote:
      "웃고 있으면 아무도 괜찮냐고 묻지 않더라. 그게 편해서 계속 웃었는데, 이젠 웃지 않는 법을 잊어버린 것 같아. …그래서 오늘도 제일 크게 웃어.",
    tones: ["intense", "serene"],
    stats: [
      { label: "보컬", kind: "star", stars: 3 },
      { label: "춤", kind: "star", stars: 3 },
      { label: "인간미", kind: "star", stars: 5 },
    ],
    appearance: {
      hair: "밝은 톤 단정한 머리",
      eyes: "웃는 눈",
      impression: "긴장을 웃음으로 감추는 분위기 메이커",
      element: "청량",
    },
    base: { str: 45, int: 66, agi: 60, luck: 55 },
    fateBeats: [
      "멤버 넷은 잠도 안 자고 실수도 안 하고 스캔들도 없습니다.",
      "팬들은 유독 당신만 음이탈이 난다며 이유를 궁금해합니다.",
      "회사는 '컨셉'이라며 절대 밝히지 말라고 합니다.",
      "그런데 어제 새벽, 멤버 하나가 당신에게 먼저 물었습니다. '안 피곤해?'",
    ],
  },
  {
    id: "idol-06",
    // ★ 장르 자체가 어긋나는 카드
    intro: "아이돌 데뷔인 줄 알고 사인했는데, 알고 보니 트로트 5인조",
    name: "한정숙 (활동명: 정숙이)",
    rank: "신인 트로트 그룹 막내 (평균 연령 58세)",
    personality: "억울한데 무대만 서면 신남",
    quote:
      "솔직히 억울하지. 내가 꿈꾸던 무대는 이런 게 아니었으니까. 그런데 조명이 켜지고 첫 소절이 나오면 또 신이 나는 걸, 나더러 어쩌라는 거야.",
    tones: ["radiant", "somber"],
    stats: [
      { label: "보컬", kind: "star", stars: 5 },
      { label: "꺾기", kind: "star", stars: 5 },
      { label: "무대 존재감", kind: "star", stars: 4 },
    ],
    appearance: {
      hair: "뽀글 파마 올림머리",
      eyes: "서글서글한 눈",
      impression: "억울한데 무대만 서면 신남",
      element: "흥",
    },
    base: { str: 60, int: 55, agi: 64, luck: 78 },
    fateBeats: [
      "계약서 장르란에 아주 작게 '성인가요'라고 적혀 있었습니다.",
      "첫 행사는 지역 축제, 관객은 열두 명이었습니다.",
      "그런데 그날 영상이 알고리즘을 타고 조회수 800만을 찍습니다.",
      "다음 주 스케줄은, 아이돌 시상식 축하무대입니다.",
    ],
  },
];

export default CARDS;
