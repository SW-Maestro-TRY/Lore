// 알 온보딩(2026-10-13) — **칸 구성·칩 라벨·순서를 여기 한 파일에 데이터로 둔다.**
//
// ★ 왜 한 파일인가 — 칩·순서는 아직 미확정이다(상훈님 10/13 "칩·순서는 미확정"). 이 배열만
//   바꿔 끼우면 화면(`PersonaEgg.tsx`)·저장 합치기(`toCharacterInput`)·계측(step 번호)이 따라온다.
//   화면 코드에 칩 이름을 다시 적지 않는다 — 두 벌이 되면 한쪽만 고쳐진다.
//
// ★ 서버는 안 건드린다(상훈님 결정 5). 지금 서버 칸은 name·personality(ies)·tone·genre·world·note 뿐이다.
//   새 칸(종족·직업/소속·성격 한 줄·좋아함·싫어함)은 **임시로 `note`(그 밖에)에 합쳐** 보낸다 —
//   "종족: 수인 · 직업: 학생 · 좋아함: 책, 별 · 싫어함: 벌레" 처럼. 칸별 저장 API 가 생기면
//   `target` 만 바꾸면 된다.
// ★ 저장은 마지막에 **한 번**(`POST draft/{id}/character`)이다. 이 호출은 한 아이에 한 번만 받는다
//   (두 번째는 409 ZZAL_PET_NOT_DRAFT). 그래서 넣는 동안은 브라우저 저장(localStorage)에 둔다.
import { CHAR_TEXT_MAX, type CharacterInput } from '../../lib/pet';
import { PERSONALITY_OF } from './constants';

/** 이 칸의 답을 서버 어느 자리에 싣는가. `note` 는 `noteLabel: 값` 꼴로 합쳐진다. */
export type PersonaTarget = 'world' | 'genre' | 'persona' | 'note';

export interface PersonaField {
  /** 저장·계측 키. 영문 소문자. 바꾸면 브라우저에 남은 옛 답과 이어지지 않는다(그건 괜찮다). */
  key: string;
  /** 한 화면에 칸이 둘 이상일 때만 보이는 작은 제목. 하나면 비워 둔다(질문이 곧 제목). */
  label?: string;
  chips: readonly string[];
  /** 칩을 몇 줄로 나눠 보일지 — 줄마다 칩 수(칩 어휘 표 `rows`). 없으면 한 덩어리로 흘린다. */
  rows?: readonly number[];
  /** 줄 이름(작게). 없으면 줄만 나눈다. */
  rowNames?: readonly string[];
  /** 여러 개 고를 수 있는가. false 면 누를 때마다 바뀐다. */
  multi: boolean;
  /** 직접 적는 칸. 없으면 칩만. `max` 는 이 칸 혼자의 상한이다(합친 뒤 상한은 아래 `NOTE_MAX`). */
  text?: { ph: string; max: number };
  target: PersonaTarget;
  /** `note` 로 합칠 때 붙는 이름. */
  noteLabel?: string;
}

export interface PersonaStep {
  /** 계측 `type` 값이자 브라우저 저장 키. 열거값이라 사람이 쓴 글이 아니다. */
  key: string;
  /** 위에 작게 붙는 묶음 이름(바탕·누구인가·취향·그 밖에). */
  section: string;
  /** 질문 한 줄. */
  q: string;
  /** 질문 아래 부연. 없으면 안 그린다. */
  sub?: string;
  /** 알 껍데기에 남는 점 색(dots 판). 알 그림에 원래 있는 옅은 무늬(초록·노랑)와 섞이지 않게 **진한 색 + 흰 테두리**. */
  color: string;
  fields: readonly PersonaField[];
}

/** 이름 칸. 필수다 — 건너뛰기가 없다. */
export const NAME_STEP = {
  key: 'name',
  section: '이름',
  q: '이 아이의 이름은?',
  sub: '이름을 넣으면 알이 깨어나기 시작해요.',
  max: 12,
  ph: '여울',
  color: '#C9685A',
} as const;

/** 서버 `note` 한 칸의 상한(`@Size(max = 200)`). 합친 결과가 넘치면 **뒤에서** 자른다. */
export const NOTE_MAX = CHAR_TEXT_MAX.extra;

