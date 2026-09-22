// 여울 시안의 데이터 표. **숫자와 문구만** — React 도 화면도 모른다.
//
// 출처 = 클로드 디자인 `여울 반응형.dc.html`(2026-09-07 상훈님 최종본)의 상수부를 그대로 옮긴 것.
// 시안이 정본이므로 값을 임의로 바꾸지 않는다. 바꿀 일이 생기면 시안을 먼저 올리고 여기를 맞춘다.
//
// ★ 여기 값은 **프론트 전용 목**이다. 서버가 붙으면 rules.ts·서버 응답이 정본을 들고 오고
//   이 표는 문구(카피)만 남는다. 지금 숫자는 화면을 눌러 보기 위한 자리표시다.
import { assetUrl, demoUrl } from '../constants';
import { propUrl } from '../props/spec';
import { C } from './ui';
import type { Personality as PersonalityValue } from '../../lib/pet';

/** 방 다섯 칸. key 는 상태 저장·검사의 손잡이라 함부로 안 바꾼다. */
export const ROOM_KEYS = ['table', 'bath', 'play', 'bed', 'album'] as const;
export type RoomKey = (typeof ROOM_KEYS)[number];

/** 하단 타일에 적히는 이름(시안 확정: 식탁→주방, 놀이→마당). */
export const ROOM_NAME: Record<RoomKey, string> = {
  table: '주방', bath: '욕실', play: '마당', bed: '침실', album: '앨범',
};

/**
 * 시트 제목. **넷만 남았다** — 주방·욕실·침실은 시트가 없어졌다(상훈님 2026-09-08 판정 12).
 * ★ 방 이름은 **주방·욕실·마당·침실** 로 통일한다(2026-09-08). 같은 방을 두 이름으로 부르지 않는다.
 */
export const SHEET_TITLE: Record<string, readonly [string, string]> = {
  play: ['마당', '맞히기 · 달리기'],
  notify: ['알림', '기다리는 일'],
};

/** 타일 아이콘 — 선 몇 개로 그린다(에셋 없이 색만 갈아입도록). `$BG` 자리에 타일 배경색이 들어간다. */
export const LINE: Record<RoomKey, readonly string[]> = {
  table: [
    'left:2px;top:13px;width:22px;height:10px;border:2px solid currentColor;border-top:none;border-radius:0 0 12px 12px',
    'left:7px;top:7px;width:12px;height:7px;border-radius:7px 7px 0 0;background:currentColor',
  ],
  bath: [
    'left:5px;top:2px;width:15px;height:15px;border:2px solid currentColor;border-radius:50% 50% 50% 3px;transform:rotate(45deg)',
    'left:3px;top:21px;width:20px;height:2px;border-radius:2px;background:currentColor',
  ],
  play: [
    'left:3px;top:3px;width:20px;height:20px;border:2px solid currentColor;border-radius:50%',
    'left:12px;top:3px;width:2px;height:20px;background:currentColor;transform:rotate(35deg)',
  ],
  bed: [
    'left:2px;top:12px;width:22px;height:10px;border:2px solid currentColor;border-radius:4px',
    'left:4px;top:6px;width:9px;height:6px;border-radius:2px;background:currentColor',
  ],
  album: [
    'left:4px;top:2px;width:18px;height:22px;border:2px solid currentColor;border-radius:3px',
    'left:7px;top:17px;width:12px;height:2px;background:currentColor',
    'left:9px;top:7px;width:6px;height:6px;border-radius:50%;background:currentColor',
  ],
};

/** 팝오버 첫 줄 — 아이가 제 상태를 말한다. */
export const SAY = {
  table: { ok: '배가 안 고파요', soon: '슬슬 배가 고파요', now: '지금 배가 고파요' },
  bath: { ok: '깨끗해요', soon: '슬슬 지저분해요', now: '지금 씻고 싶어요' },
  play: { ok: '기분이 좋아요', soon: '슬슬 심심해요', now: '지금 놀고 싶어요' },
} as const;

/**
 * 방 꾸미기 · 벽지 네 종. 시안이 CSS 색으로 확정했다.
 * ★ 방 배경 그림(무대에 깔 실물)은 화면 크기를 확정한 뒤 그 비율로 새로 만든다(상훈님 9/7 결정).
 *   그때 여기에 `img` 를 더해 색 대신 그림을 깐다.
 */
export const WALLS = [
  { id: 'cream', name: '크림', wall: C.bornBg, floor: '#EFDFCC' },
  { id: 'mint', name: '민트', wall: '#E5F1EA', floor: '#D5E5DA' },
  { id: 'sky', name: '하늘', wall: '#E7EFF8', floor: '#D8E3EF' },
  { id: 'peach', name: '복숭아', wall: '#FBE7E2', floor: '#F0D5CE' },
] as const;

/** 성격 다섯 갈래 → 캐릭터 칸의 칩. */
export const PERSONA = ['온순', '활발', '수줍음', '응석', '시크'] as const;
/**
 * 성격 칩 → 서버 값. 우리 칩 다섯과 서버 `Personality` 다섯이 하나씩 맞는다.
 * 표에 없는 값(안 골랐을 때)은 undefined 로 떨어져 아예 안 보낸다 — 만들 때는 선택이다.
 * ★ 온보딩과 아이 정보 시트가 **같은 표**를 써야 한다. 두 벌이면 한쪽만 고쳐져도 안 보인다.
 */
export const PERSONALITY_OF: Record<string, PersonalityValue | undefined> = {
  온순: 'GENTLE', 활발: 'LIVELY', 수줍음: 'SHY', 응석: 'CLINGY', 시크: 'COOL',
};
/** 거꾸로 — 서버가 준 성격을 우리 칩 이름으로. 아이 정보 시트가 지금 값을 골라 보이려고 쓴다. */
export const PERSONA_LABEL: Record<string, string> = {
  GENTLE: '온순', LIVELY: '활발', SHY: '수줍음', CLINGY: '응석', COOL: '시크',
};
export const TONE = ['반말', '존댓말', '사투리', '어린아이', '어른스러움', '무뚝뚝', '애교'] as const;
export const GENRE = ['일상', '판타지', 'SF', '학원', '역사', '로맨스', '무협'] as const;
export const WORLD = ['현대', '중세', '미래', '자연', '도시', '우주', '학교'] as const;
export const NAME_POOL = ['여울', '보리', '단이', '모래', '노을', '서리', '하루', '도담'] as const;

