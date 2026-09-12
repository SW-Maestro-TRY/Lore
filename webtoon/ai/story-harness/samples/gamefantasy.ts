// 게임 판타지 — 검수된 결과물 풀
//
// 검수 기준: 한 장이 나왔을 때 "어? 왜?"가 나오거나, 너무 잘 맞아서, 아니면
// 아예 뜬금없어서 캡처해 보내고 싶어야 한다.
// 정통(01~03)과 판을 뒤집는 반전(04~06)을 섞는다.
// 반전 카드가 뽑히는 게 이 세계관의 진짜 훅이다 — 절대 정통 카드로만 채우지 말 것.
//
// tones: 사진을 올렸을 때 이 카드의 확률이 올라가는 분위기. 4종이 골고루 깔려야
// 어떤 사진을 넣어도 후보가 생긴다.
import type { StoryCard } from "../types";

const CARDS: StoryCard[] = [
  {
    id: "gamefantasy-01",
    intro: "공격력이 0인 직업을 고른 당신, 대신 보스의 소수점을 읽는다",
    name: "윤재하 (닉네임: 나머지)",
    rank: "히든 클래스 계수사 · 무명 길드",
    personality: "숫자 앞에선 한 치도 안 봐주면서, 사람 말은 일단 다 믿고 봄",
    quote:
      "내 스킬은 아무도 못 죽여. 그냥 보이게 할 뿐이야. 그런데 다들 모르더라 — 저 보스의 체력이 왜 하필 9,999가 아니라 10,001인지.",
    tones: ["intense", "radiant"],
    stats: [
      { label: "레벨", kind: "star", stars: 2 },
      { label: "공략 점수", kind: "grade", grade: "SSR" },
      { label: "화력", kind: "star", stars: 1 },
    ],
    appearance: {
      hair: "정리 안 된 흑발",
      eyes: "초점이 빠른 눈",
      impression: "말수 적은 모범생 같음",
      element: "수식",
    },
    base: { str: 42, int: 94, agi: 58, luck: 71 },
    summon: {
      shape: "떠다니는 주판 형상",
      element: "수식",
      colors: "먹빛 × 형광 연두",
      stats: { str: 40, int: 92, agi: 55, luck: 68 },
    },
    fateBeats: [
      "「무한계단」 47층 보스는 3년째 아무도 못 깼습니다.",
      "당신은 그 보스가 매 페이즈마다 체력을 1씩 남기고 회복한다는 걸 읽어 냅니다.",
      "즉 깎는 게 아니라, 회복을 못 하게 만들면 됩니다.",
      "그 사실을 공개하려던 밤, 운영진 공지가 먼저 뜹니다. '47층 점검 무기한 연장'.",
    ],
  },
  {
    id: "gamefantasy-02",
    intro: "서버 3위 랭커인 당신, 다음 레이드에 회사 연봉이 걸려 있다",
    name: "도경완 (닉네임: 적란)",
    rank: "서버 3위 랭커 · 길드 <강철사도> 부단주",
    personality: "아무도 안 믿는다고 말하면서 파티창은 늘 열어 둠",
    quote:
      "이 판에서 실력으로 지는 건 견딜 만해. 못 견디는 건 정산 회의에서 지는 쪽이지. 나는 그래서 로그아웃한 뒤에도 계속 싸우고 있어.",
    tones: ["somber", "intense"],
    stats: [
      { label: "레벨", kind: "star", stars: 5 },
      { label: "숙련도", kind: "grade", grade: "S" },
      { label: "정치력", kind: "star", stars: 4 },
    ],
    appearance: {
      hair: "짧게 친 검은 머리",
      eyes: "피곤한 눈",
      impression: "정장이 어울리는 전투광",
      element: "전격",
    },
    base: { str: 88, int: 79, agi: 84, luck: 45 },
    fateBeats: [
      "길드 <강철사도>는 게임 회사가 아니라 투자사가 굴리는 팀입니다.",
      "다음 레이드 성적으로 스무 명의 계약 연장 여부가 갈립니다.",
      "당신은 승률 100%짜리 공략을 알아냈지만, 그건 길드원 셋을 버리는 전개입니다.",
      "레이드 전날 밤, 그 셋 중 한 명이 당신에게 귓속말을 겁니다. '형, 알고 있어요.'",
    ],
  },
  {
    id: "gamefantasy-03",
    intro: "싸우지 않는 기록사, 죽는 순서를 적다가 부활 조건을 찾아내다",
    name: "임서윤 (닉네임: 각주)",
    rank: "서포트 클래스 기록사 · 공략 위키 관리자",
    personality: "누구보다 다정한데, 정작 자기 이름은 기록에서 매번 빼놓음",
    quote:
      "나는 앞에 못 서. 대신 누가 어떻게 쓰러졌는지는 한 줄도 안 놓치고 적어 둬. 그 기록이 언젠가 누군가를 살릴 거라고, 아직도 믿거든.",
    tones: ["serene", "radiant"],
    stats: [
      { label: "레벨", kind: "star", stars: 3 },
      { label: "기록 정확도", kind: "grade", grade: "SSR" },
      { label: "생존력", kind: "star", stars: 2 },
    ],
    appearance: {
      hair: "귀 뒤로 넘긴 갈색 단발",
      eyes: "따뜻한 밤색 눈",
      impression: "조용하고 성실함",
      element: "잉크",
    },
    base: { str: 44, int: 90, agi: 62, luck: 77 },
    summon: {
      shape: "종이로 접힌 새",
      element: "잉크",
      colors: "미색 × 남색",
      stats: { str: 40, int: 86, agi: 88, luck: 70 },
    },
    fateBeats: [
      "당신은 4년간 이 서버에서 죽은 플레이어 8,412명을 전부 적어 왔습니다.",
      "그 목록을 정렬하다, 죽은 자리와 시각에 규칙이 있다는 걸 발견합니다.",
      "규칙대로라면 이 게임의 사망은 삭제가 아니라 '보관'입니다.",
      "확인차 옛 사냥터로 간 밤, 3년 전 죽은 파티원이 당신 이름을 부릅니다.",
    ],
  },
  {
    id: "gamefantasy-04",
    intro: "랭킹 1위가 신분 숨기고 만든 부캐, 하필 개발사가 지운 직업을 골랐다",
    name: "차하늘 (부캐 닉네임: 빗자루)",
    rank: "레벨 4 청소부 · 초보자 마을 소속 (본캐는 서버 1위)",
    personality: "정체 들킬까 벌벌 떨면서 자꾸 오지랖 부려서 눈에 띔",
    quote:
      "쉬려고 만든 캐릭터야. 진짜야. 빗자루 하나 들고 잡템이나 줍겠다는데, 왜 자꾸 하늘에서 개발자 채팅창이 내려오는 건데?",
    tones: ["radiant", "intense"],
    stats: [
      { label: "레벨", kind: "star", stars: 1 },
      { label: "희귀도", kind: "grade", grade: "SSR" },
      { label: "정체 은폐", kind: "star", stars: 1 },
    ],
    appearance: {
      hair: "대충 묶은 밝은 갈색",
      eyes: "장난기 있는 눈",
      impression: "초보자 옷인데 자세가 이상하게 좋음",
      element: "먼지",
    },
    base: { str: 51, int: 73, agi: 90, luck: 88 },
    fateBeats: [
      "직업 선택창 맨 아래, 아무도 안 고르는 '청소부'를 재미로 눌렀습니다.",
      "그 순간 아무에게도 안 보이는 회색 창이 뜹니다. '테스트 계정 확인됨.'",
      "청소부의 스킬은 하나뿐입니다 — 맵에 남은 것을 지웁니다. 몬스터도 포함해서.",
      "그리고 어제, 본캐 계정에 접속이 안 됩니다. 누군가 이미 쓰고 있습니다.",
    ],
  },
  {
    id: "gamefantasy-05",
    intro: "8년 만에 복귀했더니 서비스는 끝났는데 서버가 안 꺼져 있다",
    name: "송민규 (닉네임: 늦은봄)",
    rank: "휴면 계정 복귀자 · 폐쇄된 서버의 마지막 접속자",
    personality: "다 잊었다고 웃으면서, 형이 쓰던 낡은 검은 아직도 못 팜",
    quote:
      "종료 공지 뜬 지 6년이야. 그런데 로그인이 되더라. 마을 대장장이가 나를 보더니 그러더라고 — '형님은 어제도 오셨는데, 오늘은 왜 혼자세요?'",
    tones: ["somber", "serene"],
    stats: [
      { label: "레벨", kind: "star", stars: 4 },
      { label: "장비 노후도", kind: "grade", grade: "A" },
      { label: "미련", kind: "star", stars: 5 },
    ],
    appearance: {
      hair: "빛바랜 흑발",
      eyes: "웃을 때 접히는 눈",
      impression: "다 큰 어른이 옛날 옷을 입은 느낌",
      element: "무속성",
    },
    base: { str: 66, int: 61, agi: 57, luck: 90 },
    summon: {
      shape: "다리 저는 늙은 개",
      element: "무속성",
      colors: "낡은 갈색 × 흰",
      stats: { str: 45, int: 55, agi: 43, luck: 92 },
    },
    fateBeats: [
      "형이 죽고 8년, 당신은 형의 계정으로 「달빛항로」에 다시 접속합니다.",
      "서비스는 종료됐는데 마을 NPC들은 당신 형을 어제 일처럼 기억합니다.",
      "심지어 그의 접속 기록이 매주 목요일 밤마다 갱신되고 있습니다.",
      "이번 목요일, 광장에서 기다리면 된다고 대장장이가 알려 줍니다.",
    ],
  },
  {
    id: "gamefantasy-06",
    intro: "대리 육성 알바인 당신, 맡은 계정의 주인은 3년째 로그아웃을 못 했다",
    name: "한지오 (대리 중인 닉네임: 무명제(無名帝))",
    rank: "대리 육성 계약직 · 서버 1위 계정 관리자",
    personality: "남의 인생을 대신 사는 게 직업인데, 정작 자기 캐릭터는 없음",
    quote:
      "시급 만 이천 원에 서버 1위를 굴려. 이름도 얼굴도 내 게 아니야. 그런데 요즘, 내가 안 누른 스킬이 자꾸 나가더라고.",
    tones: ["intense", "serene"],
    stats: [
      { label: "레벨", kind: "star", stars: 5 },
      { label: "계정 권한", kind: "grade", grade: "B" },
      { label: "조작 일치율", kind: "star", stars: 3 },
    ],
    appearance: {
      hair: "눌린 자국이 남은 흑발",
      eyes: "잠 못 잔 눈",
      impression: "빌린 옷을 입은 사람 같음",
      element: "코드",
    },
    base: { str: 80, int: 83, agi: 86, luck: 40 },
    fateBeats: [
      "계약 내용은 단순합니다. 하루 열두 시간, 이 계정을 1위로 유지할 것.",
      "고용주는 3년간 한 번도 얼굴을 보인 적이 없습니다. 입금만 정확합니다.",
      "당신이 로그아웃한 새벽에도 캐릭터의 접속 시간은 계속 쌓입니다.",
      "오늘 처음으로 캐릭터가 당신에게 귓속말을 보냅니다. '이제 그만 나가 줄래요?'",
    ],
  },
];

export default CARDS;
