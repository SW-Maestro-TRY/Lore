/**
 * 캐릭터 카드의 순수 규칙. 화면(CharacterCard.tsx)과 시험(tests/character-card.test.cjs)이 같이 읽는다.
 *
 * ★ 글자 수는 서버와 같은 셈(`String.length`, UTF-16 단위)이다. 이모지 하나가 2로 셀 수 있지만
 *   서버 `@Size`와 어긋나지 않는 쪽을 골랐다(명세 4절).
 * ★ 여기에는 React·브라우저·다른 모듈 import를 두지 않는다. 시험이 transpile+vm으로 바로 돌린다.
 */

/** 서버 `ZzalRules.NAME_MAX_CHARS`. */
export const NAME_MAX = 12;
/** 서버 호칭 `@Size(max=20)`. */
export const CALL_ME_MAX = 20;
/** 앞면 대표 칩 최대 개수. */
export const CHIP_MAX = 4;

/**
 * 그림 자르기(1단계). 투명하지 않은 영역의 위쪽 끝에서 캐릭터 키의 `CROP_VISIBLE` 만큼이 그림 칸 높이를 채운다.
 * `CROP_HEAD_ROOM`은 머리 위에 남기는 여백(칸 높이 대비).
 * 측정 실패(`measured=false`) 때는 `object-fit: cover` + 위쪽 `CROP_FALLBACK_Y` 기준으로 버틴다.
 */
export const CROP_VISIBLE = 0.55;
export const CROP_HEAD_ROOM = 0.06;
export const CROP_FALLBACK_Y = '15%';

type FieldId = 'world' | 'job' | 'persona' | 'likes' | 'dislikes' | 'extra';
export interface CardSource {
  authoredPersona: { fields: Array<{ fieldId: FieldId | string; chips: string[] }> } | null;
  legacySource?: { personalities?: string[] | null } | null;
}

/** 칸마다 기본 몫. 모자란 자리는 이 순서대로 다음 칸에서 채운다. */
const CHIP_PLAN: Array<[string, number]> = [['world', 1], ['job', 1], ['persona', 2]];

/**
 * 대표 칩: 세계 1 → 직업 1 → 성격 2. 어느 칸이 모자라면 남은 자리를 다음 칸(그다음 앞 칸의 남은 칩)에서 채운다.
 * 좋아하는 것·싫어하는 것·그 밖에는 앞면에 올리지 않는다. 원문이 없는 옛 펫은 옛 성격 값을 쓴다.
 * 돌려주는 값은 원래 칩 값(화면에서 `personaChipLabel`로 바꾼다).
 */
export function pickRepresentativeChips(source: CardSource | null | undefined): string[] {
  if (!source) return [];
  if (!source.authoredPersona) {
    return (source.legacySource?.personalities ?? []).filter(v => typeof v === 'string' && v.trim()).slice(0, CHIP_MAX);
  }
  const pools = CHIP_PLAN.map(([id, quota]) => ({
    quota,
    chips: [...new Set((source.authoredPersona!.fields.find(f => f.fieldId === id)?.chips ?? []).filter(c => c && c.trim()))],
    used: 0,
  }));
  const picked: string[] = [];
  // 1차: 칸마다 제 몫만큼(다른 칸에 같은 칩이 이미 있으면 건너뛰고 그 칸의 다음 칩).
  for (const pool of pools) {
    let took = 0;
    while (took < pool.quota && pool.used < pool.chips.length && picked.length < CHIP_MAX) {
      const chip = pool.chips[pool.used++];
      if (!picked.includes(chip)) { picked.push(chip); took++; }
    }
  }
  // 2차: 빈자리를 앞 칸부터 남은 칩으로 채운다(세계가 비면 직업·성격에서 더 가져옴).
  for (const pool of pools) while (picked.length < CHIP_MAX && pool.used < pool.chips.length) {
    const chip = pool.chips[pool.used++];
    if (!picked.includes(chip)) picked.push(chip);
  }
  return picked;
}

export type NameError = 'empty' | 'tooLong' | 'unchanged';
export type CallMeError = NameError | 'multiple' | 'nameless';

/** 이름 입력 검사. 앞뒤 공백은 서버가 지우므로 같은 기준으로 본다. */
export function validateName(value: string, current: string | null | undefined): NameError | null {
  const v = value.trim();
  if (!v) return 'empty';
  if (v.length > NAME_MAX) return 'tooLong';
  if (v === (current ?? '').trim()) return 'unchanged';
  return null;
}

/** "이름 없이"·"이름없이"(가운데 공백 무시). 서버 거절 규칙과 같은 값. */
export function isNamelessCallMe(value: string): boolean {
  return value.replace(/\s+/g, '') === '이름없이';
}

/** 호칭 입력 검사. "/"·전각 "／"는 여러 개를 한 칸에 적은 것으로 보고 막는다(채팅이 그 글자 그대로 부르게 됨). */
export function validateCallMe(value: string, current: string | null | undefined): CallMeError | null {
  const v = value.trim();
  if (!v) return 'empty';
  if (v.length > CALL_ME_MAX) return 'tooLong';
  if (v.includes('/') || v.includes('\uFF0F')) return 'multiple';
  if (isNamelessCallMe(v)) return 'nameless';
  if (v === (current ?? '').trim()) return 'unchanged';
  return null;
}