/**
 * 캐릭터 칸 네 묶음 — 칩 한 줄 + 긴 글 한 줄(9/6 상훈님 2차 결정).
 * ★ 네 묶음 모두 **여러 개를 고를 수 있다**(상훈님 2026-09-11). 다만 성격은 서버가 하나만 받아
 *   맨 앞(처음 고른 것)만 저장된다 — 화면 규칙이 아니라 서버 계약이라 여기 적어 둔다.
 */
export const CHAR_GROUPS = [
  { key: 'persona', label: '성격 · 여럿 고를 수 있어요', opts: PERSONA, ph: '자세히 쓰셔도 돼요. 예: 낯을 가리지만 한번 친해지면 계속 따라다녀요' },
  { key: 'tone', label: '말투', opts: TONE, ph: '입버릇이나 자주 쓰는 말이 있으면 적어 주세요' },
  { key: 'genre', label: '장르', opts: GENRE, ph: '어떤 이야기 속 아이인지 적어 주세요' },
  { key: 'world', label: '세계관', opts: WORLD, ph: '사는 곳, 시대, 함께 있는 사람들 같은 걸 적어 주세요' },
] as const;

// 업로드 안내의 예시 그림. 튜플은 [설명(라벨), 폴백 색, 이미지 키] 다.
//   · 이미지 키는 서버 키 규칙(앞머리 images/ 없이)으로 적는다 — 화면은 assetUrl() 로 CDN 주소를 만든다.
//   · 그림은 Codex(무과금)로 그린 가상의 샘플 캐릭터(실존/기존 IP 아님)를 S3
//     images/zzal/onboarding/ 에 올린 것이다. 로드 실패 시 색+'그림' 자리표시자로 폴백한다.
export const GOOD_EX: ReadonlyArray<readonly [string, string, string]> = [
  ['얼굴이 크게 나온 정면', '#E8F0E2', 'zzal/onboarding/v2/good_front.webp'],
  ['전신이 다 보이는 그림', '#E8F0E2', 'zzal/onboarding/v2/good_lines.webp'],
  // ★ '한 마리만' 을 뺐다(2026-09-21 판정 7) — 나쁜 예의 「여러 명」이 이미 같은 말을 하고 있어
  //   좋은 예까지 반복하면 "혼자여야 한다" 가 두 번 잔소리가 된다. 나쁜 예 쪽은 그대로 둔다.
  ['배경 없이', '#E8F0E2', 'zzal/onboarding/v2/good_solo.webp'],
];
export const BAD_EX: ReadonlyArray<readonly [string, string, string]> = [
  ['여러 명', '#F6E7E4', 'zzal/onboarding/v2/bad_multi.webp'],
  ['뒷모습', '#F6E7E4', 'zzal/onboarding/v2/bad_back.webp'],
  ['너무 작음', '#F6E7E4', 'zzal/onboarding/v2/bad_small.webp'],
  ['배경이 복잡', '#F6E7E4', 'zzal/onboarding/v2/bad_busy.webp'],
];

/**
 * 유저 설문 여섯 문항. **온보딩 칸이 아니다** — 시안이 이걸 샘플 방으로 옮겼다.
 * 여울(연습 상대)이 방에서 한 문항씩 물어보고, 언제든 "나중에" 로 넘길 수 있다.
 *
 * ★ label 은 항목 이름이 아니라 **여울이 하는 말 그대로**다(2026-09-07 상훈님 지시).
 *   '연령대' 처럼 적으면 설문지로 읽힌다 — 말을 거는 것으로 읽혀야 해서 물음표까지 넣어 둔다.
 *   그래서 화면은 이 값을 **가공하지 말고 그대로** 써야 한다.
 * ★ `age`·`from` 은 상훈님이 타겟 유저를 가늠하는 데 쓰는 항목이라 빼지 않는다. 말투만 바꿨다.
 */
export interface UserQOpt {
  text: string;
  /** 칩 둘째 줄에 작게 붙는 말(예: 시각 범위). 없으면 한 줄짜리 칩이다. */
  note?: string;
}
export interface UserQ {
  key: string;
  /** 여울이 하는 말 그대로. 화면은 가공하지 않는다. */
  label: string;
  opts: readonly UserQOpt[];
  /** 고르는 것 말고 **직접 적을** 수도 있는 문항이면 채운다. */
  input?: { ph: string; max: number };
}

export const USER_Q: readonly UserQ[] = [
  {
    key: 'nick', label: '제가 뭐라고 불러드리면 좋을까요?',
    opts: [{ text: '이름 없이' }, { text: '언니/오빠' }, { text: '엄마/아빠' }, { text: '친구' }],
    // 넷 중에 없을 수 있다. 캐릭터 칸과 같은 '칩 한 줄 + 입력 한 줄'(9/6 결정)을 여기도 쓴다.
    input: { ph: '직접 적어 주셔도 돼요', max: 12 },
  },
  {
    // ★ 시각 구간은 게임 시계와 어긋나면 안 된다 — 재우기 저녁 7시, 깨우기 아침 7~10시.
    key: 'when', label: '주로 언제 만나러 오실 것 같아요?',
    opts: [
      { text: '아침', note: '6~11시' }, { text: '낮', note: '11~17시' },
      { text: '저녁', note: '17~22시' }, { text: '밤', note: '22시 이후' },
    ],
  },
  {
    // '친구 자캐예요' 는 남긴다 — 남의 자캐인지가 우리가 다루는 방식을 가르는 구분이라
    // (자캐 커뮤니티에서 민감한 지점) 지우면 나중에 알 길이 없다.
    key: 'whose', label: '이 아이는 어떤 사이예요?',
    opts: [{ text: '제 자캐예요' }, { text: '친구 자캐예요' }, { text: '최애캐예요' }, { text: '별 사이 아니에요' }],
  },
  { key: 'draw', label: '그림은 직접 그리시나요?', opts: [{ text: '그려요' }, { text: '보는 걸 좋아해요' }, { text: '둘 다요' }] },
  { key: 'from', label: '저희는 어떻게 알고 오셨어요?', opts: [{ text: 'X(트위터)' }, { text: '인스타' }, { text: '유튜브' }, { text: '친구 소개' }, { text: '검색' }] },
  { key: 'age', label: '나이대를 여쭤봐도 될까요?', opts: [{ text: '10대' }, { text: '20대' }, { text: '30대' }, { text: '40대 이상' }] },
];

