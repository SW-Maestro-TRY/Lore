// 후기 — 별점·칩·자유 글 한 장. 스크랩북 결에 맞춘 쪽지 한 장으로 올라온다.
//
// ★★ 구 랜딩(sections/CharacterCreator.tsx L.825~)의 후기 UI 를 옮기지 않고 새로 썼다.
//    그쪽은 그 화면의 CSS 변수(--ink · --accent · --card · --tape)에 묶여 있어서, 여기로
//    가져오면 변수부터 같이 옮겨야 하고 그러면 스크랩북 톤과 어긋난 채로 두 벌이 생긴다.
//    옮겨서 톤을 맞추는 시간이 새로 쓰는 시간보다 길다. 별점 다섯 개·칩 여섯 개·글 칸·버튼이 전부다.
//    ★ 이메일 칸은 가져오지 않았다 — 가입할 때 이미 받았다(lib/feedback.ts 머리말 참고).
//
// ★★ "무엇을 드립니다" 를 쓰지 않는다.
//    무엇을 줄지 아직 안 정해졌고, 서버의 보상 설정은 지금 NONE 이라 실제로 아무것도 안 나간다.
//    구 랜딩은 "(생성 1회 추가)" 라고 적어 두었는데, 지금 그대로 쓰면 지키지 않는 약속이 된다.
//
// ★ 디자인은 상훈님이 직접 다듬으실 자리다. 여기서는 동작이 도는 것까지만 한다.
'use client';

import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react';
import { track } from '@common/analytics';
import { ApiError } from '../lib/api';
import { getMyFeedback, submitFeedback, type FeedbackTag } from '../lib/feedback';

const PEN = "'Nanum Pen Script',cursive";
const GAEGU = "'Gaegu',cursive";
const INK = '#3A352B';
const SUB = '#7E7561';
const RED = '#B4614C';
const PAPER = '#FFFDF6';
const EDGE = '#E0D7C0';

/** 자유 글 상한. 서버의 @Size(max = 500) 과 같은 값이다 — 다르면 화면이 통과시킨 글이 400 이 된다. */
const MAX_TEXT = 500;

/**
 * 칩 목록. 값은 서버가 정본이고 여기 있는 것은 화면에 쓸 말이다.
 *
 * ★ 좋다·아쉽다를 칩 안에 담았다. 구 랜딩은 {@code 그림체 · 대사 · 컷 구성 · 속도} 처럼
 *   주제만 있어서, "그림체" 를 고른 사람이 칭찬한 것인지 불만인지 알 수 없었다.
 * ★ 여섯인 이유 — 지금 파는 것이 "내 그림이 그대로 움직이는 것" 이라 (1) 그림이 보존됐는가
 *   (2) 움직임이 자연스러운가 두 축을 양쪽으로 두고, 나머지 둘로 기다림과 다음 요구를 받는다.
 *   구 랜딩의 대사·컷 구성은 웹툰의 칸이라 여기에 없다.
 */
const CHIPS: { tag: FeedbackTag; label: string }[] = [
  { tag: 'LOOKS_SAME', label: '내 그림 그대로예요' },
  { tag: 'MOTION_GOOD', label: '움직임이 자연스러워요' },
  { tag: 'LOOKS_OFF', label: '캐릭터가 안 닮았어요' },
  { tag: 'MOTION_ODD', label: '움직임이 어색해요' },
  { tag: 'TOO_SLOW', label: '기다리는 시간이 길어요' },
  { tag: 'WANT_MORE', label: '동작이 더 다양했으면' },
];

export interface FeedbackSheetProps {
  /** 어느 아이에 대한 후기인가. 없으면(비로그인·아직 아이 없음) 아무것도 안 그린다. */
  petId: number | null;
  /**
   * 지금까지 연 동작 수. **1 이상이 되면 한 번 저절로 올라온다.**
   *
   * ★ 왜 하필 그때인가 — 상훈님이 2026-08-25 에 "첫 해금 직후 좋다" 로 확정하셨다.
   *   결과물을 아직 못 본 사람에게 결과물의 후기를 물으면 답할 것이 없다.
   */
  unlocked: number;
  /**
   * 지금 다른 것이 화면을 덮고 있는가(해금 축하 판).
   *
   * ★ 이게 없으면 축하 판 위에 후기 판이 겹쳐 뜬다. 해금은 이 서비스의 두 번째 심장이라
   *   그 순간을 가리면 안 된다 — 사용자가 축하를 닫은 뒤에 올라온다.
   */
  hold?: boolean;
}

