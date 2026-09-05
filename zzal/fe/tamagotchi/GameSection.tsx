// 미니게임 — 좌·우 맞히기. 다섯 번 겨뤄 세 번 이상 맞히면 이긴다(원조 다마고치의 그것).
//
// ★★ 이 화면은 승패를 판정하지 않는다.
//    답은 서버가 쥐고 한 판에 하나씩만 공개한다. 화면이 다섯 번을 혼자 치고 "이겼다" 만
//    보내면 개발자도구로 이겼다고 말하면 그만이고, 보상이 켜지는 순간 그게 무한 이득이 된다.
//    그래서 왕복이 다섯 번이고, 여기에는 정답을 담는 변수 자체가 없다.
//
// ★ 새 그림을 만들지 않는다 — 이미 있는 캐릭터 그림(constants 의 YEOUL_MOOD)을 그대로 쓴다.
//   디자인은 상훈님이 직접 다듬으실 자리라, 여기서는 동작이 도는 것까지만 한다.
'use client';

import { useCallback, useEffect, useState, type CSSProperties } from 'react';
import { ApiError } from '../lib/api';
import { getCurrentGame, guess as guessApi, startGame, type GameState, type GuessResult, type Side } from '../lib/game';
import { YEOUL_MOOD } from './_v1/constants';

const PEN = "'Nanum Pen Script',cursive";
const GAEGU = "'Gaegu',cursive";
const INK = '#3A352B';
const SUB = '#7E7561';
const RED = '#B4614C';
const GRN = '#7C9463';
const PAPER = '#FFFDF6';
const EDGE = '#E0D7C0';

export interface GameSectionProps {
  /** 누구와 놀 것인가. 아직 아이가 없으면 null 을 넘긴다(아무것도 안 그린다). */
  petId: number | null;
}

function messageOf(e: unknown): string {
  if (e instanceof ApiError && e.message) return e.message;
  return '연결하지 못했습니다';
}

