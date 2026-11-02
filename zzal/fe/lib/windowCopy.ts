// 재우기·깨우기 창을 말하는 문구 — 아이의 시간대로 다시 쓴다(2026-10-26).
//
// ★ 한국 사용자(규칙·표시 시간대가 둘 다 서울)에게는 **null** 을 돌려준다. 부르는 쪽은 그때 옛 문구를 그대로 쓴다.
//   그래서 한국 사용자가 보는 글은 한 글자도 안 바뀐다.
// ★ 그 밖에는 서버 창(규칙 시간대 19·23·07·10시)을 **표시 시간대의 시각**으로 바꿔 말한다.
//   1단계(서버 규칙이 아직 KST)의 LA 사용자는 "오전 3시부터 재울 수 있어요(현지 시각)" 처럼 실제로 눌리는 시각을 본다.
//   2단계 뒤에는 규칙도 현지라 "오후 7시부터" 가 된다 — 이 파일은 바뀔 것이 없다.
import { ruleHourLabel, zonesAreSeoul } from './kst';
import { SLEEP_WINDOW, WAKE_WINDOW } from '../tamagotchi/rules';

type L = 'ko' | 'en' | 'ja';
export type WindowCopyKey =
  | 'wakeNote' | 'sleepFrom' | 'sleepFromShort' | 'sleepyAfter' | 'wakeShort'
  | 'restSleep' | 'restWake' | 'guideSleep' | 'napSleep' | 'napWake';

function hours(locale: L) {
  return {
    s: ruleHourLabel(SLEEP_WINDOW.from, locale),   // 재우기 창 시작(19)
    a: ruleHourLabel(SLEEP_WINDOW.to, locale),     // 자동 취침(23)
    w1: ruleHourLabel(WAKE_WINDOW.from, locale),   // 깨우기 창 시작(07)
    w2: ruleHourLabel(WAKE_WINDOW.to, locale),     // 자동 기상(10)
  };
}

const TEXT: Record<WindowCopyKey, (h: ReturnType<typeof hours>) => [string, string, string]> = {
  wakeNote: ({ w1, w2 }) => [`${w1}~${w2} 사이에 깨울 수 있어요(현지 시각).`, `You can wake them from ${w1} to ${w2}, your time.`, `現地時間の${w1}〜${w2}に起こせます。`],
  sleepFrom: ({ s }) => [`${s}부터 재울 수 있어요(현지 시각).`, `Bedtime is available from ${s}, your time.`, `現地時間の${s}から寝かせられます。`],
  sleepFromShort: ({ s }) => [`${s}부터`, `From ${s}`, `${s}から`],
  sleepyAfter: ({ s }) => [`${s}는 넘어야 졸려요`, `Gets sleepy after ${s}`, `${s}を過ぎると眠くなります`],
  wakeShort: ({ w1, w2 }) => [`${w1}~${w2}`, `${w1}–${w2}`, `${w1}〜${w2}`],
  restSleep: ({ s, a }) => [
    `${s}부터 재울 수 있고, ${a}에는 스스로 잠들어요. 현지 시각 기준이에요.`,
    `You can put them to bed from ${s}; they sleep on their own at ${a}, your time.`,
    `${s}から寝かせられ、${a}には自然に眠ります。現地時間です。`],
  restWake: ({ w1, w2 }) => [
    `${w1}부터 깨울 수 있고, ${w2}에는 스스로 일어나요. 현지 시각 기준이에요. 자는 동안에는 대화할 수 없어요.`,
    `You can wake them from ${w1}; they wake on their own at ${w2}, your time. Chat is unavailable during sleep.`,
    `${w1}から起こせて、${w2}には自然に目覚めます。現地時間です。眠っている間は会話できません。`],
  guideSleep: ({ s, a, w1, w2 }) => [
    `평소 생활 시간은 현지 시각 기준이에요. ${s}부터 ${a} 전까지 재울 수 있고, ${a}에는 자동으로 잠들어요. ${w1}부터 ${w2} 전까지 깨울 수 있고, ${w2}에는 자동으로 깨어나요. 처음 체험 중인 낮잠은 이 생활 수면과 다를 수 있으니 침실의 현재 안내를 확인해 주세요.`,
    `Daily times use your local time. You can put your character to bed from ${s} until before ${a}; they sleep automatically at ${a}. You can wake them from ${w1} until before ${w2}; they wake automatically at ${w2}. The first guided nap is separate. Check the bedroom for the current status.`,
    `生活時間は現地時間です。${s}から${a}前まで寝かせられ、${a}に自動で眠ります。${w1}から${w2}前まで起こせて、${w2}に自動で起きます。最初の体験の昼寝は別です。寝室の今の案内を確認してください。`],
  napSleep: ({ s, a }) => [
    `침실에서 잠깐 재워 보세요. 이번에는 바로 깨울 수 있어요. 평소에는 현지 시각 ${s}부터 재울 수 있고, ${a}에 자동으로 잠들어요.`,
    `Try putting your character to bed. You can wake them right away this time. Normally, bedtime is available from ${s} your time, and they sleep automatically at ${a}.`,
    `寝室で寝かせてみてください。今回はすぐに起こせます。普段は現地時間の${s}から寝かせられ、${a}に自動で眠ります。`],
  napWake: ({ w1, w2 }) => [
    `침실에서 지금 깨워 주세요. 평소에는 현지 시각 ${w1}부터 깨울 수 있고, ${w2}에 자동으로 일어나요.`,
    `Wake your character in the bedroom now. Normally, you can wake them from ${w1} your time, and they wake automatically at ${w2}.`,
    `寝室で今すぐ起こしてください。普段は現地時間の${w1}から起こせて、${w2}に自動で起きます。`],
};

/** 창 문구. 한국 사용자면 null(옛 문구를 쓴다). */
export function windowCopy(key: WindowCopyKey, locale: L): string | null {
  if (zonesAreSeoul()) return null;
  const [ko, en, ja] = TEXT[key](hours(locale));
  return locale === 'en' ? en : locale === 'ja' ? ja : ko;
}

/** uiText 가 받는 옛 한국어 원문 → 창 문구 종류. 여기 없는 글은 창 문구가 아니다. */
const UI_KEYS: Record<string, WindowCopyKey> = {
  '아침에 깨워 주세요. 07~10시엔 깨워도 돼요.': 'wakeNote',
  '저녁 7시부터 재울 수 있어요.': 'sleepFrom',
  '저녁 7시부터 재울 수 있어요': 'sleepFrom',
  '저녁 7시부터': 'sleepFromShort',
  '저녁 7시는 넘어야 졸려요': 'sleepyAfter',
  '아침 7~10시': 'wakeShort',
};

/** uiText 앞단. 창 문구이고 한국 사용자가 아니면 바꾼 글, 아니면 null. */
export function windowUiText(value: string, locale: L): string | null {
  const key = UI_KEYS[value];
  return key ? windowCopy(key, locale) : null;
}