/** 온보딩 네 칸. 가입은 칸이 아니라 랜딩 CTA 에서 뜨는 모달이다(상훈님 9/7 결정). */
export const STEPS = ['landing', 'upload', 'char', 'born'] as const;
export type StepKey = (typeof STEPS)[number] | 'user';

/** 화면 큰 갈래. onb → (샘플)room → egg → room. */
export type ScreenKey = 'onb' | 'room' | 'egg';

/**
 * 랜딩 v2 카피 — **한 벌**이다. `/zzal` 온보딩 첫 칸(LandingV2Stage)과 `/zzal/landing` 이
 * 같은 이 상수를 읽는다. 복제하면 나중에 한쪽만 고쳐진다.
 *
 * ★ "같이 산다·살아난다·부활·재현" 류 무거운 말은 쓰지 않는다(언캐니 규칙). 옛 카피
 *   "그림 한 장이면, 같이 살 수 있어요" 를 2026-09-18 상훈님 확정 문구로 갈아치웠다.
 */
export const LANDING_COPY = {
  greeting: '안녕! 같이 키우자!',
  sub: '그림 한 장이면, 내가 그린 아이랑 같이 지낼 수 있어요.',
  cta: '같이 키우러 가기',
} as const;

/**
 * 그림 올리기 칸의 문구. **두 갈래가 한 상수에 모여 있다**(2026-09-21 통합).
 *
 * 1) 그림은 골랐는데 아직 안 올린 한 박자(2026-09-19) — 가입 창이 뜨는 자리가 여기다.
 *    그동안 화면이 말해야 하는 것은 하나뿐이다 — "고른 그림은 그대로 있다".
 *    다시 고르라고 읽히면 사람은 창을 닫고 처음부터 다시 한다.
 *
 * 2) ★★ `infraNote` — 연결이 끊기거나 S3 가 거절하면(CORS·403·5xx)
 *    "이미지를 올리지 못했습니다" 한 줄 **바로 아래에 「이런 그림은 어려워요」 예시가 붙어 있어서**,
 *    사용자가 제 그림 탓으로 읽었다. 인프라 실패일 때는 예시를 아예 안 그리고(→ `Onboarding.tsx`),
 *    대신 "그림 때문이 아니다" 를 명시한다. 그림이 정말 거절당한 경우(`ZZAL_PET_HATCH_FAILED`)
 *    에만 예시가 도움이 되므로 그때는 그대로 둔다.
 */
export const UPLOAD_COPY = {
  /** 미리보기 아래 한 줄(원래 '그림 올리기' 자리). */
  pending: '가입하면 이 그림으로 시작해요',
  /** 그 아래 작은 줄. 고른 그림이 어디 가지 않는다는 것만 말한다. */
  pendingNote: '고른 그림은 그대로 두었어요',
  /** 아래 버튼. 누르면 가입 창이 다시 열린다. */
  pendingCta: '가입하고 올리기',
  goodTitle: '이런 그림이면 좋아요',
  badTitle: '이런 그림은 어려워요',
  privacy: '올린 그림은 학습에 쓰지 않아요. 이 아이를 만드는 데만 써요.',
  infraNote: '그림 때문이 아니에요. 연결이 잠깐 끊겼거나 서버가 응답하지 않았어요. 같은 그림으로 다시 눌러 주세요.',
} as const;

export const ONB_COPY: Record<StepKey, readonly [string, string]> = {
  landing: [LANDING_COPY.greeting, LANDING_COPY.sub],
  upload: ['캐릭터 이미지를 업로드해 주세요', '한 장만 올릴 수 있어요. 얼굴이 잘 보이는 그림일수록 좋아요.'],
  user: ['당신은 어떤 분인가요', '알려주면 아이가 더 살갑게 대해요. 전부 선택이에요.'],
  char: ['어떤 아이인가요', '이름만 정하면 시작할 수 있어요.'],
  born: ['태어났어요', '이제 이 아이의 첫날이에요.'],
};

/** 튜토리얼 · 샘플 방(여울이 연습을 시킨다). */
export interface TutorStep { at: string; room: string | null; act: 'a' | 'pet' | null; done: string; text: string }
export const TUTOR: readonly TutorStep[] = [
  { at: '0분', room: null, act: null, done: 'any', text: '안녕하세요, 저는 여울이에요. 당신의 아이가 나올 동안 여기서 연습해요.' },
  { at: '3분', room: null, act: 'pet', done: 'pet', text: '제 몸을 톡 눌러 보세요. 언제든 다시 해도 돼요.' },
  { at: '8분', room: 'table', act: 'a', done: 'feed', text: '슬슬 배가 고파요. 주방에서 밥을 주면 배부름이 차요.' },
  { at: '15분', room: 'bath', act: 'a', done: 'clean', text: '바닥에 흔적이 생겼어요. 욕실에서 치워 주면 말끔해져요.' },
  { at: '20분', room: 'play', act: 'a', done: 'game', text: '심심해요. 마당에서 좌우 맞히기를 하면 기분이 올라가요.' },
  { at: '25분', room: 'chat', act: null, done: 'chat', text: '오른쪽 아래 말풍선을 누르면 저랑 얘기할 수 있어요.' },
  { at: '40분', room: 'bed', act: 'a', done: 'sleep', text: '저녁이 되면 침실에서 재워 주세요. 자는 동안 다 회복돼요.' },
  { at: '60분', room: 'album', act: 'a', done: 'album', text: '같이한 동작은 앨범에 쌓여요. 방 벽을 열어 보세요.' },
];

