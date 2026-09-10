// 여울 시안의 데이터 표. **숫자와 문구만** — React 도 화면도 모른다.
//
// 출처 = 클로드 디자인 `여울 반응형.dc.html`(2026-09-07 상훈님 최종본)의 상수부를 그대로 옮긴 것.
// 시안이 정본이므로 값을 임의로 바꾸지 않는다. 바꿀 일이 생기면 시안을 먼저 올리고 여기를 맞춘다.
//
// ★ 여기 값은 **프론트 전용 목**이다. 서버가 붙으면 rules.ts·서버 응답이 정본을 들고 오고
//   이 표는 문구(카피)만 남는다. 지금 숫자는 화면을 눌러 보기 위한 자리표시다.
import { assetUrl, demoUrl } from '../constants';
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
  { id: 'cream', name: '크림', wall: '#FBEFE2', floor: '#EFDFCC' },
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

/** 캐릭터 칸 네 묶음 — 칩 한 줄 + 긴 글 한 줄(9/6 상훈님 2차 결정). */
export const CHAR_GROUPS = [
  { key: 'persona', label: '성격 · 다섯 중 하나', opts: PERSONA, ph: '자세히 쓰셔도 돼요. 예: 낯을 가리지만 한번 친해지면 계속 따라다녀요' },
  { key: 'tone', label: '말투', opts: TONE, ph: '입버릇이나 자주 쓰는 말이 있으면 적어 주세요' },
  { key: 'genre', label: '장르', opts: GENRE, ph: '어떤 이야기 속 아이인지 적어 주세요' },
  { key: 'world', label: '세계관', opts: WORLD, ph: '사는 곳, 시대, 함께 있는 사람들 같은 걸 적어 주세요' },
] as const;

export const GOOD_EX: ReadonlyArray<readonly [string, string]> = [
  ['얼굴이 크게 나온 정면', '#E8F0E2'],
  ['선이 또렷한 그림', '#E8F0E2'],
  ['한 마리만 · 배경 없이', '#E8F0E2'],
];
export const BAD_EX: ReadonlyArray<readonly [string, string]> = [
  ['여러 명', '#F6E7E4'],
  ['뒷모습', '#F6E7E4'],
  ['너무 작음', '#F6E7E4'],
  ['배경이 복잡', '#F6E7E4'],
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

export const ONB_COPY: Record<StepKey, readonly [string, string]> = {
  landing: ['그림 한 장이면,\n같이 살 수 있어요', '내가 그린 아이가 방 하나를 얻습니다.'],
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
  { at: '15분', room: 'bath', act: 'a', done: 'clean', text: '바닥에 흔적이 생겼어요. 욕실에서 치워 주세요.' },
  { at: '20분', room: 'play', act: 'a', done: 'game', text: '놀고 싶어요. 좌우 맞히기는 하루 세 판이에요.' },
  { at: '25분', room: 'chat', act: null, done: 'chat', text: '하루에 세 번 불러요. 말풍선을 누르면 답할 수 있어요.' },
  { at: '40분', room: 'bed', act: 'a', done: 'sleep', text: '저녁 7시가 되면 침실에서 재워 주세요. 자는 동안 다 회복돼요.' },
  { at: '60분', room: 'album', act: 'a', done: 'album', text: '함께한 순간은 앨범 벽에 쌓여요. 열어 보세요.' },
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
export const FRAME_KEYS = ['joy', 'eat', 'wash', 'base', 'sad', 'practice', 'shy', 'sick'] as const;
export type FrameKey = (typeof FRAME_KEYS)[number];

/**
 * 기본 8종. **방에 들어왔다는 것은 이 여덟이 다 만들어졌다는 뜻**이다 —
 * 부화가 끝나야 '태어났어요' 가 뜨고, 그걸 눌러야 방에 들어온다.
 * 그래서 진짜 방에서 이 중 하나라도 없으면 그건 폴백할 일이 아니라 **고장**이다.
 */
export const BASIC_KEYS = ['base', 'eat', 'joy', 'sad', 'sick', 'practice', 'shy', 'call'] as const;

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
  { at: '5칸', room: 'bath', act: 'a', done: 'CLEAN', text: '바닥에 흔적이 생겼어요. 욕실에서 치워 주세요.' },
  { at: '6칸', room: 'play', act: 'a', done: 'GAME', text: '같이 놀아 볼까요. 마당에서 좌우 맞히기를 한 판 시작해 주세요.' },
  { at: '7칸', room: 'album', act: 'a', done: 'SHARE', text: '이 모습 가져가실래요. 앨범 벽에서 액자를 열어 공유해 보세요.' },
  { at: '8칸', room: 'bed', act: 'a', done: 'NAP', text: '졸린가 봐요. 침실에서 재우고, 다시 깨워 주세요.' },
  { at: '9칸', room: null, act: null, done: 'DONE', text: '이제 혼자서도 괜찮아요. 여기부터는 시간이 흐르기 시작해요.' },
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