/**
 * 이름 다음에 묻는 칸들. **순서가 곧 화면 순서**다.
 * 칩 어휘는 `~/shared-context/work/projects/lore/persona-chips-1013.md` **v2** 3절 데이터 블록(커밋 84a48e9 — 칩은 넓은 범주어, 자리표시는 실제 원문을 다듬은 세세한 예)에서 옮겼다.
 * 지금 값(상훈님 10/13 2차 결정): 어떤 세상(세계관·장르를 한 칸으로) → 직업/소속 · 성격 → 좋아하는 것 · 싫어하는 것 → 그 밖에.
 * 종족 칸은 뺐다(종족·설정은 그 밖에에). 원칙: 고민시키는 칸·칩은 빼고 재밌게 설레며 쓰게.
 * ★ 장르(`genre`) 칸은 지금 비워 보낸다. 종족 칩 묶음은 쓰지 않는다(칸 삭제).
 */
export const PERSONA_STEPS: readonly PersonaStep[] = [
  {
    key: 'world', section: '바탕', q: '어떤 세상이야?', sub: '여러 개 골라도 돼요.', color: '#4F8FCB',
    fields: [{
      key: 'world', multi: true, target: 'world',
      chips: ['현대', '학교', '일상', '판타지', '중세', '미래', '어두운 세상'],
      text: { ph: '예: 홍대 근처 자취방 · 수인이 사는 이세계 · 꽃 바이러스로 봉쇄된 도시', max: 100 },
    }],
  },
  {
    key: 'job', section: '누구인가', q: '무엇을 하며 지내요?', color: '#D9A21F',
    fields: [{
      key: 'job', multi: false, target: 'note', noteLabel: '직업',
      chips: ['학생', '직장인', '가게 운영', '모험가', '마법사', '그 외'],
      text: { ph: '예: 꽃집 겸 잡화점 사장 · 견습 별지기 · 가문의 소가주', max: 40 },
    }],
  },
  {
    key: 'persona', section: '누구인가', q: '어떤 성격이에요?', sub: '여러 개 골라도 돼요. 처음 고른 것이 대표예요.', color: '#E0607E',
    fields: [{
      key: 'persona', multi: true, target: 'persona', noteLabel: '성격', rows: [4, 4],
      chips: ['활발', '긍정적', '장난기', '다정', '차분', '무뚝뚝', '시크', '수줍음'],
      text: { ph: '예: 겉으론 밝은데 속은 겁이 많아요 · 건들지 않으면 안 물지만 건들면 물어요 · 원하는 게 있으면 애교', max: 80 },
    }],
  },
  {
    key: 'likes', section: '취향', q: '좋아하는 건 뭐예요?', sub: '여러 개 골라도 돼요.', color: '#EE8434',
    fields: [{
      key: 'likes', multi: true, target: 'note', noteLabel: '좋아함',
      chips: ['음식', '간식', '음료', '놀이', '장소', '동물', '날씨'],
      text: { ph: '예: 크림빵처럼 달달한 거 · 오뎅국에 든 무랑 곤약 · 별 잘 보이는 곳 찾아다니기', max: 60 },
    }],
  },
  {
    key: 'dislikes', section: '취향', q: '싫어하는 건요?', sub: '여러 개 골라도 돼요.', color: '#6F8394',
    fields: [{
      key: 'dislikes', multi: true, target: 'note', noteLabel: '싫어함',
      chips: ['음식', '소리', '벌레', '어둠', '물', '날씨', '간섭'],
      text: { ph: '예: 민트맛은 질색 · 피망은 먹을 수는 있음 · 갑자기 일정 바뀌는 거', max: 60 },
    }],
  },
  {
    key: 'extra', section: '그 밖에', q: '그 밖에 알려 주고 싶은 것', sub: '종족·설정도 여기에 적어 주세요.', color: '#9B7653',
    fields: [{ key: 'extra', chips: [], multi: false, text: { ph: '예: 긴장하면 리본 끝을 만지작거림 · 가족 얘기는 금지 · 사실 염소 수인', max: NOTE_MAX }, target: 'note' }],
  },
];

