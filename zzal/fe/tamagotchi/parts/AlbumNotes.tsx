// 앨범 머리의 쪽지 한 줄 — 지금 이 아이의 "심화 행동" 이 어디까지 왔는지.
//
// ★ 운영 사정(검수 대기·재생성 요청)은 여기 안 쓴다. 서버가 이미 사용자 말 네 가지로 접어서 준다
//   (계약 해석 29·30). 화면은 `baking` 한 칸과 `firstGift` 만 보고 한 줄을 고른다.
// ★ 왜 필요한가 — 밤에 굽는 일은 사용자가 자는 동안 일어난다. 아무 말도 없으면 "고장났나" 가 된다.
'use client';

import type { PetDetail } from '../../lib/pet';
import { EDGE, GAEGU, INK, PEN, SUB } from './theme';

export interface AlbumNotesProps {
  /** 이 아이가 지금 뭔가 연습 중인가(펫 단위 한 칸). */
  practicing: boolean;
  /** 첫 심화 행동(선물)의 진행. */
  firstGift: PetDetail['firstGift'];
  /** 앨범 기능이 열렸는가. **조회를 막는 값이 아니다** — 안내 문구만 바뀐다(해석 25). */
  unlocked: boolean;
  /** 엽서·장면은 아직 서버가 빈 목록으로 준다. 자리만 잡아 둔다. */
  postcards: number;
  scenes: number;
  name: string;
}

/** 첫 선물의 상태를 사람 말로. `null`이면 아직 알 수 없다는 뜻이라 아무 말도 안 한다. */
function giftLine(g: PetDetail['firstGift'], name: string): string | null {
  if (!g) return null;
  switch (g.status) {
    case 'OPEN': return `${name}의 첫 선물이 도착했어요`;
    case 'BAKING': return '첫 선물을 준비하고 있어요';
    case 'WAITING': return '오늘 잘 지내면 밤에 첫 선물을 준비해요';
    case 'LOCKED': return g.daysLeft > 0 ? `첫 선물까지 ${g.daysLeft}일` : null;
    default: return null;
  }
}

export default function AlbumNotes({ practicing, firstGift, unlocked, postcards, scenes, name }: AlbumNotesProps) {
  const gift = giftLine(firstGift, name);
  if (!gift && !practicing && !postcards && !scenes) return null;

  return (
    <div
      data-part="album-notes"
      data-practicing={practicing ? '1' : '0'}
      data-album-open={unlocked ? '1' : '0'}
      style={{
        display: 'flex', flexDirection: 'column', gap: 4,
        margin: '0 0 12px', padding: '10px 12px',
        background: '#FFF9E4', border: '1px solid ' + EDGE, borderRadius: 3,
      }}
    >
      {/* ★ "아직 연습 중이에요" 는 실패가 아니라 진행이다. 남은 시간·횟수는 말하지 않는다 —
          약속한 시각을 못 지키면 그 자체가 거짓말이 된다. */}
      {practicing && (
        <span data-note="practicing" style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 15, color: INK }}>
          아직 연습 중이에요 · 다 되면 아침에 보여 드릴게요
        </span>
      )}
      {gift && <span data-note="gift" style={{ fontFamily: PEN, fontSize: 18, color: SUB }}>{gift}</span>}
      {(postcards > 0 || scenes > 0) && (
        <span style={{ fontFamily: PEN, fontSize: 17, color: SUB }}>
          {postcards > 0 && `엽서 ${postcards}장`}{postcards > 0 && scenes > 0 && ' · '}{scenes > 0 && `혼자 논 장면 ${scenes}개`}
        </span>
      )}
    </div>
  );
}
