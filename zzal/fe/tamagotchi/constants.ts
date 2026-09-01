// 자캐 다마고치 — 스킨과 무관한 상수.

/**
 * 정적 에셋이 사는 곳. **레포에는 그림을 넣지 않는다**(→ .gitignore).
 *
 * 값이 두 갈래다.
 *   배포(dev·운영)  `/images`  → CloudFront 가 S3 로 보낸다. 도메인을 안 박았으므로
 *                               dev 든 운영이든 각자 자기 도메인의 S3 를 본다.
 *   로컬            `''`       → `/zzal/demo/idle.webp` 가 되어 apps/web/public 에서 서빙된다.
 *                               `.env.local` 에 `NEXT_PUBLIC_CDN_BASE=` 한 줄이면 켜진다.
 *
 * ★ 로컬이 S3 를 안 거치게 한 이유 — 화면을 고치고 확인하는 왕복에 업로드가 끼면
 *   한 번에 몇 분씩 늘어난다. 로컬에서 빠르게 보고, 확정된 것만 올린다(2026-09-01 결정).
 *
 * ★ 폴더 이름은 로컬과 S3 가 **같다**(assets·bg·demo). 한쪽만 바꾸면 로컬에선 되는데
 *   배포에서만 깨지고, 그 차이는 배포한 뒤에야 드러난다.
 */
const CDN = process.env.NEXT_PUBLIC_CDN_BASE ?? '/images';

export const NAMES = ['여름', '노루', '단이', '보리', '설아', '하루', '도담', '미르', '온이', '새벽', '비단', '초록'];

/**
 * 모을 수 있는 움직임. 앞의 넷은 돌봄(이미 2프레임으로 보고 있는 것의 16프레임 판),
 * 뒤는 표현이다. 순서 = 해금 순서이고, 첫 두 개는 첫날 순서(튜토리얼)가 쓴다.
 *
 * ⚠️ 화면에는 아직 안 연 것의 **이름을 쓰지 않는다**. 생성이 실패하면 다른 것으로
 *    갈아끼워야 하는데, 이름을 약속해 버리면 그때 어길 말이 생긴다.
 */
export const MOVES = [
  '쓰다듬', '청소', '밥 먹기', '훈련',
  '손 흔들기', '윙크하트', '콩콩뛰기', '박수', '놀라펄쩍',
  '부끄러워', '구르기', '절하기', '넘어지기',
];

export const FRIENDS = [
  { name: '민지', n: 9, sub: '어제 밤에 재웠어요' },
  { name: '연우', n: 5, sub: '알을 품는 중' },
  { name: '해린', n: 12, sub: '전부 모았어요' },
  { name: '지오', n: 2, sub: '오늘 데려왔어요' },
];

/** 여울 기본 움짤(정지 대표컷 자리에도 쓴다). */
export const YEOUL = `${CDN}/zzal/demo/idle.webp`;

/**
 * 다 자란 여울이 쉬지 않고 노는 한 바퀴(30.7초). 첫 장에서 이걸 보여준다.
 *
 * 완벽 판정을 받은 16프레임 동작 8종을 기본자세로 이어 붙인 것 —
 * 식사·스쿼트·쓰다듬·먼지떨이·하이파이브·농구·볼콕찌르기·분무기.
 * 동작과 기본자세가 각각 1.92초씩 번갈아 나온다.
 *
 * ★발 높이가 idle.webp 와 같도록 캔버스를 맞춰 뽑았다(313x356).
 *   비율이 다르면 폴라로이드의 바닥선과 어긋난다.
 * 만든 곳 = `~/work/jakae-lab/01_움짤/과정/2026-08-31_여울완성본/루프빌드.py`
 * 2.7MB 라 레포에 넣지 않고 S3 로 올린다(images/zzal/demo/loop.webp).
 */
export const YEOUL_LOOP = `${CDN}/zzal/demo/loop.webp`;

/** 상태별 여울 그림. moodOf() 가 고른 값을 그대로 파일명으로 쓴다. */
export const YEOUL_MOOD: Record<string, string> = {
  idle: `${CDN}/zzal/demo/idle.webp`,
  eat: `${CDN}/zzal/demo/eat.webp`,
  hungry: `${CDN}/zzal/demo/hungry.webp`,
  clean: `${CDN}/zzal/demo/clean.webp`,
  happy: `${CDN}/zzal/demo/happy.webp`,
  sad: `${CDN}/zzal/demo/sad.webp`,
  pet: `${CDN}/zzal/demo/pet.webp`,
  train: `${CDN}/zzal/demo/train.webp`,
};