/**
 * 성격 칩 → 서버 성격 키. 서버는 다섯 키(GENTLE·LIVELY·SHY·CLINGY·COOL)뿐이다.
 * ★ 칩 v2(활발·긍정적·장난기·다정·차분·무뚝뚝·시크·수줍음) 중 대응되는 것만 성격 칸으로 보낸다 —
 *   활발·시크·수줍음은 그대로, 다정→온순(GENTLE), 장난기→활발(LIVELY, 근사).
 * ★ 이름이 서버 값과 **똑같지 않은 칩**(근사 둘 포함)은 그 말 그대로 note 의 "성격:" 줄에도 싣는다(임시) —
 *   사용자가 고른 낱말이 근사값 뒤로 사라지지 않게.
 */
const PERSONA_KEY: Record<string, string | undefined> = { 활발: 'LIVELY', 시크: 'COOL', 수줍음: 'SHY', 온순: 'GENTLE', 응석: 'CLINGY', 다정: 'GENTLE', 장난기: 'LIVELY' };
/** 서버 성격과 이름까지 같은 칩. 이 밖의 칩은 note 에도 남긴다. */
const PERSONA_EXACT = new Set(Object.keys(PERSONALITY_OF));

/** 한 칸의 답. 칩과 글을 따로 둔다 — 칸별 저장 API 가 생기면 그대로 옮겨 실을 수 있게. 칩을 눌러도 글 칸엔 아무것도 안 들어간다. */
export interface FieldAnswer { chips: string[]; text: string }
export type Answers = Record<string, FieldAnswer>;

const EMPTY: FieldAnswer = { chips: [], text: '' };
export const answerOf = (a: Answers, key: string): FieldAnswer => a[key] ?? EMPTY;
export const hasAnswer = (x: FieldAnswer | undefined) => !!x && (x.chips.length > 0 || !!x.text.trim());

/** 칩·글을 한 줄로. 세계관과 같은 셈(` · `)이다 — 옛 화면의 `worldOf` 와 같은 모양. */
const lineOf = (x: FieldAnswer, max: number) => [...x.chips, x.text.trim()].filter(Boolean).join(' · ').slice(0, max);

/**
 * `note` 로 합쳐질 앞부분(구조 칸). `그 밖에` 칸의 남은 자리를 알려 주려고 따로 꺼낸다.
 * ★ 이 셈과 `toCharacterInput` 의 셈이 **같아야** 한다 — 갈리면 경고 없이 글이 잘린다.
 */
export function noteHead(a: Answers, steps: readonly PersonaStep[] = PERSONA_STEPS): string {
  const parts: string[] = [];
  for (const st of steps) {
    for (const f of st.fields) {
      const x = answerOf(a, f.key);
      if (f.target === 'note' && f.noteLabel) {
        const v = [x.chips.join(', '), x.text.trim()].filter(Boolean).join(' · ');
        if (v) parts.push(`${f.noteLabel}: ${v}`);
      }
      // 성격: 서버 값과 이름이 같은 칩(활발·시크·수줍음 …)은 성격 칸으로만, 나머지 칩(근사 포함)과 글 한 줄은 note 로도.
      if (f.target === 'persona' && f.noteLabel) {
        const extraChips = x.chips.filter((c) => !PERSONA_EXACT.has(c));
        const v = [extraChips.join(', '), x.text.trim()].filter(Boolean).join(' · ');
        if (v) parts.push(`${f.noteLabel}: ${v}`);
      }
    }
  }
  return parts.join(' · ');
}

/** `그 밖에`(이름표 없는 note 칸)에 아직 남은 자리. */
export function extraRoom(a: Answers, steps: readonly PersonaStep[] = PERSONA_STEPS): number {
  const head = noteHead(a, steps);
  return Math.max(0, NOTE_MAX - head.length - (head ? 3 : 0));
}

/**
 * 모은 답 → 지금 서버 계약(`CharacterInput`). 안 채운 칸은 보내지 않는다(서버가 선택으로 받는다).
 * ★ 성격은 고른 순서 그대로 전부 — 맨 앞이 대표(옛 화면과 같은 규칙).
 */
