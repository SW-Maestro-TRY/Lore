// 채팅(하루 3회의 부름) — 성격 그룹·원망 금지 필터·테스트용 템플릿. 정본 §10·§16.
//
// ★ v1 의 키워드 매칭 답변(answerFor)은 폐기. 대사는 서버 템플릿 line 을 **그대로** 렌더하고,
//   여기서는 (1) 성격 5그룹 표시명 (2) 출력 단계 2차 필터 BANNED_PATTERNS (3) 목 서버가 쓰는 templateReply 만 둔다.
//
// ⚠️ 캐릭터가 사용자를 원망하는 대사는 어떤 성격·상황에도 없다(§0 원칙 6, 자캐 커뮤니티 캐조종 규범).
//    "왜 안 왔어요" · "기다렸는데" · "혼자 두고" 류는 서버가 걸러도 여기서 한 번 더 거른다 — 두 겹이어야
//    한쪽이 실수해도 화면에 안 나간다. 스스로 슬퍼하는 것(수용)과 사용자를 향한 원망(금지)은 다르다.

import type { ChatSlot, Personality } from '../lib/pet';
import { CHAT_MAX_CHARS } from './rules';

export interface PersonalityGroup {
  key: Personality;
  label: string;
  /** 온보딩 카드에 붙는 한 줄. */
  hint: string;
}

/** 성격 그룹 5개(§16 기본값 온순·활발·수줍음·응석·시크). 이름만 바꿀 수 있고 개수는 5 고정. */
export const PERSONALITY_GROUPS: readonly PersonalityGroup[] = [
  { key: 'GENTLE', label: '온순', hint: '차분하고 다정하게 말해요' },
  { key: 'LIVELY', label: '활발', hint: '신나서 먼저 말을 걸어요' },
  { key: 'SHY', label: '수줍음', hint: '조심스럽게 조금씩 말해요' },
  { key: 'CLINGY', label: '응석', hint: '붙어 있고 싶어 해요' },
  { key: 'COOL', label: '시크', hint: '짧게, 그래도 속은 따뜻해요' },
];

export function personalityLabel(key: Personality | null): string {
  return PERSONALITY_GROUPS.find((g) => g.key === key)?.label ?? '';
}

/**
 * 원망 문장 금지 패턴(출력 단계 강제, §16). 사용자를 탓하거나 부재를 책망하는 꼴.
 * 새 패턴을 넣을 때는 "스스로 슬픈 말" 이 걸리지 않는지 SAFE_LINES 로 같이 확인한다.
 */
export const BANNED_PATTERNS: readonly RegExp[] = [
  /왜\s*(안|이제야|늦게|이렇게)/,
  /안\s*(왔|와요|와서|오고|오면)/,
  /(버렸|버리고|버릴)/,
  /기다렸(는데|잖아)/,
  /혼자\s*(두|뒀|남겨)/,
  /(서운|섭섭)/,
  /미워/,
  /(잊었|잊어버렸)/,
  /(어디\s*갔|어디\s*있었)/,
  /(무심|매정|나쁜\s*(사람|주인))/,
  /(너|당신|주인)\s*(때문|탓)/,
];

/** 이 줄이 원망 문장인가. */
export function isBanned(line: string): boolean {
  return BANNED_PATTERNS.some((re) => re.test(line));
}

/** 걸렸을 때 대신 내보내는 안전한 한 줄. 부재를 전제하지 않는다. */
export const SAFE_FALLBACK = '와 줘서 좋아요';

/** 서버 대사를 화면에 내보내기 직전 한 번 더 거른다. 통과하면 그대로, 걸리면 SAFE_FALLBACK. */
export function sanitizeLine(line: string | null | undefined): string {
  if (!line) return '';
  return isBanned(line) ? SAFE_FALLBACK : line;
}

/** 입력 40자 제한. 넘으면 자른다(서버도 400 을 내지만 화면이 먼저 막는다). */
export function clampChat(text: string): string {
  return Array.from(text.trim()).slice(0, CHAT_MAX_CHARS).join('');
}

// ── 테스트용 템플릿(목 서버) ───────────────────────────────────────────────
//
// 실서버는 chat.mode=template 로 같은 구조(5그룹 × 3슬롯 × 3벌 + 반응 3벌)를 갖는다(플랜 "기획 구멍").
// 여기 문장은 세션 초안이고 상훈님 톤 리뷰 전이다. 원망 패턴은 위 필터가 잡는다.