/**
 * 배운 동작에 대응하는 여울 그림. 돌봄 4종은 이미 상태 그림이 있고,
 * 표현 9종은 아직 전용 그림이 없어 기쁜 얼굴로 대신한다
 * (실제 서비스에서는 그 자리에 방금 구운 16프레임이 들어간다).
 */
export const MOVE_IMG: Record<string, string> = {
  '쓰다듬': `${CDN}/zzal/demo/pet.webp`,
  '청소': `${CDN}/zzal/demo/clean.webp`,
  '밥 먹기': `${CDN}/zzal/demo/eat.webp`,
  '훈련': `${CDN}/zzal/demo/train.webp`,
};

/** 받침이 있으면 앞의 것, 없으면 뒤의 것. "쓰다듬을" / "청소를" */
export function josa(word: string, withFinal: string, withoutFinal: string): string {
  const last = word.charCodeAt(word.length - 1);
  if (last < 0xac00 || last > 0xd7a3) return withoutFinal;   // 한글이 아니면 그냥
  return (last - 0xac00) % 28 > 0 ? withFinal : withoutFinal;
}

/**
 * 공통 에셋 — 전 사용자가 함께 쓰는 그림. 상훈님이 2026-08-25 에 확정한 시트를 잘라 낸 것이고,
 * 전부 캐릭터와 같은 313x350 이라 **캐릭터 앞에 그대로 겹친다**.
 * 원본 시트·제작법 = jakae-lab/01_움짤/01_결과/공통에셋/2026-08-25_확정/
 */
export const ASSET = {
  /** 부화 전 알이 조금씩 흔들린다. */
  eggIdle: `${CDN}/zzal/assets/egg_idle.webp`,
  /** 금이 가기 시작. 부화가 가까워졌을 때. */
  eggCrack: `${CDN}/zzal/assets/egg_crack.webp`,
  /** 직전 → 부화 → 깨짐 → 껍질. 한 번만 돈다. */
  eggHatch: `${CDN}/zzal/assets/egg_hatch.webp`,
  /** 바닥에 쌓이는 쓰레기 1~5단계. 캐릭터 앞을 가린다(자캐 자체는 더러워지지 않는다). */
  trash: [
    `${CDN}/zzal/assets/trash1.webp`, `${CDN}/zzal/assets/trash2.webp`, `${CDN}/zzal/assets/trash3.webp`,
    `${CDN}/zzal/assets/trash4.webp`, `${CDN}/zzal/assets/trash5.webp`,
  ],
  /** 치우는 순간 흩어지는 한 줌. */
  trashSweep: `${CDN}/zzal/assets/trash_sweep.webp`,
  /** 칭찬·해금 때 터지는 폭죽. */
  firework: `${CDN}/zzal/assets/firework.webp`,
  /** 자는 중 Zzz. */
  zzz: `${CDN}/zzal/assets/zzz.webp`,
  /** 잠 — 커튼이 화면을 덮는다(눈 뜬 채 자는 문제가 원천 해소된다). */
  curtainClosed: `${CDN}/zzal/assets/curtain_closed.webp`,
  curtainOpen: `${CDN}/zzal/assets/curtain_open.webp`,
  /** 커튼 위에서 노는 달·별. */
  moon: `${CDN}/zzal/assets/moon.webp`,
} as const;

/**
 * 무대 배경 16종 — 캐릭터 **뒤**에 깔린다(2026-08-30 생성).
 * 가운데와 아래 2/3 을 비우고 바닥선을 넣어 그린 것이라 캐릭터가 딛고 선 것처럼 보인다.
 * 원본 시트 = jakae-lab/01_움짤/과정/2026-08-30_배경/배경_4x4.png
 */
export const BACKGROUNDS = [
  { key: 'room',         label: '기본 방' },
  { key: 'window_day',   label: '햇살 창' },
  { key: 'window_night', label: '밤 창' },
  { key: 'window_rain',  label: '비 오는 창' },
  { key: 'field',        label: '풀밭' },
  { key: 'blossom',      label: '벚꽃' },
  { key: 'sunset',       label: '노을' },
  { key: 'starry',       label: '별 밤' },
  { key: 'sea',          label: '바닷가' },
  { key: 'snow',         label: '눈' },
  { key: 'forest',       label: '숲' },
  { key: 'cafe',         label: '카페' },
  { key: 'library',      label: '책장 방' },
  { key: 'cloud',        label: '구름 위' },
  { key: 'dots',         label: '도트 벽지' },
  { key: 'checker',      label: '체커 바닥' },
] as const;

export const bgUrl = (key: string) => `${CDN}/zzal/bg/${key}.webp`;