/** 튜토리얼 · 진짜 방(내 아이의 첫날). 샘플과 달리 **직접 해야** 다음으로 간다. */
export const TUTOR_MAIN: readonly TutorStep[] = [
  { at: '0분', room: null, act: null, done: 'any', text: '오늘부터 함께예요. 천천히 둘러봐도 돼요.' },
  { at: '3분', room: null, act: 'pet', done: 'pet', text: '손을 대 보세요. 쓰다듬기는 하루 세 번까지 세어 줘요.' },
  { at: '8분', room: 'table', act: 'a', done: 'feed', text: '배가 고파요. 주방에서 밥을 주세요 · 재고는 시간이 지나면 채워져요.' },
  { at: '15분', room: 'bath', act: 'a', done: 'clean', text: '바닥에 흔적이 생겼어요. 욕실에서 치워 주세요. 한 번 쓸면 하나예요.' },
  { at: '20분', room: 'play', act: 'a', done: 'game', text: '놀고 싶어요. 시작하면 오늘 세 판 중 하나예요. 중간에 나가면 그 판은 져요.' },
  { at: '25분', room: 'chat', act: null, done: 'chat', text: '하루에 세 번 불러요. 말풍선을 누르면 답할 수 있어요.' },
  { at: '40분', room: 'bed', act: 'a', done: 'sleep', text: '저녁 7시가 되면 침실에서 재워 주세요. 자는 동안 다 회복돼요.' },
  // ★ 마지막 칸이 **남은 규칙 셋**을 받는다(2026-09-21 판정 — 8칸짜리 목에도 넣는다).
  //   간식·목욕·약은 튜토리얼에서 한 번도 안 눌리는데 셋 다 거절이 붙어, 안 말해 주면
  //   **처음 만나는 순간이 곧 첫 실패**가 된다(간식은 거절도 아니고 아이가 아파진다).
  { at: '60분', room: 'album', act: 'a', done: 'album', text: '함께한 순간은 앨범 벽에 쌓여요. 열어 보세요. 간식은 하루 네 개까지예요 — 다섯 개째는 배탈이 나요. 목욕은 하루 한 번, 약은 아플 때만 줄 수 있어요.' },
];

/** 앨범 18칸. `이름 · 조건` 형식이고 두 번째 값이 1 이면 이미 열린 칸이다. */
export const ALBUM: ReadonlyArray<readonly [string, number]> = [
  ['첫 만남', 1], ['첫 밥', 1], ['첫 목욕', 1], ['늦은 밤', 1], ['창가', 1], ['장난감', 1], ['낮잠', 1], ['첫 대화', 1],
  ['비 오는 날 · 비 올 때 함께', 0], ['달리기 · 2층 해금', 0], ['생일 · 30일째', 0], ['두 번째 층 · 2층 해금', 0],
  ['아침 인사 · 07시 깨우기', 0], ['간식 파티 · 간식 10개', 0], ['단짝 · 친밀도 60%', 0], ['기억 상자 · 기억 10개', 0],
  ['먼 여행 · 3층 해금', 0], ['졸업 · 60일째', 0],
];

/**
 * 앨범 벽에 걸리는 동작 여덟 종. **카탈로그 key** 로 적는다(서버 `Motion.key` 와 같은 이름).
 * 여울 실물이 여덟 장뿐이라 그만큼만 돌린다.
 */
export const FRAME_KEYS = ['joy', 'eat', 'wash', 'base', 'sad', 'hello', 'pet', 'sick'] as const;
export type FrameKey = (typeof FRAME_KEYS)[number];

/**
 * 기본 8종. **방에 들어왔다는 것은 이 여덟이 다 만들어졌다는 뜻**이다 —
 * 부화가 끝나야 '태어났어요' 가 뜨고, 그걸 눌러야 방에 들어온다.
 * 그래서 진짜 방에서 이 중 하나라도 없으면 그건 폴백할 일이 아니라 **고장**이다.
 */
// ★ 2026-09-13 — **v4 이름으로 갈아 끼웠다.** 서버가 이미 이 여덟을 주는 것을 실측으로 확인했다
//   (`GET /me/pets/{id}` 의 `motions[]` seq 1~8 = base·eat·joy·sad·sick·pet·hello·sleep).
//   전에 옛 이름(`practice`·`shy`·`call`)을 들고 있었던 탓에 `missingBasics` 가 서버에 아예 없는
//   `practice` 를 **늘 '그림 없음' 으로 잡아** 개발 화면에 상시 거짓 경고를 띄웠다.
//   ⚠️ 옛 `practice`(훈련)가 빠지고 `sleep`(잠)이 1층으로 올라온 것이 **칸이 바뀐 유일한 자리**다.
export const BASIC_KEYS = ['base', 'eat', 'joy', 'sad', 'sick', 'pet', 'hello', 'sleep'] as const;

/**
 * 발밑 투명 여백. 그림 한 장은 313 × 350 인데 배경을 지우고 나면 **아래 54px 이 빈칸**이라,
 * 칸을 바닥선에 맞추면 아이가 그만큼 떠 보인다(2026-09-07 상훈님 지적).
 *
 * 재는 법 — 여덟 종의 알파 경계를 브라우저 캔버스로 훑고, 발끝 선을 그어 눈으로 확인했다.
 * 여덟 종이 전부 같은 선이었다(idle·eat·happy·sad·hungry·train·pet·clean).
 * ★ 그림을 새로 뽑으면 이 값도 다시 재야 한다 — 여백이 달라지면 아이가 뜨거나 잠긴다.
 */
export const SPRITE_FOOT_PAD = 54 / 350;