/** 이 브라우저에서 이 아이에게 이미 저절로 띄웠는지. 새로고침마다 다시 뜨는 것을 막는다. */
function askedKey(petId: number): string {
  return `zzal_fb_asked_${petId}`;
}

function wasAsked(petId: number): boolean {
  try {
    return window.localStorage.getItem(askedKey(petId)) === '1';
  } catch {
    // 사생활 보호 모드 등에서 저장소 접근 자체가 예외를 던진다. 그때는 "안 물어봤다" 로 본다.
    return false;
  }
}

function markAsked(petId: number): void {
  try {
    window.localStorage.setItem(askedKey(petId), '1');
  } catch {
    // 기억하지 못할 뿐이다. 화면이 멈추면 안 된다.
  }
}

function codeOf(e: unknown): string {
  return e instanceof ApiError && e.code ? e.code : 'UNKNOWN';
}

function messageOf(e: unknown): string {
  if (e instanceof ApiError && e.message) return e.message;
  return '보내지 못했습니다';
}

export default function FeedbackSheet({ petId, unlocked, hold = false }: FeedbackSheetProps) {
  /** 서버가 아는 사실 — 이미 냈는가. null 이면 아직 못 물어봤다. */
  const [submitted, setSubmitted] = useState<boolean | null>(null);
  const [open, setOpen] = useState(false);
  /** 방금 이 자리에서 냈는가. 고맙다는 말을 띄울지 정한다(다시 들어온 사람에게는 안 띄운다). */
  const [justSent, setJustSent] = useState(false);

  const [rating, setRating] = useState(0);
  const [tags, setTags] = useState<FeedbackTag[]>([]);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /** 어디서 열렸나. 닫힘 기록에 같은 값을 실어 열림과 짝을 맞춘다. */
  const from = useRef<'unlock' | 'dex'>('dex');

  // 이미 냈는지 물어본다. ★ 이 한 번이 "이미 낸 사람에게 또 띄우지 않는다" 를 지킨다.
  useEffect(() => {
    if (petId == null) {
      setSubmitted(null);
      return;
    }
    let alive = true;
    const controller = new AbortController();
    getMyFeedback(petId, controller.signal)
      .then((f) => { if (alive) setSubmitted(f.submitted); })
      .catch(() => {
        // ★ 화면에 에러를 띄우지 않는다. 후기는 곁다리라, 못 읽었다고 다마고치 화면 전체에
        //   경고를 올리면 정작 할 수 있는 일까지 방해한다. 대신 **안 띄운다** —
        //   모르는 채로 띄우면 이미 낸 사람에게 두 번 묻게 되고 그게 더 나쁘다.
        if (alive) setSubmitted(true);
      });
    return () => { alive = false; controller.abort(); };
  }, [petId]);

  // 첫 동작을 얻은 직후 한 번. 축하 판이 떠 있는 동안에는 기다린다.
  useEffect(() => {
    if (petId == null || submitted !== false || open || hold) return;
    if (unlocked < 1 || wasAsked(petId)) return;
    markAsked(petId);
    from.current = 'unlock';
    setOpen(true);
    track('zzal_feedback_opened', { from: 'unlock' });
  }, [petId, submitted, open, hold, unlocked]);

  const openFromDex = useCallback(() => {
    if (petId == null) return;
    // 손으로 연 것도 "물어봤다" 로 친다 — 닫고 새로고침했을 때 또 저절로 뜨면 성가시다.
    markAsked(petId);
    from.current = 'dex';
    setOpen(true);
    setError(null);
    track('zzal_feedback_opened', { from: 'dex' });
  }, [petId]);

  const close = useCallback(() => {
    setOpen(false);
    track('zzal_feedback_closed', { from: from.current });
  }, []);

  const toggle = useCallback((tag: FeedbackTag) => {
    setTags((prev) => (prev.includes(tag) ? prev.filter((t) => t !== tag) : [...prev, tag]));
  }, []);

  const send = useCallback(async () => {
    if (petId == null || busy || rating < 1) return;
    setBusy(true);
    setError(null);
    try {
      await submitFeedback(petId, { rating, tags, text });
      setSubmitted(true);
      setJustSent(true);
      // ★★ 사용자가 쓴 글(text)은 담지 않는다. 길이만 남긴다.
      //    별점·칩은 값의 가짓수가 정해져 있어 담아도 되지만, 자유 글은 무엇이 적혀 있을지
      //    알 수 없다. 서버가 화이트리스트로 거르기는 하지만(AnalyticsService), 버려질 값이
      //    네트워크를 타고 나가서 좋을 것이 하나도 없다.
      //  · stars = 별점 · type = 고른 칩들 · count = **쓴 글의 길이**(내용이 아니다)
      //    ★ props 키는 서버 화이트리스트에 있는 것만 저장된다. "길이" 를 뜻하는 키가 없어
      //      count 를 빌려 썼다(여섯 개를 다 골라도 type 은 62자라 64자 상한 안이다).
      track('zzal_feedback_submitted', { stars: rating, type: tags.join(','), count: text.trim().length });
    } catch (e) {
      // ★ 실패는 코드만 남긴다. 서버 문구는 바뀌지만 코드는 안 바뀌고, 문구에는 무엇이
      //   실려 있을지 알 수 없다.
      track('zzal_feedback_failed', { code: codeOf(e) });
      // 이미 낸 사람이 다른 탭에서 또 보낸 경우다. 실패로 두면 영영 못 닫는 칸이 된다.
      if (e instanceof ApiError && e.code === 'ZZAL_FEEDBACK_ALREADY_SUBMITTED') {
        setSubmitted(true);
      }
      setError(messageOf(e));
    } finally {
      setBusy(false);
    }
  }, [petId, busy, rating, tags, text]);

  // 아이가 없거나, 아직 못 물어봤거나, 이미 낸 사람에게는 아무것도 안 그린다.
  if (petId == null || submitted === null) return null;
  if (submitted && !open) return null;

  return (
    <>
      {/* 도감 구석의 작은 상시 링크. 첫 판을 닫은 사람이 나중에 다시 찾을 유일한 길이다. */}
      {!submitted && (
        <button data-action="feedback-open" onClick={openFromDex} style={S.link}>
          후기 남기기
        </button>
      )}

      {open && (
        <div style={S.overlay}>
          <div onClick={close} style={S.dim} />
          <div style={S.card} role="dialog" aria-label="후기 남기기">
            <span style={S.tape} />

            {justSent ? (
              <div style={{ textAlign: 'center' }}>
                <p style={{ margin: '4px 0 8px', fontFamily: PEN, fontSize: 21, color: SUB }}>고마워요</p>
                {/* ★ 여기에 "무엇을 드립니다" 를 쓰지 않는다. 지금은 아무것도 안 나간다. */}
                <p style={{ margin: '0 0 16px', fontFamily: GAEGU, fontWeight: 700, fontSize: 18, color: INK, lineHeight: 1.5 }}>
                  잘 읽고 다음 아이에 반영할게요
                </p>
                <button data-action="feedback-done" onClick={close} style={S.send(false)}>닫기</button>
              </div>
            ) : (
              <>
                <p style={{ margin: '0 0 2px', fontFamily: PEN, fontSize: 20, color: RED, lineHeight: 1 }}>한 장만</p>
                <h3 style={{ margin: '0 0 14px', fontFamily: GAEGU, fontWeight: 700, fontSize: 21, color: INK }}>
                  받은 움직임, 어땠어요?
                </h3>

                <span style={S.label}>별점</span>
                <div style={{ display: 'flex', gap: 4, margin: '6px 0 16px' }}>
                  {[1, 2, 3, 4, 5].map((n) => (
                    <button
                      key={n}
                      data-action={`feedback-star-${n}`}
                      onClick={() => setRating(n)}
                      aria-label={`별점 ${n}점`}
                      style={S.star(n <= rating)}
                    >
                      ★
                    </button>
                  ))}
                </div>

                <span style={S.label}>
                  이런 점은 어땠나요 <span style={{ fontSize: 12, color: '#A79C82' }}>(여러 개)</span>
                </span>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 7, margin: '8px 0 16px' }}>
                  {CHIPS.map((c) => (
                    <button
                      key={c.tag}
                      data-action={`feedback-chip-${c.tag}`}
                      onClick={() => toggle(c.tag)}
                      aria-pressed={tags.includes(c.tag)}
                      style={S.chip(tags.includes(c.tag))}
                    >
                      {c.label}
                    </button>
                  ))}
                </div>

                <span style={S.label}>하고 싶은 말 (선택)</span>
                <textarea
                  data-action="feedback-text"
                  value={text}
                  onChange={(e) => setText(e.target.value.slice(0, MAX_TEXT))}
                  rows={3}
                  placeholder="어떤 점이 좋았는지, 뭐가 아쉬웠는지 편하게 적어주세요"
                  style={S.textarea}
                />
                <span style={{ alignSelf: 'flex-end', marginTop: 5, fontFamily: "'Nanum Gothic Coding',monospace", fontSize: 11, color: '#A79C82' }}>
                  {text.length} / {MAX_TEXT}
                </span>

                {error && <p style={S.error}>{error}</p>}

                <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
                  <button data-action="feedback-close" onClick={close} style={S.ghost}>나중에</button>
                  {/* 별점 없이는 보낼 수 없다. 누를 수 있을 때는 aria-disabled 를 아예 붙이지
                      않는다 — "false" 를 비활성으로 읽는 도구가 있다(스크랩북의 돌봄 버튼과 같은 규칙). */}
                  <button
                    data-action="feedback-submit"
                    onClick={() => void send()}
                    disabled={busy || rating < 1}
                    aria-disabled={busy || rating < 1 ? true : undefined}
                    style={S.send(busy || rating < 1)}
                  >
                    {busy ? '보내는 중…' : '보내기'}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}

const S = {
  link: {
    border: 'none', background: 'none', padding: 0, cursor: 'pointer',
    fontFamily: PEN, fontSize: 18, color: SUB, textDecoration: 'underline',
    textUnderlineOffset: 3, textDecorationStyle: 'dotted',
  } as CSSProperties,
  overlay: {
    position: 'fixed', inset: 0, zIndex: 70,
    display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20,
  } as CSSProperties,
  dim: { position: 'absolute', inset: 0, background: 'rgba(58,53,43,.5)' } as CSSProperties,
  card: {
    position: 'relative', width: '100%', maxWidth: 360, maxHeight: '86%', overflowY: 'auto',
    display: 'flex', flexDirection: 'column',
    background: PAPER, border: '1px solid ' + EDGE, borderRadius: 4,
    padding: '22px 18px 18px', boxShadow: '4px 6px 0 rgba(58,53,43,.22)',
    animation: 'tamaRiseIn .3s ease-out both',
  } as CSSProperties,
  /*
   * ★ 카드 밖(top: -11)으로 빼지 않는다. 이 카드는 내용이 길면 스스로 스크롤해서
   *   overflow 가 auto 인데, 그러면 밖으로 나간 만큼이 잘려 테이프가 반만 보인다.
   *   스크랩북의 다른 종이들은 스크롤하지 않아 밖으로 뺄 수 있었다.
   */
  tape: {
    position: 'absolute', top: 0, left: '50%', marginLeft: -34, width: 68, height: 21,
    background: 'linear-gradient(180deg, rgba(226,208,160,.72), rgba(214,193,142,.62))',
    borderLeft: '1px solid rgba(196,175,124,.5)', borderRight: '1px solid rgba(196,175,124,.5)',
    transform: 'rotate(-2deg)',
  } as CSSProperties,
  label: { fontFamily: GAEGU, fontWeight: 700, fontSize: 15, color: '#5C5445' } as CSSProperties,
  star: (on: boolean): CSSProperties => ({
    border: 'none', background: 'none', padding: '0 2px', cursor: 'pointer',
    fontSize: 30, lineHeight: 1, color: on ? '#E0A93F' : '#DCD2B8', transition: 'color .12s',
  }),
  chip: (on: boolean): CSSProperties => ({
    minHeight: 38, padding: '0 12px', borderRadius: 3,
    border: '1px solid ' + (on ? '#A2543F' : EDGE),
    background: on ? RED : PAPER, color: on ? '#FFF8EC' : INK,
    fontFamily: GAEGU, fontWeight: 700, fontSize: 14, cursor: 'pointer',
    boxShadow: '2px 2px 0 rgba(58,53,43,.09)',
  }),
  textarea: {
    marginTop: 8, padding: '10px 12px', border: 'none', borderBottom: '2px solid #D6CBAE',
    background: 'rgba(255,255,255,.5)', color: INK,
    fontFamily: GAEGU, fontSize: 15, lineHeight: 1.6, resize: 'none',
  } as CSSProperties,
  error: { margin: '10px 0 0', fontSize: 13, color: RED } as CSSProperties,
  ghost: {
    flex: 1, minHeight: 48, borderRadius: 3, border: '1px solid ' + EDGE,
    background: PAPER, color: '#5C5445', fontFamily: GAEGU, fontWeight: 700, fontSize: 16, cursor: 'pointer',
  } as CSSProperties,
  send: (off: boolean): CSSProperties => ({
    flex: 1, minHeight: 48, borderRadius: 3,
    border: '1px solid ' + (off ? '#DCD2B8' : '#2F2A22'),
    background: off ? 'rgba(230,224,206,.8)' : INK, color: off ? '#A79C82' : '#FFF8EC',
    fontFamily: GAEGU, fontWeight: 700, fontSize: 16, cursor: off ? 'default' : 'pointer',
  }),
};
