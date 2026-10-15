// 개발용 **목 데이터 프리셋** 세 벌 — 온보딩 칸을 한 번에 채워 「알에 넣기」만 누르면 되게(2026-10-13).
//
// ★ 상훈님 원문(10/13): "목 데이터 넣는 버전도 하나 있었으면 좋겠다. 리모컨으로 내가 넣을 때는."
//   → 한 벌이 아니라 세 벌(현대 학생 / 판타지 마법사 / 어두운 세상 모험가). 리모컨 「온보딩」 탭과
//   캐릭터 칸(dev 도구가 켜진 서버에서만 보이는 줄) 두 곳이 **이 한 표**를 쓴다.
//
// ★ 두 모양을 같이 든다.
//   · `answers` — 알 온보딩(feat/onboarding-egg-1013 `personaSteps.ts`)의 칸 키(world·job·persona·likes·dislikes·extra)와
//     같은 꼴 `{ chips, text }`. 칩은 그 브랜치의 칩 어휘에서만 골랐다 — 두 브랜치가 만나면 그대로 꽂힌다.
//   · `charScreen` — 지금 캐릭터 칸(CHAR_GROUPS: persona·tone·genre·world 칩 + 자유 입력 + 그 밖에)에 얹는 값.
//     새 칸(직업·좋아함·싫어함)은 그 브랜치와 같은 방식으로 「그 밖에」 에 `이름: 값` 으로 합친다.
// ★ 누가 봐도 테스트 값이게 — 실제 사용자 글처럼 보이면 판정 기록에 섞였을 때 가려낼 수 없다. 이름 뒤에 표시를 안 붙이는 대신
//   서버 기록의 그 밖에 맨 끝에 `(dev 프리셋)` 을 남긴다.
import { CHAR_TEXT_MAX } from '../../lib/pet';

export interface PresetAnswer { chips: string[]; text: string }

export interface DevPreset {
  id: 'student' | 'mage' | 'wanderer';
  label: string;
  name: string;
  /** 알 온보딩 칸 키 → 답. */
  answers: Record<'world' | 'job' | 'persona' | 'likes' | 'dislikes' | 'extra', PresetAnswer>;
  /** 지금 캐릭터 칸의 칩(CHAR_GROUPS 어휘). */
  picks: { persona: string[]; tone: string[]; genre: string[]; world: string[] };
}

export const DEV_PRESETS: readonly DevPreset[] = [
  {
    id: 'student', label: '현대 학생', name: '하늘',
    answers: {
      world: { chips: ['현대', '학교'], text: '바닷가 작은 도시의 고등학교' },
      job: { chips: ['학생'], text: '고2 · 도서부' },
      persona: { chips: ['활발', '장난기'], text: '시험 전날에만 벼락치기' },
      likes: { chips: ['간식', '장소'], text: '딸기우유 · 학교 옥상' },
      dislikes: { chips: ['벌레', '간섭'], text: '모기 · 잔소리' },
      extra: { chips: [], text: '사실 고양이 수인 · 긴장하면 귀가 튀어나옴' },
    },
    picks: { persona: ['활발'], tone: ['반말'], genre: ['학원', '일상'], world: ['현대', '학교'] },
  },
  {
    id: 'mage', label: '판타지 마법사', name: '루멘',
    answers: {
      world: { chips: ['판타지', '중세'], text: '별빛 탑이 서 있는 왕국' },
      job: { chips: ['마법사'], text: '견습 별빛 마법사' },
      persona: { chips: ['차분', '다정'], text: '말수는 적지만 먼저 챙겨 줌' },
      likes: { chips: ['음료', '날씨'], text: '허브차 · 별 뜬 밤' },
      dislikes: { chips: ['소리', '물'], text: '천둥 · 비에 젖는 것' },
      extra: { chips: [], text: '엘프 · 왼쪽 눈이 별색으로 빛남' },
    },
    picks: { persona: ['온순'], tone: ['존댓말'], genre: ['판타지'], world: ['중세'] },
  },
  {
    id: 'wanderer', label: '어두운 세상 모험가', name: '카엘',
    answers: {
      world: { chips: ['어두운 세상', '판타지'], text: '해가 뜨지 않는 폐허 대륙' },
      job: { chips: ['모험가'], text: '혼자 떠도는 유물 사냥꾼' },
      persona: { chips: ['무뚝뚝', '시크'], text: '경계심이 강하지만 의리 있음' },
      likes: { chips: ['동물', '음식'], text: '떠돌이 늑대 · 구운 고기' },
      dislikes: { chips: ['어둠', '간섭'], text: '등불 없는 밤 · 명령' },
      extra: { chips: [], text: '오른팔에 저주 문양 · 과거 얘기는 금지' },
    },
    picks: { persona: ['시크'], tone: ['무뚝뚝'], genre: ['판타지'], world: [] },
  },
];

const join = (a: PresetAnswer) => [...a.chips, a.text].filter(Boolean).join(', ');

/** 지금 캐릭터 칸에 얹을 값 — `actions.patch` 에 그대로 넣는다. */
export function presetForCharScreen(p: DevPreset): { petName: string; picks: Record<string, string[]>; texts: Record<string, string> } {
  const a = p.answers;
  const extra = [
    `직업: ${join(a.job)}`, `성격: ${a.persona.text}`, `좋아함: ${join(a.likes)}`, `싫어함: ${join(a.dislikes)}`,
    a.extra.text, '(dev 프리셋)',
  ].join(' · ').slice(0, CHAR_TEXT_MAX.extra);
  return {
    petName: p.name,
    picks: { ...p.picks },
    texts: { world: a.world.text.slice(0, CHAR_TEXT_MAX.world), extra },
  };
}