/**
 * 팝오버가 무대 아래를 덮는 높이(px). 캐릭터·그림자는 **항상** 이만큼 위에 선다.
 *
 * ★ 왜 재지 않고 상수인가 — 팝오버는 글자 크기·여백이 전부 고정이고 아래 영역 안
 *   `bottom:116px` 에 놓이므로 **화면 크기와 무관하게 같은 높이**다. 실제로 세 크기에서
 *   한 픽셀도 다르지 않았다(390×844 · 360×640 · 1280×800, 2026-09-07).
 *   런타임 실측을 쓰면 팝오버를 한 번도 안 연 **여울 샘플 첫 진입에서 캐릭터가 낮게 서고**,
 *   거기서 팝오버를 열면 그제야 올라갔다. 상훈님이 보신 게 그 어긋남이다.
 *
 * ★ 잰 값(무대 아래끝 − 팝오버 윗변) — 2026-09-07, 세 화면 크기에서 동일
 *     진짜 방 : 주방 200 · 욕실 200 · 마당 200 · 침실 125 · 앨범 182
 *     샘플 방 : 주방 204 · 욕실 204 · 마당 204 · 침실 129 · 앨범 186
 *   **가장 큰 204** 를 쓴다. 이보다 낮게 잡으면 어느 방에선가 팝오버가 발을 덮는다.
 *
 * ★ 같은 주방인데 200/204 로 갈리는 이유 — 팝오버 높이는 186px 로 **늘 같고**, 튜토리얼이
 *   가리키는 타일에 2.5px 테두리가 생기면서 아래 영역이 커져 무대가 4px 줄어든 것이다.
 *   그러니 가장 큰 값은 **아무 타일도 강조되지 않은 때**에 나온다.
 *
 * ★ 팝오버 안의 '여기서 ○○ 누르기' 안내 줄을 없애기 전에는(2026-09-07) 튜토리얼 대상 부름에서
 *   그 줄만큼 팝오버가 더 높아져 이 값을 넘겼다 — 그때는 캐릭터가 덮일 수 있었다. 지금은 없다.
 *
 * ⚠️ 팝오버(또는 그 안의 줄·여백)가 바뀌면 이 값이 조용히 낡는다 — 캐릭터가 가려지거나 붕 뜬다.
 *   다시 재는 법: 두 모드에서 방을 하나씩 열고 `무대.getBoundingClientRect().bottom − 팝오버.top`
 *   의 최댓값. 튜토리얼이 가리키는 부름에서도 한 번씩 열어 볼 것.
 */
export const POP_LIFT = 204;

export const EGG_IMG = {
  idle: assetUrl('egg_idle'), hatch: assetUrl('egg_hatch'), crack: assetUrl('egg_crack'),
} as const;

/**
 * 좌우 맞히기의 손 그림 — 주먹 두 장, 펼친 손 네 장.
 *
 * ★ **`props/catalog.ts` 에 등록하지 않는다.** 소품 렌더러(`PropLayer`)는 무대 안에만 마운트되는데
 *   이 그림들은 무대가 아니라 **시트 안 버튼**에 붙는다 — 등록해도 그려질 자리가 없다. 게다가 그
 *   파일은 바깥 규격 JSON 을 옮겨 적는 자리라 다음 갱신 때 손으로 적은 줄이 지워진다.
 *   그래서 화면이 직접 드는 상수로 여기 둔다.
 *
 * ★ 왼쪽·오른쪽은 **화면 기준**이다(아이 기준이 아니다). 그림 파일 이름의 `l`·`r` 이 곧 화면의 좌·우다.
 *
 * ★ 여섯 장이 전부 512×512 이고 **손목이 캔버스 바닥에 붙어 있다.** 그래서 같은 정사각 칸에
 *   `object-fit: contain` 으로 넣으면 주먹↔펼침을 갈아 끼워도 손목이 한 픽셀도 안 움직인다.
 *   칸을 정사각이 아닌 모양으로 바꾸면 이 약속이 깨진다 — 손이 위아래로 튄다.
 */
/**
 * ★ 주소는 **`propUrl`** 로 만든다 — `assetUrl` 이 아니다.
 *   예전에는 `assetUrl('guess_l_fist.v1')` 이라 `<CDN>/guess_l_fist.v1` 이 됐다. `zzal/assets/` 도
 *   `.webp` 도 빠져 **쓰는 순간 404** 인 값이었고, 그래서 화면은 이 표를 안 쓰고 `Room.tsx` 의
 *   `handSrc()` 가 `propUrl()` 로 주소를 따로 만들었다. 이제 **같은 함수로 같은 주소**를 만드므로
 *   미리 받기(`preloadGuessHands`)가 화면이 실제로 요청할 그 주소를 그대로 데운다.
 *   ⚠️ 두 곳이 어긋나면 미리 받기는 조용히 헛돈다(딴 주소를 받아 두고 캐시가 안 맞는다).
 */
export const GUESS_HANDS = {
  LEFT: {
    fist: propUrl('guess_l_fist', 1),
    open_empty: propUrl('guess_l_open_empty', 1),
    open_candy: propUrl('guess_l_open_candy', 1),
  },
  RIGHT: {
    fist: propUrl('guess_r_fist', 1),
    open_empty: propUrl('guess_r_open_empty', 1),
    open_candy: propUrl('guess_r_open_candy', 1),
  },
} as const;

/**
 * **미리 받아 둘 네 장** — 펼친 손(빈손·사탕) 좌·우.
 *
 * ★ 왜 주먹은 뺐나 — 주먹은 판이 뜨는 그 순간 이미 화면에 그려져 있어 저절로 받아진다.
 *   늦는 것은 **탭한 뒤 갈아 끼우는 펼친 손**이다(1층 실측: 주소 교체 → 그려짐 94ms).
 * ★ 왜 미리 받아야 하나 — `<img>` 한 장의 `src` 를 갈아 끼우는 구조라, 새 그림이 도착할 때까지
 *   브라우저가 **주먹을 계속 그린다.** 그게 "손이 늦게 펴진다" 의 정체다.
 *   로컬은 −94ms 지만 staging·운영은 그림이 원격 CDN 이라 **첫 공개마다 왕복 한 번**이 통째로 얹힌다.
 */
export const GUESS_HAND_PRELOAD: readonly string[] = [
  GUESS_HANDS.LEFT.open_empty, GUESS_HANDS.LEFT.open_candy,
  GUESS_HANDS.RIGHT.open_empty, GUESS_HANDS.RIGHT.open_candy,
];

/** 손 그림 한 칸의 한 변(px). 정사각이어야 손목이 안 튄다(위 주석). */
export const GUESS_HAND_PX = 64;

/** 손 그림 세 갈래. 화면이 이 순서대로 세 장을 겹쳐 두고 한 장만 보여 준다(갈아 끼울 때 안 깜빡이게). */
export const GUESS_HAND_KINDS = ['fist', 'open_empty', 'open_candy'] as const;
export type GuessHandKind = (typeof GUESS_HAND_KINDS)[number];

/**
 * 진 판에 뜨는 점 세 개. 256×256 이고 잉크는 가로로 넓은 띠라(실측 228×61, 캔버스 정중앙)
 * 정사각 칸에 넣으면 위아래가 비어 보인다 — 그게 정상이다.
 */
export const GUESS_LOSE_DOTS = assetUrl('lose_dots.v1');
export const GUESS_LOSE_DOTS_PX = 40;

