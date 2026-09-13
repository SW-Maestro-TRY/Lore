// 위자드 정적 데이터 — haeun/landing/web/app.js 의 같은 이름 상수를 그대로
// 옮겼다. 서버가 아직 없어서 지금은 여기 상수로 두지만, 실제로는 스타일
// 목록·장르 목록 다 config.yaml/서버가 정하는 값이라 백엔드가 붙으면
// /api/config 에서 받아오는 쪽으로 옮겨야 한다(원본과 같은 이유 — 화면에
// 베껴 두면 서버 쪽 목록과 갈라진다).

/* 원본(app.js 의 STYLE_INFO)과 **순서까지 같다.** 화면에 이 목록이 있는
   것 자체가 임시다 — 실제로 무엇을 그릴 수 있는지는 하네스가 정한다
   (newharness_pipeline 의 STYLE_CHOICES). 서버에서 받아오는 쪽으로 옮겨야
   하는데, 그때까지는 원본과 손으로 맞춘다.

   실제로 어긋났던 적이 있다 — 이식본이 한동안 없어진 「선화 · 액션」을
   보여주고, 새로 생긴 「세미리얼」·「게임 원화」는 안 보여줬다. */
export const STYLE_INFO: [key: string, label: string, desc: string][] = [
  ["webtoon", "일반 웹툰", "깔끔한 선과 셀 채색. 매주 연재하는 그 그림 — 읽히는 속도가 기준입니다."],
  ["romance", "로맨스 판타지", "표지 일러스트급 밀도. 보석 같은 눈, 장미와 금박, 레이스까지 하나하나."],
  ["shoujo", "순정 · BL", "얼굴과 둘 사이의 거리. 길고 날카로운 눈, 스크린톤, 여백에 뜬 꽃."],
  ["frost", "세미리얼 · 성인향", "사실적인 인체에 선은 얇고 듬성듬성, 진한 디테일은 얼굴·손에만. 넓은 면은 비워 두고 저채도로 차분하게."],
  ["pastel", "일상툰 감성", "일부러 덜 완성한 그림. 흔들리는 연필선, 종이 결, 바랜 파스텔 몇 색."],
  ["noir", "다크 느와르", "어둠이 주인공입니다. 화면 대부분이 먹으로 덮이고 빛은 얇게 남습니다."],
  ["cinematic", "시네마틱 반실사", "빛으로 화려해집니다. 역광·공기·얕은 심도·필름 색보정. 얼굴은 웹툰 그대로."],
  ["game", "게임 원화", "고급 모바일 게임 캐릭터 CG. 섬세한 선화에 은은하게 빛나는 채색과 정제된 조명까지."],
];

export const GENRE_QUICK = [
  "로맨스 판타지", "무협", "판타지", "헌터·게이트",
  "마법학교", "게임 판타지", "센티넬", "오메가버스",
  "아이돌", "스릴러", "액션", "개그", "일상",
  "히어로",
];

export const GENRE_NOTE: Record<string, string> = {
  "로맨스 판타지": "드레스와 무도회, 계약 결혼과 회귀. 감정이 사건을 끕니다.",
  "무협": "강호와 문파, 내공과 검. 은원이 이야기를 끕니다.",
  "판타지": "검과 마법, 다른 세계. 종족과 왕국이 배경이 됩니다.",
  "헌터·게이트": "현대 한국에 열린 게이트. 각성자와 길드, 등급이 규칙입니다.",
  "마법학교": "입학과 기숙사, 수업과 시험. 학교가 세계의 크기입니다.",
  "게임 판타지": "상태창과 레벨, 퀘스트와 스킬. 규칙이 눈에 보입니다.",
  "센티넬": "가이드와 센티넬, 감각 폭주와 결합. 관계가 곧 설정입니다.",
  "오메가버스": "알파·베타·오메가, 페로몬과 각인. 관계의 규칙이 세계입니다.",
  "아이돌": "연습생과 데뷔, 무대와 팬. 성장과 경쟁이 축입니다.",
  "스릴러": "쫓고 쫓기는 것. 정보를 언제 주는지가 연출이 됩니다.",
  "액션": "몸으로 부딪히는 것. 합과 속도로 컷을 나눕니다.",
  "개그": "박자와 배신. 컷의 크기 차이로 웃깁니다.",
  "일상": "큰 사건 없이 하루하루. 인물의 결이 곧 이야기입니다.",
  "히어로": "능력과 빌런, 등록과 자경단. 누가 구할 자격을 갖느냐가 규칙입니다.",
};

export const GENRE_NOTE_EMPTY =
  "비워두면 루가 골라요 — 앞에서 적은 캐릭터 설명을 보고 이야기에 맞는 장르를 정합니다.";

export const WIZ_NAMES = ["수면", "항해", "깊은 바다", "심해", "바닥"];
export const WIZ_LAST = 5;
export const MAX_PHOTOS = 4;

export type WizardMode = "simple" | "expert";

/** 얼마나 촘촘히 그릴까. 값은 서버(WebtoonQuality)가 아는 이름과 같아야 한다. */
export type WizardQuality = "wave" | "surf" | "swell";

/* 화질 셋 — 이름 · 한 줄 · 설명.
 *
 * **크레딧 값은 여기 안 적는다.** 서버가 `/nh/allowance` 의 `qualities` 로
 * 내려 준다(WebtoonQuality). 여기에도 적어 두면 한쪽만 고치는 순간 화면이
 * 적은 값과 실제로 빠지는 크레딧이 어긋나고, 그건 사람에게 거짓말이 된다.
 *
 * 시간은 **줄이 비었을 때** 기준이다. 만들기가 한 번에 한 편씩 돌기 때문에
 * 앞에 사람이 있으면 그만큼 더 걸린다 — 그래서 "약" 을 붙인다. */
export const QUALITY_INFO: {
  key: WizardQuality;
  label: string;
  lede: string;
  desc: string[];
}[] = [
  {
    key: "wave",
    label: "물결",
    lede: "약 6분 · 가장 빠른 생성",
    desc: ["굵고 단순한 선으로 가볍게 표현해요.", "배경과 소품은 필요한 만큼만 담아요."],
  },
  {
    key: "surf",
    label: "파도",
    lede: "약 8분 · 자연스러운 디테일",
    desc: ["인물과 배경을 가장 자연스럽게 표현해요."],
  },
  {
    key: "swell",
    label: "너울",
    lede: "약 15분 · 가장 섬세한 표현",
    desc: ["가는 선과 풍부한 디테일로 표현해요.", "배경과 소품까지 깊이 있게 담아내요."],
  },
];

export const QUALITY_DEFAULT: WizardQuality = "surf";

export interface WizardForm {
  photos: string[]; // data URL
  /** 「이 캐릭터로 웹툰 만들기」로 들어왔을 때. 서버가 이 번호로 그림을 붙인다 —
   *  브라우저가 그림을 내려받아 다시 올릴 이유가 없다. */
  characterId?: string;
  /** 미리 보여줄 그림 주소. 보내는 값이 아니라 화면에만 쓴다. */
  characterArt?: string;
  name: string;
  character: string;
  story: string;
  genre: string;
  style: string;
  quality: WizardQuality;
  mode: WizardMode;
  agreeIp: boolean;
}

export const emptyWizardForm = (): WizardForm => ({
  photos: [],
  characterId: undefined,
  characterArt: undefined,
  name: "",
  character: "",
  story: "",
  genre: "",
  style: "",
  quality: QUALITY_DEFAULT,
  mode: "simple",
  agreeIp: false,
});
