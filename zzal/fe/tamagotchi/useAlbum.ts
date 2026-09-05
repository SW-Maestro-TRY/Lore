// 앨범 한 판을 만드는 훅(옛 `useDex`).
//
// 두 가지를 낸다.
//   1) **도감 18칸** — 서버가 준 `PetDetail.motions` 를 스킨이 그대로 그릴 카드로. API 를 따로 안 부른다.
//      칸 수도 이름도 조건도 이미 상태 안에 있다.
//   2) **앨범의 나머지** — 엽서·장면·첫 심화 기념은 상태에 없어서 `GET /album` 을 한 번 부른다.
//
// ★ 잠긴 칸도 이름·조건이 보인다(정본 §6, 결정기록 C10). v1 의 "안 연 것의 이름은 쓰지 않는다" 는 폐기.
// ★ v0 동안 앨범 조회는 막히지 않는다(계약 해석 25). `features.album` 은 **플래그로만** 쓴다 —
//   잠긴 칸의 이름·조건을 보여주는 것이 정본인데 조회를 막으면 그 규칙과 어긋난다.
'use client';

import { useEffect, useMemo, useState, type CSSProperties } from 'react';
import { assetUrl } from '../lib/assets';
import { copyImageLink, downloadImage, imageFileName } from '../lib/download';
import type { Album, Motion, PetDetail, ShareKind } from '../lib/pet';
import type { PetSource } from '../lib/petSource';
import { YEOUL_MOTION } from './constants';

const DEXROT = [-1.4, .9, -.6, 1.3, -1.1, .7, -1.6, 1.1, -.8, 1.5, -1, .6];
const GAEGU = "'Gaegu',cursive";
const INK = '#3A352B';

/** 스킨이 그대로 그리는 카드 한 장. */
export interface DexCard {
  seq: number;
  key: string;
  name: string;
  /** 잠긴 칸의 조건 한 줄("채팅 응답 1회") + 진행("0/1"). 열린 칸은 ''. */
  hint: string;
  open: boolean;
  locked: boolean;
  /** 심화 행동(16프레임)이 열렸는가. */
  advanced: boolean;
  img: string;
  cardStyle: CSSProperties;
  mediaStyle: CSSProperties;
  imgStyle: CSSProperties;
  nameStyle: CSSProperties;
  save: () => void;
  share: () => void;
}

export interface UseDexOptions {
  motions: Motion[];
  pc: boolean;
  say: (message: string) => void;
  petName?: string | null;
  /** 저장·공유 사실을 서버에 남긴다(튜토리얼 25분의 서버 사실). */
  onShared?: (motionKey: string, kind: ShareKind) => void;
}

export function useDexCards({ motions, pc, say, petName, onShared }: UseDexOptions): DexCard[] {
  return useMemo(() => motions.map((m, i) => {
    const open = m.unlocked;
    const advanced = m.advanced.status === 'OPEN' && !!m.advanced.imageKey;
    const imageKey = advanced ? m.advanced.imageKey : m.basicImageKey;
    const img = imageKey ? assetUrl(imageKey) : (YEOUL_MOTION[m.key] ?? YEOUL_MOTION.base);
    const hint = open ? '' : `${m.hint ?? ''}${m.progress ? ` · ${m.progress.current}/${m.progress.target}` : ''}`;
    return {
      seq: m.seq, key: m.key, name: m.label, hint, open, locked: !open, advanced, img,
      cardStyle: {
        position: 'relative', background: open ? '#FFFEFA' : 'rgba(252,249,238,.75)',
        border: '1px solid ' + (open ? '#EDE6D4' : '#E2DAC4'), borderRadius: 2, overflow: 'hidden', padding: 4,
        boxShadow: open ? '3px 4px 0 rgba(58,53,43,.1)' : 'none',
        transform: `rotate(${(DEXROT[i % DEXROT.length] * (pc ? 1 : .45)).toFixed(2)}deg)`,
      } as CSSProperties,
      mediaStyle: { position: 'relative', aspectRatio: '313 / 350', background: '#F2EDDD', overflow: 'hidden' } as CSSProperties,
      imgStyle: {
        width: '100%', height: '100%', objectFit: 'contain', display: 'block',
        filter: open ? 'none' : 'grayscale(.75) opacity(.42)',
      } as CSSProperties,
      nameStyle: { fontFamily: GAEGU, fontWeight: 700, fontSize: 15, color: open ? INK : '#A79C82' } as CSSProperties,
      // ★ 어느 갈래로 끝나든 반드시 한마디를 띄운다. 아무 일도 안 일어나는 버튼이 가장 나쁘다.
      save: () => {
        if (!open || !imageKey) { say('아직 그림이 준비되지 않았어요'); return; }
        // ★ await 없이 곧바로 부른다 — iOS 갈래가 새 탭을 여는데, 기다렸다 부르면 팝업으로 막힌다.
        void downloadImage(assetUrl(imageKey), imageFileName(petName || 'lore', m.label)).then((r) => {
          if (r.outcome === 'saved') { say(m.label + ' 저장했어요'); onShared?.(m.key, 'DOWNLOAD'); }
          else if (r.outcome === 'opened') { say('새 탭에서 열었어요. 그림을 꾹 눌러 저장해 주세요'); onShared?.(m.key, 'DOWNLOAD'); }
          else if (r.code === 'http_404') say('아직 그림이 준비되지 않았어요');
          else if (r.code === 'popup_blocked') say('새 탭이 막혀 있어요. 팝업을 허용해 주세요');
          else say('저장하지 못했어요. 잠시 뒤 다시 눌러 주세요');
        });
      },
      share: () => {
        if (!open || !imageKey) { say('아직 그림이 준비되지 않았어요'); return; }
        void copyImageLink(assetUrl(imageKey)).then((r) => {
          if (r.manual) say('주소를 직접 복사해 주세요');
          else if (r.ok) { say('링크를 복사했어요'); onShared?.(m.key, 'SHARE'); }
          else say('링크를 복사하지 못했어요');
        });
      },
    };
  }), [motions, pc, say, petName, onShared]);
}