/** 버튼 표기 세 가지 — 색맹·저시력 대비로 색 말고 모양·글자를 함께 낼 수 있게(정본 §16). */
export const NEED_STYLES = ['색+모양+글자', '색+글자', '색+모양'] as const;
export type NeedStyle = (typeof NEED_STYLES)[number];

/** 대화 입력창이 2.6초마다 바꿔 보여 주는 예시. */
export const CHAT_HINTS = ['잘 지냈어', '조금 피곤해', '보고 싶었어', '오늘 빵 만들었어'] as const;
export const CHAT_QUICK = ['잘 지냈어', '빵 만들었어', '조금 피곤해'] as const;
export const CHAT_REPLY = ['그 얘기 기억해 둘게요.', '오늘도 들려줘서 좋아요.', '나도 그런 날이 있어요.', '음, 그랬구나.'] as const;

/** 다음에 배울 동작 — 왼쪽 아래 작은 카드가 이 표를 보고 하나를 고른다. */
/**
 * ★ **서버가 세는 튜토리얼 9칸**(2026-09-10). 위 `TUTOR_MAIN` 8부름을 대신한다.
 *
 * 순서·개수는 서버 `TutorialStepKey` 와 **한 칸도 어긋나면 안 된다** — 서버가 준 `tutorial.step` 이
 * 곧 이 배열의 첨자다. 어긋나면 엉뚱한 방을 가리키고, 아무 소리도 안 난다.
 * 문구는 `tamagotchi/tutorial.ts` 의 `BABY_CALLS`(정본 §12) 를 이 화면 말로 편 것이다.
 *
 * `at` 은 이제 뜻이 없다(시각 개념이 사라졌다). 자리만 지키고 칸 번호를 적어 둔다.
 * `done` 도 서버가 판정하므로 여기서는 안 쓴다 — 키를 그대로 넣어 대조만 되게 둔다.
 *
 * `room` 이 가리키는 곳:
 *   table·bath·play·bed·album = 아래 타일 / chat = 오른쪽 아래 말풍선 / info = 머리줄의 '아이 정보'
 */
export const TUTOR_SERVER: readonly TutorStep[] = [
  { at: '1칸', room: 'table', act: 'a', done: 'FEED', text: '배가 고픈가 봐요. 주방에서 밥을 주세요.' },
  { at: '2칸', room: null, act: 'pet', done: 'PET', text: '쓰다듬어 주세요. 아이를 톡 누르면 돼요.' },
  { at: '3칸', room: 'chat', act: null, done: 'CHAT', text: '뭐라고 말을 거네요. 오른쪽 아래 말풍선을 눌러 답해 주세요.' },
  { at: '4칸', room: 'info', act: null, done: 'PERSONALITY', text: '어떤 아이인가요. 아이 정보에서 성격을 골라 주세요.' },
  { at: '5칸', room: 'bath', act: 'a', done: 'CLEAN', text: '바닥에 흔적이 생겼어요. 욕실에서 치워 주세요. 한 번 쓸면 하나예요.' },
  { at: '6칸', room: 'play', act: 'a', done: 'GAME', text: '같이 놀아 볼까요. 마당에서 좌우 맞히기를 한 판 시작해 주세요. 시작하면 오늘 세 판 중 하나예요. 중간에 나가면 그 판은 져요.' },
  { at: '7칸', room: 'album', act: 'a', done: 'SHARE', text: '이 모습 가져가실래요. 앨범 벽에서 액자를 열어 공유해 보세요.' },
  { at: '8칸', room: 'bed', act: 'a', done: 'NAP', text: '졸린가 봐요. 침실에서 재우고, 다시 깨워 주세요.' },
  { at: '9칸', room: null, act: null, done: 'DONE', text: '이제 혼자서도 괜찮아요. 여기부터는 시간이 흐르기 시작해요. 저녁 7시가 되면 재워 주세요. 간식은 하루 네 개까지예요 — 다섯 개째는 배탈이 나요. 목욕은 하루 한 번, 약은 아플 때만 줄 수 있어요.' },
];

export const LEARN_GOALS = [
  { name: '손 흔들며 인사', cond: '대화 답하기', need: 4, counter: 'cChat' },
  { name: '씻기', cond: '목욕하기', need: 3, counter: 'cBath' },
  { name: '자기', cond: '재우기', need: 3, counter: 'cSleep' },
  { name: '놀라기', cond: '좌우 맞히기', need: 3, counter: 'cGame' },
] as const;

/**
 * 조각 4칸(정본 §"조각 4개(3층부터, 잠들 때 판정·리셋)").
 * 3층이 시작되면 좌측 하단 카드가 로드맵에서 이 도장으로 넘어간다.
 * ★ 이름·조건은 정본 그대로다. 바꾸려면 정본을 먼저 고칠 것.
 */
export const SHARDS = [
  { key: 'food', label: '밥', cond: '밥 2회' },
  { key: 'play', label: '놀이', cond: '간식 1회 또는 게임 1승' },
  { key: 'clean', label: '청결', cond: '청소 1회 또는 목욕 1회' },
  { key: 'bond', label: '교감', cond: '채팅 1회 또는 쓰다듬기 2회' },
] as const;

/** 아침에 도착하는 엽서 세 벌. */
export const POSTCARDS: ReadonlyArray<readonly [string, string]> = [
  ['오늘 아침, 창가에 앉아 있었어요.', '#F6E7DF'],
  ['밥을 다 먹고 한참 서 있었어요.', '#E3EFDC'],
  ['문 앞에서 당신을 기다렸어요.', '#DEEAF3'],
];

/** 웹에서만 보이는 단축키 안내. */
export const WEB_KEYS: ReadonlyArray<readonly [string, string]> = [
  ['1~5', '방'], ['Space', '쓰다듬기'], ['Enter', '대화'], ['Esc', '닫기'],
];