export default function GameSection({ petId }: GameSectionProps) {
  /** 서버가 준 마지막 상태. 시작 전에도 remainingToday 를 보려고 들고 있는다. */
  const [state, setState] = useState<GameState | null>(null);
  /** 방금 친 판의 결과. 한 판마다 갈아 끼운다. */
  const [last, setLast] = useState<GuessResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ★ 새로고침 복구. 다섯 왕복이라 중간에 새로고침이 반드시 일어난다 — 이게 없으면
  //   치던 판을 다시 못 잡고, 그 판은 안 끝난 채 남아 하루 횟수만 먹는다.
  useEffect(() => {
    if (petId == null) {
      setState(null);
      setLast(null);
      return;
    }
    let alive = true;
    const controller = new AbortController();
    getCurrentGame(petId, controller.signal)
      // ★ 복구 응답은 "아직 아무 상태도 없을 때" 만 쓴다. 시작 응답(playing:true)이 먼저 왔는데 늦게 도착한
      //   복구 응답(playing:false)이 덮으면 치던 판이 화면에서 사라진다(리뷰 M2).
      .then((s) => { if (alive) setState((cur) => cur ?? s); })
      .catch((e: unknown) => {
        if (!alive) return;
        if (e instanceof DOMException && e.name === 'AbortError') return;
        setError(messageOf(e));
      });
    return () => { alive = false; controller.abort(); };
  }, [petId]);

  const start = useCallback(async () => {
    if (petId == null || busy) return;
    setBusy(true);
    setError(null);
    setLast(null);
    try {
      setState(await startGame(petId));
    } catch (e) {
      setError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }, [petId, busy]);

  const pick = useCallback(
    async (side: Side) => {
      if (petId == null || busy || !state?.playing || state.gameId == null) return;
      setBusy(true);
      setError(null);
      try {
        const r = await guessApi(petId, state.gameId, side);
        setLast(r);
        // ★ 응답이 곧 최신 상태다. 친 뒤에 다시 조회하지 않는다 — 왕복이 두 번이 되고
        //   그 사이에 값이 어긋난다(usePet 이 지키는 규칙과 같다).
        setState({
          kind: 'LEFT_RIGHT', finished: r.finished, win: r.win,
          rounds: r.rounds, winAt: r.winAt, remainingToday: r.remainingToday,
          ...(r.finished
            ? { playing: false, gameId: null, round: null, hits: null }
            : { playing: true, gameId: r.gameId, round: r.nextRound, hits: r.hits }),
        });
      } catch (e) {
        setError(messageOf(e));
      } finally {
        setBusy(false);
      }
    },
    [petId, busy, state],
  );

  // 아이가 없으면 놀 상대가 없다. 아무것도 그리지 않는다.
  if (petId == null) return null;

  const playing = !!state?.playing;
  const finished = !!last?.finished;
  const rounds = state?.rounds ?? last?.rounds ?? 5;
  const winAt = state?.winAt ?? last?.winAt ?? 3;
  // 치는 중에는 지금 판, 끝났으면 마지막 판까지 온 것.
  const round = playing ? (state?.round ?? 0) : rounds;
  const hits = playing ? (state?.hits ?? 0) : (last?.hits ?? 0);

  /** 결과 연출은 있는 그림으로만 한다 — 맞으면 기쁜 얼굴, 틀리면 시무룩. */
  const face = last == null ? YEOUL_MOOD.idle : last.hit ? YEOUL_MOOD.happy : YEOUL_MOOD.sad;

  return (
    <section data-sec="game" style={S.sec}>
      <div style={S.wrap}>
        <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', gap: 12, marginBottom: 14 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span style={{ fontFamily: PEN, fontSize: 22, color: RED, lineHeight: 1 }}>같이 놀기</span>
            <h2 style={S.h2}>좌우 맞히기</h2>
          </div>
          <span style={{ fontFamily: PEN, fontSize: 19, color: '#A79C82', whiteSpace: 'nowrap' }}>
            {rounds}번 중 {winAt}번
          </span>
        </div>

        <div style={S.paper}>
          {/* 얼굴 — 방금 친 판의 결과를 표정으로 말한다. */}
          <div style={S.stage}>
            <img src={face} alt="" aria-hidden style={{ width: 150, height: 'auto', display: 'block', objectFit: 'contain' }} />
          </div>

          {/* 지금 몇 번째인지 · 맞힌 수 */}
          <p data-game="score" style={S.score}>
            {finished
              ? `${rounds}번 중 ${hits}번 맞혔어요`
              : playing
                ? `${round + 1}번째 · 맞힌 수 ${hits}`
                : '어느 쪽으로 고개를 돌릴까요?'}
          </p>

          {/* 방금 친 판의 결과 한 줄. 답은 이 한 판의 것뿐이다. */}
          {last && (
            <p data-game="last" style={{ ...S.line, color: last.hit ? GRN : RED }}>
              {last.hit ? '맞았어요' : '빗나갔어요'} — 나 {label(last.pick)} · 답 {label(last.answer)}
            </p>
          )}

          {finished && (
            <p data-game="result" style={{ ...S.result, color: last?.win ? GRN : SUB }}>
              {last?.win ? '이겼어요!' : '졌어요…'}
            </p>
          )}

          {/* 좌·우 두 개. 치는 중에만 누를 수 있다. */}
          {playing ? (
            <div style={S.btnRow}>
              <button data-action="game-left" onClick={() => void pick('LEFT')} disabled={busy} style={S.sideBtn(busy)}>
                왼쪽
              </button>
              <button data-action="game-right" onClick={() => void pick('RIGHT')} disabled={busy} style={S.sideBtn(busy)}>
                오른쪽
              </button>
            </div>
          ) : (
            <button data-action="game-start" onClick={() => void start()} disabled={busy} style={S.startBtn(busy)}>
              {busy ? '여는 중…' : finished ? '한 판 더' : '놀아주기'}
            </button>
          )}

          <p style={S.hint}>
            오늘 {state?.remainingToday ?? 0}판 더 놀 수 있어요
          </p>

          {error && <p data-game="error" style={{ ...S.line, color: RED }}>{error}</p>}
        </div>
      </div>
    </section>
  );
}

function label(side: Side): string {
  return side === 'LEFT' ? '왼쪽' : '오른쪽';
}

const S = {
  sec: { scrollSnapAlign: 'start', padding: '30px 18px 38px' } as CSSProperties,
  wrap: { width: '100%', maxWidth: 1120, margin: '0 auto' } as CSSProperties,
  h2: { margin: 0, fontFamily: GAEGU, fontWeight: 700, fontSize: 22, color: INK } as CSSProperties,
  paper: {
    position: 'relative', background: PAPER, border: '1px solid ' + EDGE, borderRadius: 4,
    padding: '19px 15px', boxShadow: '3px 4px 0 rgba(58,53,43,.08), 0 1px 0 #fff inset',
    display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10,
  } as CSSProperties,
  stage: {
    display: 'flex', alignItems: 'flex-end', justifyContent: 'center',
    minHeight: 160, width: '100%',
  } as CSSProperties,
  score: { margin: 0, fontFamily: GAEGU, fontWeight: 700, fontSize: 18, color: INK } as CSSProperties,
  line: { margin: 0, fontSize: 13 } as CSSProperties,
  result: { margin: 0, fontFamily: GAEGU, fontWeight: 700, fontSize: 21 } as CSSProperties,
  hint: { margin: 0, fontSize: 12, color: '#A79C82' } as CSSProperties,
  btnRow: { display: 'flex', gap: 10, width: '100%', maxWidth: 320 } as CSSProperties,
  sideBtn: (busy: boolean): CSSProperties => ({
    flex: 1, minHeight: 54, border: '1px solid ' + EDGE, borderRadius: 3,
    background: busy ? 'rgba(230,224,206,.8)' : PAPER, color: busy ? '#A79C82' : INK,
    fontFamily: GAEGU, fontWeight: 700, fontSize: 17,
    cursor: busy ? 'default' : 'pointer', boxShadow: '2px 3px 0 rgba(58,53,43,.12)',
  }),
  startBtn: (busy: boolean): CSSProperties => ({
    width: '100%', maxWidth: 320, minHeight: 54,
    border: '1px solid ' + (busy ? '#DCD2B8' : '#2F2A22'), borderRadius: 3,
    background: busy ? 'rgba(230,224,206,.8)' : INK, color: busy ? '#A79C82' : '#FFF8EC',
    fontFamily: GAEGU, fontWeight: 700, fontSize: 17,
    cursor: busy ? 'default' : 'pointer',
  }),
};