export function toCharacterInput(name: string, a: Answers, steps: readonly PersonaStep[] = PERSONA_STEPS): CharacterInput {
  const out: CharacterInput = { name: name.trim().slice(0, NAME_STEP.max) };
  const freeNotes: string[] = [];
  for (const st of steps) {
    for (const f of st.fields) {
      const x = answerOf(a, f.key);
      if (!hasAnswer(x)) continue;
      if (f.target === 'world') out.world = lineOf(x, CHAR_TEXT_MAX.world) || undefined;
      else if (f.target === 'genre') out.genre = lineOf(x, CHAR_TEXT_MAX.genre) || undefined;
      else if (f.target === 'persona') {
        const all = [...new Set(x.chips.map((c) => PERSONA_KEY[c]).filter((p): p is string => !!p))] as NonNullable<CharacterInput['personality']>[];
        if (all.length) { out.personality = all[0]; out.personalities = all; }
      } else if (f.target === 'note' && !f.noteLabel && x.text.trim()) freeNotes.push(x.text.trim());
    }
  }
  const note = [noteHead(a, steps), ...freeNotes].filter(Boolean).join(' · ').slice(0, NOTE_MAX);
  if (note) out.note = note;
  return out;
}

/** 날아가는 "말 조각" 글자. 12자 넘으면 말줄임. */
export const pieceOf = (t: string) => (t.length > 12 ? `${t.slice(0, 12)}…` : t);

// ── 브라우저 저장 ─────────────────────────────────────────────────
// ★ 인스타 내장 브라우저는 앱을 바꿨다 오면 탭이 죽는 일이 잦다. 넣은 칸이 그때 사라지면
//   자캐를 맡긴 사람에게 가장 나쁜 일이다. 칸별 즉시 저장 API 가 생기기 전까지의 **임시 방편**이다.
// ★ 아이 번호(petId)별로 둔다 — 다른 아이(다른 계정)의 답이 이어 붙지 않게.

export interface Saved {
  v: 1;
  petId: number;
  name: string;
  /** 이름을 넣었는가(= 알 질문 단계에 들어섰는가). */
  named: boolean;
  /** 지금 보던 칸 번호(PERSONA_STEPS 순번). 길이와 같으면 다 넣었다는 뜻. */
  idx: number;
  answers: Answers;
  /** 건너뛴 칸 키. 계측·요약용. */
  skipped: string[];
  /**
   * 알에 앉은 점(= 넣은 칸) 키. 「이전」으로 돌아가면 그 칸의 점이 빠지되 글·칩은 남아 있어서,
   * 답이 있다고 넣은 칸이라 볼 수 없다. 옛 저장본(없음)은 답이 있는 칸으로 되살린다.
   */
  placed?: string[];
  at: number;
}

const KEY = (petId: number) => `zzal_persona_v1:${petId}`;

export function loadSaved(petId: number | null): Saved | null {
  if (!petId) return null;
  try {
    const raw = window.localStorage.getItem(KEY(petId));
    if (!raw) return null;
    const s = JSON.parse(raw) as Saved;
    if (s?.v !== 1 || s.petId !== petId || typeof s.idx !== 'number' || !s.answers) return null;
    return s;
  } catch {
    return null;
  }
}

export function writeSaved(s: Saved): void {
  try { window.localStorage.setItem(KEY(s.petId), JSON.stringify(s)); } catch { /* 막힌 브라우저 — 저장 없이 간다 */ }
}

export function clearSaved(petId: number | null): void {
  if (!petId) return;
  try { window.localStorage.removeItem(KEY(petId)); } catch { /* 무시 */ }
}

/**
 * 알 시안 — `dots`(기본, 칸마다 색 점) / `glow`(빛만) / `off`(옛 한 화면, 되돌려 보기용).
 * 읽는 순서: 주소 `?egg=` → 브라우저 저장 `zzal.devEgg`(개발 리모컨이 쓰는 키) → `dots`.
 * ★ 사용자에게는 늘 dots 다. 나머지는 비교·되돌려 보기용으로만 남긴다(상훈님 10/13 "닷이 더 좋은 것 같아").
 */
export type EggVariant = 'dots' | 'glow' | 'off';
export const DEV_EGG_KEY = 'zzal.devEgg';
const asVariant = (v: string | null | undefined): EggVariant | null =>
  v === 'dots' || v === 'glow' || v === 'off' ? v : null;
export function eggVariant(): EggVariant {
  try {
    const q = asVariant(new URLSearchParams(window.location.search).get('egg'));
    if (q) return q;
  } catch { /* 무시 */ }
  try {
    const d = asVariant(window.localStorage.getItem(DEV_EGG_KEY));
    if (d) return d;
  } catch { /* 막힌 브라우저 */ }
  return 'dots';
}