/**
 * 튜토리얼 완주 축하 판(첫날 선물 화면)의 문구.
 *
 * ★★ 2026-09-20 — **화면이 사실과 다른 말을 하던 자리**라 여기로 끌어냈다. 고친 것 셋:
 *   1) "앨범에서 바로 볼 수 있어요" → 거짓이었다. 구르기는 이 순간 **굽기가 시작될 뿐**이고
 *      (`night/BakeTrigger.onTutorialDone`), 그림은 검수를 지나 깨어 있는 첫 정산에서야 도착한다
 *      (`ZzalMotion.reveal` · `AdvancedMotion.imageKey` 는 도착 뒤에만 채워진다). 즉 이 판을
 *      보는 순간 앨범에는 아무것도 없다.
 *   2) "오늘 밤에는 새 동작을 하나 연습해 볼 참" → 밤에 굽는 모델이 아니고(1.8·1.9 에서 조건을
 *      채운 그 순간 굽기로 바뀌었다), 3층 심화 목록(`app.zzal.advanced-motions`)도 비어 있어
 *      **내일 새 모습이 생기지 않는다.** 약속을 지우고 결만 남긴다.
 *   3) "구르기 저장하기" 버튼 → 누르면 서버 호출이 한 건도 없는데 "앨범에 저장했어요" 라고 했다.
 *      저장할 그림 자체가 아직 없으므로 버튼을 없앴다(→ 도착한 뒤 앨범 액자에서 저장한다).
 *
 * ★ 말투 규칙(자캐 커뮤니티 규범) — "꼭 오세요"·"기다릴게요"·"안 오면 서운해요" 처럼 **아이가
 *   사용자를 재촉하거나 원망하는 결은 쓰지 않는다.** 지킬 수 없는 약속(날짜·시각)도 안 쓴다.
 *
 * ★★ **아직 안 붙인 것 둘** — 자리는 이 판 안에 잡아 두었고, 재료가 오면 갈아 끼운다.
 *   1) ~~구르기 미리보기~~ → **붙였다**(`GRAD_PREVIEW_SRC`). 말로만 하던 "구르기" 를 실제로 보여준다.
 *      ⚠️ `tamagotchi/constants.ts` 의 `YEOUL_MOTION.roll` 은 **여전히 기쁨 자세로 버티는 중**이고
 *      그건 그대로 둔다 — 그쪽은 도감·방이 쓰는 자리라 별개 판단이다.
 *   2) **"이런 동작도 보고 싶어요" 자유 입력** — 받아 줄 서버 칸이 아직 없다. 행동 기록 수집기
 *      (`POST /api/v1/events`)는 사람이 쓴 글을 **일부러 안 받아** 조용히 버린다. 전용 API 가
 *      열리면 지금의 한 번 누르는 버튼을 입력칸으로 바꾼다.
 */
export const GRAD_COPY = {
  title: '첫날을 함께 마쳤어요',
  /**
   * ★★ **"~쯤" 과 "~을 거예요" 를 지우지 말 것.** 이 두 마디가 이 문장을 참으로 만든다.
   *
   *   구르기는 굽고 나서 **검수를 통과해야** 앨범에 들어오고, 그 검수가 언제 끝나는지는
   *   화면도 서버도 미리 모른다. "내일 열려 있어요" 처럼 단정하는 순간 지킬 수 없는 약속이 되고,
   *   늦어지는 사람에게는 그 문장이 그대로 거짓말이 된다.
   *   여지를 남긴 채로 기대를 주는 것이 이 문구가 하려는 일이다.
   *
   * @param name 아이 이름(빈 값이면 '아이').
   */
  body: (name: string) => `${name}도 구르기를 하나 그리는 중이에요.\n다음에 오실 때쯤 앨범에서 만날 수 있을 거예요.`,
  /** 정본 §심화 행동이 선물 화면에 붙이라고 한 수요조사. **이 한 줄은 진짜로 서버에 남는다.** */
  wish: '이런 동작도 보고 싶어요',
  close: '닫기',
  /**
   * 미리보기에 붙는 두 마디. **그림보다 먼저 읽혀야 하는 말**이다.
   *
   * ★★ 여기 뜨는 것은 **여울(연습 상대)의 구르기**이지 사용자의 아이가 아니다. 사용자는 이 자리에서
   *   제 아이를 기대하므로, 표시가 약하면 **"내 아이가 여울로 바뀌었나"** 로 읽힌다. 자캐 커뮤니티에서
   *   다른 캐릭터로의 치환은 캐붕의 시각적 형태라 가장 민감한 지점이다.
   * ★ 그래서 세 겹으로 말한다 — **글자**(이 칩과 캡션) · **모양**(방 액자의 나무 테두리와 다른 점선 틀)
   *   · **말**(본문이 "{이름}도 …" 로 둘을 나란히 놓는다). 색 하나로 가르지 않는다.
   */
  previewBadge: '예시',
  previewCaption: '여울이 먼저 보여주는 구르기예요',
} as const;

/**
 * 졸업 판 미리보기 그림의 주소. **비어 있으면 미리보기를 아예 안 그린다.**
 *
 * ★★ 왜 상수 한 줄인가 — 그림이 정식 주소로 게시되는 시점과 이 화면을 만드는 시점이 다르다.
 *   값이 비어 있는 동안에는 자리 자체가 없어서 **레이아웃이 흔들리지 않고**, 주소가 정해지면
 *   이 한 줄만 바꾸면 판이 그대로 완성된다. 화면 코드에는 조건이 한 곳뿐이다.
 * ★ **`YEOUL_MOTION.roll` 을 쓰지 않는다.** 그쪽은 기본 그림이 없어 기쁨 자세로 버티는 자리라,
 *   그대로 가져다 쓰면 **기쁨 자세를 놓고 "구르기" 라고 말하게 된다.** 미리보기는 진짜 구르기
 *   그림이 있을 때만 뜨는 것이 맞고, 그 판단을 이 상수 하나가 쥔다.
 * ★ 타입을 `string` 으로 박아 둔 이유 — 빈 문자열 리터럴로 좁혀지면 값을 채웠을 때 조건문이
 *   죽은 가지로 취급된다. 미리보기를 끄려면 `''` 로 되돌리면 된다.
 * ★ 주소는 `demoUrl` 이 만든다 — 손으로 적으면 배포에서 `/images` 앞머리가 겹치거나 빠진다
 *   (`lib/assets.ts` 머리말). 키는 `images/zzal/demo/v7/roll.v1.webp` 한 벌이고,
 *   앞머리를 누가 붙이느냐만 로컬·배포가 다르다.
 */
