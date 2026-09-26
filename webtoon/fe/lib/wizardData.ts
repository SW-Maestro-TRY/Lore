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
  ["webtoon", "일반 웹툰", "깔끔한 선과 셀 채색. 읽히는 속도가 기준."],
  ["romance", "로맨스 판타지", "표지 일러스트급 밀도. 보석 같은 눈, 금박, 레이스."],
  ["shoujo", "순정 · BL", "길고 날카로운 눈, 스크린톤, 여백에 뜬 꽃."],
  ["frost", "세미리얼 · 성인향", "사실적인 인체, 얇은 선, 저채도로 차분하게."],
  ["pastel", "일상툰 감성", "흔들리는 연필선, 종이 결, 바랜 파스텔."],
  ["game", "게임 원화", "섬세한 선화에 은은하게 빛나는 채색."],
];

export const GENRE_QUICK = [
  "로맨스 판타지", "무협", "판타지", "헌터·게이트",
  "마법학교", "게임 판타지", "센티넬", "오메가버스",
  "아이돌", "스릴러", "액션", "개그", "일상",
  "히어로",
];

export const GENRE_NOTE: Record<string, string> = {
  "로맨스 판타지": "중세풍 제국에서 펼쳐지는 연애 이야기예요. 소설 속 인물로 빙의하거나 인생을 다시 사는 회귀가 흔한 시작이에요.",
  "무협": "무공을 익힌 고수들이 문파를 이루고 겨루는 옛 동양풍 세계 이야기예요.",
  "판타지": "검과 마법, 몬스터가 있는 다른 세계에서 벌어지는 모험 이야기예요.",
  "헌터·게이트": "현대에 괴물이 나오는 문(게이트)이 열리고, 능력에 눈뜬 사람들이 등급을 받아 사냥에 나서는 이야기예요.",
  "마법학교": "마법을 배우는 학교가 무대인 학원물이에요. 수업과 시험, 친구와 라이벌, 학교 밖에서 몰려오는 사건까지 다뤄요.",
  "게임 판타지": "게임처럼 상태창·레벨·스킬이 눈에 보이는 세계, 혹은 게임 속에 들어간 이야기예요.",
  "센티넬": "초감각을 가진 '센티넬'과, 그 감각이 폭주하지 않게 붙잡아 주는 '가이드'가 짝을 이루는 세계예요.",
  "오메가버스": "남녀 말고 알파·베타·오메가라는 두 번째 성별이 있는 세계예요. 알파와 오메가는 페로몬으로 서로에게 강하게 끌려요.",
  "아이돌": "연습생이 데뷔를 향해 경쟁하고 무대에 오르는 아이돌 업계 이야기예요.",
  "스릴러": "누가, 왜를 쫓는 긴장감 있는 이야기예요. 위험과 반전이 핵심이에요.",
  "액션": "싸움과 추격이 중심인 이야기예요. 몸으로 부딪히는 장면이 볼거리예요.",
  "개그": "웃기는 게 먼저인 이야기예요. 엉뚱한 상황과 반응으로 굴러가요.",
  "일상": "큰 사건 없이 소소한 하루를 그리는 편안한 이야기예요.",
  "히어로": "초능력을 가진 히어로와 악당(빌런)이 있는 현대 도시 이야기예요.",
};

/** 위자드 네 걸음 — 캔버스의 「웹툰 만들기 1~4」. */
export const WIZ_STEPS = ["캐릭터", "이야기 · 장르", "그림체", "그리기 방식"];
export const WIZ_LAST = 4;
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
 * 시간은 **줄이 비었을 때** 기준이다. 만들기가 한 번에 두 편까지만 돌기 때문에
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
  mode: "expert",
  agreeIp: false,
});

/** 하네스 그림체 이름(캐릭터 카드의 style) → 화면 키. 카드에서 1화로 넘어갈 때
 *  요약에 그림체 이름을 적으려고 쓴다. 서버는 어느 쪽이든 받는다. */
export const STYLE_KEY_OF_HARNESS: Record<string, string> = {
  webtoon_lock_bg: "webtoon", romance_fantasy: "romance", shoujo: "shoujo",
  frost: "frost", pastel: "pastel", game: "game",
};

export function styleLabelOf(keyOrHarness: string): string {
  const key = STYLE_KEY_OF_HARNESS[keyOrHarness] ?? keyOrHarness;
  return STYLE_INFO.find(([k]) => k === key)?.[1] ?? "";
}