const CALL: Record<ChatSlot, Record<Personality, string[]>> = {
  BABY: {
    GENTLE: ['안녕하세요… 여기가 어디예요?', '처음 보는 곳이에요', '당신이 저를 데려온 사람이에요?'],
    LIVELY: ['우와! 여기 뭐예요?', '안녕! 나 지금 태어났어!', '같이 놀 거예요?'],
    SHY: ['…안녕하세요', '여기… 있어도 돼요?', '조금 무서워요'],
    CLINGY: ['옆에 있어 줄 거죠?', '안녕! 오늘 계속 같이 있어요', '저 여기 있어요, 봐 줘요'],
    COOL: ['…왔네', '여긴 어디지', '뭐, 나쁘지 않네'],
  },
  MORNING: {
    GENTLE: ['잘 잤어요? 오늘은 뭐 할 거예요?', '아침이에요. 날씨가 좋으면 좋겠어요', '오늘 하루도 잘 부탁해요'],
    LIVELY: ['일어났다! 오늘 뭐 하고 놀아요?', '아침이야! 배고파!', '오늘은 신나는 일이 있을 것 같아!'],
    SHY: ['…좋은 아침이에요', '잘 잤어요…?', '오늘도… 같이 있어요?'],
    CLINGY: ['일어나자마자 당신 생각했어요', '오늘 몇 번 올 거예요?', '아침 인사 해 줘요'],
    COOL: ['아침이네', '일어났어', '오늘 계획은?'],
  },
  NOON: {
    GENTLE: ['점심은 드셨어요?', '지금 뭐 하고 있어요?', '오후엔 좀 쉬어요'],
    LIVELY: ['오후다! 심심해!', '지금 밖은 어때요?', '뭐 재밌는 일 없어요?'],
    SHY: ['…바빠요?', '방해한 건 아니죠…?', '조금 심심했어요'],
    CLINGY: ['보고 싶었어요', '지금 뭐 해요? 나도 같이', '오후에도 같이 있어요'],
    COOL: ['오후네', '뭐 해', '심심하진 않아'],
  },
  EVENING: {
    GENTLE: ['저녁이에요. 오늘 어땠어요?', '슬슬 졸려요', '오늘도 고마웠어요'],
    LIVELY: ['저녁이야! 오늘 뭐가 제일 재밌었어요?', '아직 안 졸려!', '내일은 뭐 해요?'],
    SHY: ['…오늘 하루 어땠어요?', '저녁이에요', '조금 졸려요'],
    CLINGY: ['저녁이에요, 옆에 있어 줘요', '재워 줄 거죠?', '오늘 제일 좋았던 건 당신이에요'],
    COOL: ['저녁이네', '오늘 어땠어', '슬슬 잘까'],
  },
};

const REPLY: Record<Personality, string[]> = {
  GENTLE: ['그렇군요. 얘기해 줘서 고마워요', '음, 그런 날도 있죠', '기억해 둘게요'],
  LIVELY: ['우와, 진짜요?', '나도 그거 좋아해!', '다음에 또 얘기해 줘요!'],
  SHY: ['…그렇구나', '고, 고마워요', '조금 알 것 같아요'],
  CLINGY: ['더 얘기해 줘요!', '나한테만 말해 준 거죠?', '기억할게요, 꼭'],
  COOL: ['그래', '흠, 알겠어', '나쁘지 않네'],
};

const REACTION: Record<Personality, string> = {
  GENTLE: 'nod', LIVELY: 'wave', SHY: 'shy', CLINGY: 'shy', COOL: 'tilt',
};

/** 결정적 선택 — 테스트가 같은 입력에 같은 결과를 보게 시드로 고른다. */
function pickBy<T>(xs: readonly T[], seed: number): T {
  return xs[Math.abs(seed) % xs.length];
}

/** 캐릭터가 먼저 건네는 한 줄(목 서버용). 원망 필터를 통과한 것만 나간다. */
export function templateCall(slot: ChatSlot, personality: Personality | null, seed: number): string {
  return sanitizeLine(pickBy(CALL[slot][personality ?? 'GENTLE'], seed));
}

/**
 * 답에 대한 대사 1줄 + 반응 동작 1개(목 서버용). memory 가 있으면 최근 답을 한 번 재언급한다(§10 기억).
 */
export function templateReply(
  personality: Personality | null,
  text: string,
  memory: readonly string[],
  seed: number,
): { reply: string; reactionKey: string } {
  const p = personality ?? 'GENTLE';
  let reply = pickBy(REPLY[p], seed + text.length);
  const last = memory[memory.length - 1];
  if (last && seed % 3 === 0) reply = `${reply} 아까 "${last}" 라고 했었죠`;
  return { reply: sanitizeLine(reply), reactionKey: REACTION[p] };
}