export const GRAD_PREVIEW_SRC: string = demoUrl('v7/roll.v1');

/**
 * "이런 동작도 보고 싶어요" 자유 입력칸의 문구와 상한.
 *
 * ★★ **문구가 사용자를 탓하지 않는다.** 특히 하루 상한에 걸렸을 때 "너무 많이 보냈어요" 는
 *   쓰지 않는다 — 우리 쪽 사정을 사용자 잘못으로 옮기는 말이고, 자캐 커뮤니티에서 가장 싫어하는
 *   결이다. 아이가 "오늘 들은 이야기는 여기까지" 라고 제 사정으로 말하게 둔다.
 * ★ `limit` 문구에만 "내일" 을 쓸 수 있다 — 하루 상한은 **한국 시각 자정에 풀린다**는 것이
 *   서버 계약에 적혀 있어서다. 다른 문구에는 시각을 약속하지 않는다.
 * ★ 상한 60자는 **서버·DB·화면이 같은 값**이다(요청 검증 `@Size(60)` · 표의 `varchar(60)`).
 *   한쪽만 바꾸면 화면은 받아 놓고 서버가 거절하는 자리가 생긴다.
 */
export const WISH_COPY = {
  label: '이런 동작도 보고 싶어요',
  placeholder: '예) 검을 휘두른다',
  send: '보내기',
  sending: '보내는 중…',
  /** 보낸 뒤 입력칸 자리에 대신 남는 한 줄. */
  done: '잘 들었어요. 적어 두었어요.',
  /** 실패 안내. 코드별로 한 줄씩 — 어느 것도 사용자를 탓하지 않는다. */
  fail: {
    tooLong: '한 줄로 짧게 적어 주시면 보낼 수 있어요.',
    login: '로그인하면 남길 수 있어요.',
    noPet: '아이를 찾지 못했어요. 새로고침하고 다시 해 주세요.',
    limit: '오늘 들은 이야기는 여기까지예요. 내일 또 들려주세요.',
    other: '지금은 보내지 못했어요. 잠시 후 다시 눌러 주세요.',
  },
} as const;

/**
 * 해금 판 문구 — **두 가지 해금을 같은 판으로, 다른 말로** 알린다(2026-09-21 판정 5).
 *
 * ★★ 해금은 하나가 아니다. 언제 오는지도, 그림을 어디서 가져오는지도 다르다.
 *   - `now`   = 2층 기본 행동. 돌보다가 **그 자리에서** 열린다. 그림은 부화 때 이미 구워져 있다.
 *   - `slept` = 심화 행동·선물. **자는 동안 되고 아침에 도착**한다. 그림이 아직 없을 수 있다.
 *   두 경우를 한 문장으로 뭉치면 한쪽이 반드시 거짓이 된다 — "자는 동안 배웠어요" 를
 *   즉시 해금에 쓰면 자지도 않았는데 잤다고 말하는 꼴이다.
 *
 * ★ 지킬 수 없는 약속을 쓰지 않는다. 언제 또 열리는지 우리도 모르므로 다음을 예고하지 않는다.
 * ★ 사용자를 탓하거나 재촉하지 않는다("드디어"·"이제야"·"자주 오셔야" 전부 안 쓴다).
 */
export const UNLOCK_COPY = {
  now: {
    // ★ 즉시 해금 판 문안 = **3안**(상훈님 2026-09-21 판정 1). "배웠어요" 가 아니라
    //   "할 수 있게 됐어요" 로 둔 것은, 이 판이 **지금 바로 눌러 볼 수 있는 동작**을 알리는 자리라서다.
    //   "자고 일어나면 보여드릴게요" 의 결은 아침 판(`slept`)의 몫이다 — 여기 쓰면 거짓이 된다.
    title: '새로 할 수 있게 됐어요',
    /** @param names 이번에 열린 것들. 한 개면 그대로, 여러 개면 가운뎃점으로 잇는다. */
    body: (names: string) => `${names} — 방금 열렸어요. 바로 보여드릴게요!`,
    previewBadge: '새 동작',
    previewCaption: (name: string) => `${name}, 이렇게 움직여요`,
    close: '방으로 돌아가기',
  },
  slept: {
    title: '자는 동안 배워 왔어요',
    body: (names: string) => `${names} — 오늘 아침에 도착했어요.`,
    previewBadge: '새 동작',
    previewCaption: (name: string) => `${name}, 이렇게 움직여요`,
    close: '앨범에서 보기',
  },
  // ★ 옛 `noPreviewNote`("그림은 아직 그리는 중이에요")는 **없앴다**(2026-09-21 판정 11).
  //   아침 목록에는 그림이 반드시 있다 — 서버가 `OPEN`(사람이 검수를 통과시킨 것)이면서 도착
  //   시각이 찍힌 것만 담고, 그림 키도 도착한 뒤에만 준다(`PetResponses.java` · `ZzalMotion.java`
  //   · `AdminService.java`). 설 자리가 없는 문장이라 화면에서 붙이던 자리와 함께 지웠다.
  //   자리표를 다시 만들지 말 것 — 그림이 없으면 `FirePreview` 가 칸을 접는다(A-09).
} as const;

/**
 * "이런 동작도 보고 싶어요" 를 보낸 뒤 뜨는 답 판(2026-09-21 판정 5).
 *
 * ★★ **"곧 추가하겠다" 를 쓰지 않는다.** 언제 되는지 우리도 모르고, 단정하는 순간
 *   늦어지는 분께는 그대로 거짓말이 된다. 대신 **이미 참인 것**만 말한다 —
 *   글은 진짜로 저장되고, 만드는 사람이 그걸 읽고 다음에 무엇을 그릴지 정한다.
 * ★ 고마움을 전하되 보답을 약속하지 않는다. 약속은 문구가 아니라 다음 판으로 갚는다.
 */
export const WISH_REPLY = {
  title: '잘 받았어요',
  body: '적어 주신 동작, 만드는 사람이 그대로 읽어요.\n어떤 걸 보고 싶어 하시는지 알아야 다음에 무엇을 그릴지 정할 수 있어요.',
  close: '방으로 돌아가기',
} as const;

/** 자유 입력 상한. 서버 검증·DB 칸 길이와 같은 값이라 **한쪽만 바꾸면 안 된다.** */
export const WISH_MAX = 60;