// ── 앨범 전체 ─────────────────────────────────────────────────────────────

export interface AlbumView {
  /** 도감 18칸. */
  cards: DexCard[];
  /** 기능이 열렸는가(`features.album`). **조회를 막는 값이 아니다** — 안내 문구용. */
  unlocked: boolean;
  /** 지금 뭔가 연습 중인가. 화면의 "아직 연습 중이에요" 한 줄이 이것만 본다(계약 해석 30). */
  practicing: boolean;
  /** 첫 심화 행동(선물)의 진행. */
  firstGift: PetDetail['firstGift'];
  /** 여행에서 온 엽서. v0 에서는 늘 빈 목록(백엔드 PR-11). */
  postcards: Album['postcards'];
  /** 혼자 논 장면. v0 에서는 늘 빈 목록(백엔드 PR-9). */
  scenes: Album['scenes'];
  /** 앨범을 아직 못 읽었나. 못 읽어도 도감 18칸은 그려진다. */
  loading: boolean;
}

export interface UseAlbumOptions extends UseDexOptions {
  source: PetSource | null;
  pet: PetDetail | null;
}

/**
 * 도감 카드 + 앨범의 나머지.
 *
 * ★ 앨범을 못 읽어도 화면은 **안 죽는다.** 도감 18칸은 상태에서 나오고, 엽서·장면은 빈 목록으로 둔다.
 *   곁다리 하나가 실패했다고 도감 전체를 못 보게 하면, 정작 할 수 있는 일까지 막는 셈이다.
 */
export function useAlbum(opts: UseAlbumOptions): AlbumView {
  const { source, pet, motions, pc, say, petName, onShared } = opts;
  const cards = useDexCards({ motions, pc, say, petName, onShared });
  const [album, setAlbum] = useState<Album | null>(null);
  const [loading, setLoading] = useState(false);

  const petId = pet?.phase === 'ALIVE' ? pet.petId : null;
  // 도착한 심화 행동의 수가 바뀌면 다시 읽는다(첫 심화 기념·엽서가 그때 늘어난다).
  const arrived = (pet?.motions ?? []).filter((m) => m.advanced.status === 'OPEN').length;

  useEffect(() => {
    if (!source || petId == null) { setAlbum(null); return; }
    let alive = true;
    const controller = new AbortController();
    setLoading(true);
    source.getAlbum(petId, controller.signal)
      .then((a) => { if (alive) setAlbum(a); })
      .catch(() => { /* 곁다리다 — 못 읽어도 도감은 그린다. */ })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; controller.abort(); };
  }, [source, petId, arrived]);

  return useMemo(() => ({
    cards,
    unlocked: pet?.features?.album === true,
    practicing: pet?.baking === 'PRACTICING' || pet?.baking === 'QUEUED',
    firstGift: album?.firstGift ?? pet?.firstGift ?? null,
    postcards: album?.postcards ?? [],
    scenes: album?.scenes ?? [],
    loading,
  }), [cards, pet?.features?.album, pet?.baking, pet?.firstGift, album, loading]);
}
