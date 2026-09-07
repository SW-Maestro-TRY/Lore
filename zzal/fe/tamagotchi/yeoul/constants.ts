// 여울 시안의 데이터 표. **숫자와 문구만** — React 도 화면도 모른다.
//
// 출처 = 클로드 디자인 `여울 반응형.dc.html`(2026-09-07 상훈님 최종본)의 상수부를 그대로 옮긴 것.
// 시안이 정본이므로 값을 임의로 바꾸지 않는다. 바꿀 일이 생기면 시안을 먼저 올리고 여기를 맞춘다.
//
// ★ 여기 값은 **프론트 전용 목**이다. 서버가 붙으면 rules.ts·서버 응답이 정본을 들고 오고
//   이 표는 문구(카피)만 남는다. 지금 숫자는 화면을 눌러 보기 위한 자리표시다.
import { assetUrl, demoUrl } from '../constants';

/** 방 다섯 칸. key 는 상태 저장·검사의 손잡이라 함부로 안 바꾼다. */
export const ROOM_KEYS = ['table', 'bath', 'play', 'bed', 'album'] as const;
export type RoomKey = (typeof ROOM_KEYS)[number];

/** 하단 타일에 적히는 이름(시안 확정: 식탁→주방, 놀이→마당). */
export const ROOM_NAME: Record<RoomKey, string> = {
  table: '주방', bath: '욕실', play: '마당', bed: '침실', album: '앨범',
};

/** 시트 제목은 타일 이름과 다르다(시안 `sheetTitles`). */
export const SHEET_TITLE: Record<string, readonly [string, string]> = {
  table: ['식탁', '배부름과 밥'],
  bath: ['욕실', '흔적과 몸단장'],
  play: ['놀이', '대화 · 맞히기 · 달리기'],
  bed: ['침실', '재우기'],
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
 * 유저 설문 다섯 문항. **온보딩 칸이 아니다** — 시안이 이걸 샘플 방으로 옮겼다.
 * 여울(연습 상대)이 방에서 한 문항씩 물어보고, 언제든 "나중에" 로 넘길 수 있다.
 */
export const USER_Q = [
  { key: 'age', label: '연령대', opts: ['10대', '20대', '30대', '40대 이상'] },
  { key: 'from', label: '어디서 알게 됐나요', opts: ['X(트위터)', '인스타', '유튜브', '친구 소개', '검색'] },
  { key: 'draw', label: '자캐는', opts: ['그려요', '보는 걸 좋아해요', '둘 다요'] },
  { key: 'when', label: '주로 만나는 시간', opts: ['아침', '점심', '저녁', '밤'] },
  { key: 'whose', label: '이 아이는', opts: ['내 자캐', '친구 자캐', '좋아하는 캐릭터'] },
] as const;

/** 온보딩 네 칸. 가입은 칸이 아니라 랜딩 CTA 에서 뜨는 모달이다(상훈님 9/7 결정). */
export const STEPS = ['landing', 'upload', 'char', 'born'] as const;
export type StepKey = (typeof STEPS)[number] | 'user';

/** 화면 큰 갈래. onb → (샘플)room → egg → room. */
export type ScreenKey = 'onb' | 'room' | 'egg';

export const ONB_COPY: Record<StepKey, readonly [string, string]> = {
  landing: ['그림 한 장이면,\n같이 살 수 있어요', '내가 그린 아이가 방 하나를 얻습니다.'],
  upload: ['아이 그림을 올려요', '한 번에 한 장만 올려요. 이 그림이 그대로 아이가 돼요.'],
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

/** 액자에 걸리는 동작 여덟 종(여울 실물이 이 여덟뿐이라 순환시킨다). */
export const FRAME_KINDS = ['happy', 'eat', 'clean', 'idle', 'sad', 'train', 'pet', 'sick'] as const;
export type FrameKind = (typeof FRAME_KINDS)[number];

/** 동작 → 여울 그림. 시안이 쓰던 매핑 그대로. */
export const KIND_IMG: Record<FrameKind, string> = {
  idle: demoUrl('idle'), eat: demoUrl('eat'), happy: demoUrl('happy'), sad: demoUrl('sad'),
  sick: demoUrl('hungry'), train: demoUrl('train'), pet: demoUrl('pet'), clean: demoUrl('clean'),
};

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
export const LEARN_GOALS = [
  { name: '손 흔들며 인사', cond: '대화 답하기', need: 4, counter: 'cChat' },
  { name: '씻기', cond: '목욕하기', need: 3, counter: 'cBath' },
  { name: '자기', cond: '재우기', need: 3, counter: 'cSleep' },
  { name: '놀라기', cond: '좌우 맞히기', need: 3, counter: 'cGame' },
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
