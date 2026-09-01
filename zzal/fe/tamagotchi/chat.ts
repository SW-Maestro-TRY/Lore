// 아이에게 말을 걸었을 때 무엇이라고 답하는가.
//
// 출처 = Claude Design 시안 'Cream Minimal v2'(2026-08-31). 시안의 answerFor 를
// 우리 상태(포만감·행복·잠·해금)에 맞춰 옮겼다.
//
// ★대사를 한 파일에 모아 둔 이유 — 이건 캐릭터의 성격을 정하는 글이라
//   화면 코드 사이사이에 흩어 두면 나중에 톤을 손볼 때 다 찾지 못한다.
//   말투를 바꾸고 싶으면 이 파일만 고치면 된다.
//
// ⚠️ 자캐 커뮤니티에는 "남이 내 캐릭터의 성격·감정을 마음대로 정하는 것"을
//    침해로 보는 규범이 있다(캐조종·캐붕). 아래 LATE 처럼 사용자가 안 온 것을
//    전제하는 말은 그 선에 가장 가까우니, 톤을 손볼 때 여기부터 보면 된다.

export interface ChatContext {
  /** 아이 이름. */
  name: string;
  /** 0~5. */
  fullness: number;
  /** 0~5. */
  happiness: number;
  sleeping: boolean;
  training: boolean;
  /** 바닥에 쌓인 쓰레기 0~5. */
  trash: number;
  /** 지금까지 배운 동작 수. */
  unlocked: number;
}

/** 아무 규칙에도 안 걸렸을 때. 매번 같은 말이 나오면 대화가 죽는다. */
const FALLBACK = [
  '그렇구나! 더 얘기해줘요',
  '와, 신기해요',
  '오늘도 같이 있어줘서 좋아요',
  '음… 그건 처음 들었어요!',
];

const SLEEPING = ['쿨쿨… 조금만 더 잘게요', '음냐… 나중에 얘기해요'];

/** ⚠️ 안 온 것을 전제하는 말. 톤을 손볼 때 가장 먼저 볼 자리. */
const LATE = '괜찮아요, 와줘서 좋아요';

const pick = <T,>(xs: readonly T[]) => xs[Math.floor(Math.random() * xs.length)];

/**
 * 무엇이라고 답할지 정한다. 부수 효과 없음 — 행복이 오르는 것 같은 일은
 * 이 함수가 아니라 부르는 쪽(useTamagotchi)이 한다.
 *
 * `pet` 이 true 로 돌아오면 쓰다듬은 것과 같은 반응을 함께 낸다.
 */
export function answerFor(msg: string, c: ChatContext): { text: string; pet?: boolean } {
  const m = msg.toLowerCase();
  const has = (...ws: string[]) => ws.some(w => m.indexOf(w) >= 0);

  if (c.sleeping) return { text: pick(SLEEPING) };

  if (has('안녕', '하이', 'hi', 'hello')) return { text: '안녕! 기다렸어요' };
  if (has('사랑', '좋아', '귀여', '이뻐', '예뻐')) return { text: '헤헤… 저도 좋아해요', pet: true };
  if (has('이름', '누구')) return { text: `저는 ${c.name}이에요!` };
  if (has('배고', '밥', '먹')) {
    return { text: c.fullness <= 1 ? '사실 조금 배고파요…' : '방금 먹었어요! 배불러요' };
  }
  if (has('훈련', '연습', '놀', '심심')) {
    return { text: c.training ? '지금 연습하는 중이에요!' : '같이 연습해요! 준비 됐어요' };
  }
  if (has('졸', '잠', '자자', '굿나잇')) {
    return { text: '불 꺼주면 잘게요. 자는 동안 새로 하나 배워올게요' };
  }
  if (has('더러', '치우', '청소', '지저분')) {
    return { text: c.trash >= 3 ? '바닥이 좀… 치워주면 좋겠어요' : '지금은 깨끗해요!' };
  }
  if (has('뭐 배', '배운', '뭐 할 줄', '할 줄')) {
    return { text: c.unlocked > 0 ? `지금까지 ${c.unlocked}가지 배웠어요!` : '아직 배운 게 없어요. 같이 연습해요' };
  }
  if (has('기분', '어때', '괜찮')) {
    return { text: c.happiness >= 4 ? '오늘 기분 최고예요!' : '조금 심심했어요…' };
  }
  if (has('뭐 해', '뭐하', '뭐 하')) return { text: '방 안 산책하고 있었어요' };
  if (has('고마', '땡큐')) return { text: '제가 더 고마워요!' };
  if (has('미안', '늦었')) return { text: LATE };
  if (has('?', '뭐', '왜', '어디')) return { text: '음… 잘 모르겠어요! 같이 알아볼까요?' };

  return { text: pick(FALLBACK) };
}
