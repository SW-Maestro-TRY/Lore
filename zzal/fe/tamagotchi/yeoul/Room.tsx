// 방 — 여울 시안의 본 화면. 위에서 아래로 셋이다.
//
//   머리   이름 · 며칠째 · 친밀도 · [아이 정보]
//   무대   벽·바닥·창문 위에서 아이가 좌우로 오간다. 말풍선·튜토리얼·하트가 여기 뜬다.
//   아래   타일 다섯(주방·욕실·마당·침실·앨범)과, **누른 타일 바로 위에 뜨는 팝오버**
//
// ★ 시트가 아니라 팝오버인 것이 이 판의 핵심이다(2026-09-07 클로드 디자인 확정).
//   무대를 덮지 않으므로 아이를 보면서 밥을 줄 수 있다. 대신 깊은 화면(앨범·아이 정보·놀이)만
//   아래에서 올라오는 시트로 남겼다 → `Panels.tsx`.
//
// ★ 캐릭터는 **항상** 팝오버가 덮는 높이(`POP_LIFT`)만큼 위에 선다. 팝오버 열림·닫힘,
//   진짜 방·여울 샘플 어디서나 같은 자리다 — 예전처럼 겹침을 재서 따라가면 여닫을 때마다,
//   방을 바꿀 때마다 발이 오르내리고, 팝오버를 한 번도 안 연 샘플 첫 진입에선 낮게 섰다.
//
// ★ 캐릭터 칸은 발밑 여백만큼 더 내린다. 배경을 지운 그림은 발 아래가 비어 있어서, 칸을
//   바닥선에 맞추면 **발이 바닥선 위에 떠서** 그림자와 벌어진다. 여백은 그림마다 다르므로
//   `useFootPad` 가 그림에서 직접 잰다(못 재면 여울 기준값으로 되돌아간다).
'use client';

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { EGG_IMG, POP_LIFT, SPRITE_FOOT_PAD } from './constants';
import { YEOUL_ANCHORS_URL } from '../constants';
import { C, C2, GAEGU, LV, MONO, TAP_MIN, gap, monoSize, radius, shadow, fz, ink, acc, paperA, pad } from './ui';
import Album from './Album';
import Panels from './Panels';
import FeedbackSheet from '../FeedbackSheet';
import { spriteUrl, useFootPad, useHeadPad, useLive, useSideEdges, yeoulSpriteUrl } from './useHatch';
import { useIsWide } from '../useIsWide';
import { CHAT_MAX, type Yeoul } from './useYeoul';
import { useAnchors } from '../props/anchors';
import { charFit, HEAD_SAFE, FOOT_FLOOR, FOOT_FLOOR_SHORT, NARROW_Q, SHORT_Q } from '../props/layout';
import { poseFacing, propSide, propUrl } from '../props/spec';
import PropLayer, { RoomPropLayer, ScreenPropLayer } from '../props/PropLayer';
import {
  SITUATION_TABLE, activeSituations, alwaysSituationIds, situationsOfPose, stageAt, stagePlanOf,
  type SituationRow,
} from '../props/situations';
import { confirmedSpec } from '../props/catalog';

/** 걷힘 바퀴에만 잠깐 생기는 줄의 이름. 표에 없는 이름이라 다른 줄과 안 부딪힌다. */
const SWEEP_ROW_ID = '__sweep__';

// `NARROW_Q`(좁은 폰)·`SHORT_Q`(짧은 화면)는 `../props/layout` 에서 가져온다 — 소품 층과 **같은 기준**이라야
// 캐릭터 발끝선과 소품이 어긋나지 않는다. 좁고 짧은 화면(SE 등)만 `compact` 로 아이를 키우고 팝오버를 줄인다.

export default function Room({ y }: { y: Yeoul }) {
  const { s, v, actions } = y;
  // 좁은 폰(SE)에서 말풍선이 떴을 때만, 머리 위 공간을 벌기 위해 아이를 소폭 낮춘다(아래 SIL).
  const narrow = useIsWide(NARROW_Q);
  // ★ 좁고 **짧은** 화면(SE 667 등)만 — 팝오버를 콤팩트하게 줄이고 발끝선 예약(LIFT 하한)을 낮춰 아이를 키운다.
  //   폭·높이 둘 다 걸어야 390x844(긴 폰)·1280x720(넓은 창)이 안 딸려 온다(그 화면들은 이미 마음에 드심).
  const short = useIsWide(SHORT_Q);
  const compact = narrow && short;
  // 무엇을 그릴지는 `v.spriteKey`(useYeoul)가, 누구를 그릴지는 `spriteUrl`(useHatch)이 정한다.
  const live = useLive();
  // 여울 샘플 방에서는 여울이, 진짜 방에서는 내 아이만 나온다.
  const wantSrc = spriteUrl(live, v.spriteKey, v.sample.show);
  /**
   * **주소는 받았는데 그림이 없을 때**의 마지막 안전망(2026-09-13).
   *
   * ★ 왜 필요한가 — 서버는 `basicImageKey` 를 **파일이 있는지 확인하지 않고 만들어서 준다**
   *   (`PetResponses.basicImageKey`: 판 번호와 key 로 주소를 조립할 뿐이다).
   *   그래서 아직 한 장도 안 구운 아이(`basic_round == 0`)도 주소를 받는데, 그 주소는
   *   **판 번호가 빠진 옛 모양**이라 오늘 구운 아이 자리에서는 403 이다. `spriteUrl` 은
   *   주소가 **있으므로** 폴백을 안 타고, 결과는 **아무것도 안 그려진 빈 무대**다
   *   (2026-09-13 실측: 그런 아이의 1층 8종 전부 `naturalWidth === 0`).
   * ★ 범위를 좁혀 적는다 — 구운 아이(`round >= 1`)는 멀쩡하다(308 실측: `basic/1/base.webp` 200).
   *   빈 무대는 **굽기를 안 탄 아이에게만** 난다. 처음에 "아무 아이나" 로 적었다가 좁힌 자리다.
   * ★ 주소만 보고는 알 수 없고 **받아 봐야 안다.** 그래서 실패한 주소를 적어 두고 여울로 바꿔 단다.
   *   같은 주소로 두 번 시도하지 않으므로 깜빡이지 않는다.
   * ⚠️ 이것은 **덮개이지 해결이 아니다.** 진짜 고칠 자리는 "없는 그림의 주소를 주지 않는" 서버다.
   */
  const [brokenSrc, setBrokenSrc] = useState<ReadonlySet<string>>(() => new Set());
  const fallbackSrc = yeoulSpriteUrl(v.spriteKey);
  const charSrc = brokenSrc.has(wantSrc) ? fallbackSrc : wantSrc;
  // 발밑 여백은 그림마다 다르다 — 상수로 두면 어떤 아이는 뜨고 어떤 아이는 잠긴다.
  const footPad = useFootPad(charSrc, SPRITE_FOOT_PAD);
  // 머리 위 여백·좌우 가장자리도 **그림에서 잰다** — 아래 `headSpan`·`silLeft` 참조.
  const headPad = useHeadPad(charSrc, SPRITE_FOOT_PAD);
  const sideEdges = useSideEdges(charSrc, SPRITE_FOOT_PAD);

  // ── 소품 오버레이 ─────────────────────────────────────────────────────
  //
  // ★ **앵커가 없어도 화면이 완성이다.** 고정 앵커표(여울 실측)로 끝까지 그려지고, 서버가
  //   `anchorsKey` 를 주면 그때 받아서 덮어쓴다. 못 받으면 고정값 그대로 간다(개발 화면에만 표시).
  // ★ **상황표가 정본이다**(`contract/소품-상황표-v1.json` → `props/table.ts`). 여기서는 지금 상태를
  //   표의 낱말(상황 id)로 옮기기만 한다 — 자세별 소품을 코드에 적지 않는다.
  //   표가 없으면 아무 소품도 안 뜬다. 고장이 아니라 "아직 없음" 이다.
  // ★ 연습방(여울 샘플)은 서버 펫이 없어 `anchorsKey` 가 없다. 그래서 **여울 시연용 앵커**를 대신 쓴다 —
  //   그러면 연습방에서도 **진짜 앵커로 그리는 경로**를 눈으로 확인할 수 있다(폴백 띠가 꺼진다).
  const anchors = useAnchors(live.pet?.anchorsKey, v.sample.show ? YEOUL_ANCHORS_URL : undefined);
  const propTable = SITUATION_TABLE;
  // ★ 개발용(연습방) 고르기 — 손으로 고른 상황이 있으면 **그것만**, 자세만 골랐으면 **그 자세의 상황 전부**를 켠다.
  //   자세와 무관하게 깔리는 줄(바닥 흔적 같은 것)은 어느 쪽이든 그대로 둔다.
  const always = useMemo(() => alwaysSituationIds(propTable), [propTable]);
  const auto = activeSituations(v.scene);
  const picked = v.sitPick ? [v.sitPick, ...auto.filter((id) => always.has(id))]
    : v.posePick ? [...situationsOfPose(propTable, v.posePick), ...auto.filter((id) => always.has(id))]
      : auto;
  // ★ 개발용으로 손수 켠 줄(가방·재회 하트·하루 소품)은 자세를 골랐든 말든 **그대로 얹힌다.**
  //   서버 신호가 생기는 날 `activeSituations` 가 대신 켜면 여기서 빼면 된다.
  const active = v.scene.extra.length || v.scene.daily
    ? [...picked, ...v.scene.extra, ...(v.scene.daily ? ['daily_prop'] : [])]
    : picked;
  /**
   * 단계가 있는 소품은 **바퀴마다 한 단계씩** 넘어간다(밥 3->2->1). 몇 번째 바퀴인지는 두뇌가 세고,
   * 그 숫자를 **표가 적어 둔 차례**에 대입하는 일만 여기서 한다 — 차례를 코드가 지어내지 않는다.
   */
  const actPlan = v.scene.act ? stagePlanOf(propTable, v.scene.act) : null;
  /**
   * **덮었다가 걷히는 마지막 한 바퀴.**
   *
   * ★ 규격이 정한 것이다 — `dust` note: *"3단계(걷힘)는 그림이 없다 — 반짝은 단계가 아니라
   *   전환 신호다."* 그 바퀴에는 덮고 있던 소품을 **내리고** 마무리 신호만 띄운다
   *   (먼지 → 반짝임 · 거품 → 물줄기). 상훈님 "먼지가 화면을 다 덮고 나서 짜자잔".
   * ★ 신호 줄은 표에 없다(표는 상태를 적는 자리고 이건 **전환**이다). 그래서 여기서 한 줄을
   *   만들어 표 뒤에 붙인다 — 자리·크기는 여전히 **그 소품의 규격**이 정한다.
   */
  const beat = actPlan ? stageAt(actPlan, v.scene.actStep) : null;
  const sweeping = !!beat?.sweeping;
  const signalSpec = sweeping && actPlan?.signal ? confirmedSpec(actPlan.signal) : null;
  const signalRow: SituationRow | null = signalSpec && actPlan?.signal
    ? {
      id: SWEEP_ROW_ID, pose: '*', prop: actPlan.signal, anchor: signalSpec.anchor,
      layer: signalSpec.unit === 'screen' ? 'screen' : 'char', priority: 1, status: 'confirmed',
    }
    : null;
  const table = signalRow ? [...propTable, signalRow] : propTable;
  /**
   * ★ **같은 말을 두 언어로 하지 않는다**(2026-09-20 · 재설계안 H5).
   *   종이 말풍선(글)이 떠 있는 동안에는 머리 옆 만화 말풍선(`bubble_*` — 느낌표·물음표·음표…)을 끈다.
   *   둘이 같이 뜨면 "아이가 말한다" 를 두 가지 문법으로 동시에 말해 화면이 번잡해진다.
   */
  const mutedBubbleProps = useMemo(
    () => new Set(table.filter((r) => (r.prop ?? '').startsWith('bubble_')).map((r) => r.id)),
    [table],
  );
  const speaking = v.bub.show;
  const scene = {
    pose: v.spriteKey,
    // 걷힘 바퀴에는 **덮고 있던 줄을 끈다** — 안 끄면 첫 단계로 되돌아가 다시 덮인다.
    active: (sweeping && v.scene.act ? active.filter((id) => id !== v.scene.act) : active)
      .filter((id) => !(speaking && mutedBubbleProps.has(id)))
      .concat(signalRow ? [SWEEP_ROW_ID] : []),
    stages: {
      trash: v.scene.trash,
      ...(actPlan && beat?.stage != null ? { [actPlan.prop]: beat.stage } : {}),
    },
    // `daily_prop` 은 표가 `prop_ball|prop_book|prop_cup|prop_plant` 로 적어 둔 줄이라 **고른 것**을 말해 줘야 한다.
    ...(v.scene.daily ? { choices: { daily_prop: v.scene.daily } } : {}),
  };

  /**
   * **머리 옆을 이미 쓰고 있는 소품이 있는 쪽.** 말풍선이 옆으로 비킬 때 이쪽은 피한다.
   *
   * ★ 왜 — 게임 이김 표시(`win_star`)·짐 표시(`lose_dots`)가 `head_side` 앵커라, 1200 에서
   *   옆으로 비킨 말풍선과 **같은 자리**를 노린다(2026-09-21 실측: 47.7px 겹침).
   *   말풍선을 머리 위로 올리면 아이가 그만큼 작아지므로, **반대쪽으로 보내는 쪽**을 골랐다.
   * ★ 만화 말풍선(`bubble_*`)은 종이 말풍선이 뜨면 이미 꺼지므로(위 `speaking`) 여기 안 걸린다.
   */
  const headSideTaken: 'left' | 'right' | null = (() => {
    // ★ 게임이 도는 동안에는 **매치 내내** 같은 쪽을 피한다. 결과 표시는 판마다 떴다 사라지는데,
    //   뜰 때만 비키면 말풍선이 판마다 좌우로 튄다(실측: 대기 왼쪽 → 결과 오른쪽 → 다시 왼쪽).
    if (v.game.show) return poseFacing(v.spriteKey);
    for (const id of scene.active) {
      const row = table.find((r) => r.id === id);
      if (!row?.prop || row.anchor !== 'head_side') continue;
      const spec = confirmedSpec(row.prop);
      return spec ? propSide(spec, v.spriteKey) : 'left';
    }
    return null;
  })();

  // ── 아이를 어디에 얼마나 크게 세울 것인가 ──────────────────────────────
  //
  // 규칙은 셋이고, 위에서부터 양보할 수 없는 순서다.
  //   1) 상호작용으로 안 움직인다 — 아래 값은 전부 화면 크기만의 함수다(실측이 아니다).
  //      팝오버를 여닫든 방을 바꾸든 진짜 방/샘플 방을 오가든 같은 화면에선 같은 자리다.
  //   2) 아이가 무대 밖으로 안 나간다 — 머리끝이 무대 위끝 안에.
  //   3) 팝오버가 발을 안 덮는다 — 발끝이 팝오버 윗변보다 위에.
  //   4) 그 안에서 최대한 크게.
  //
  // ★ 발끝(`LIFT`) — 무대 아래에서 발끝까지. 무대가 커지면 **발끝도 30% 지점까지 함께 올라와**
  //   큰·긴 화면에서 발밑에 죽은 바닥이 넓게 남지 않는다(2026-09-16 art-direction: 방을 균형 있게).
  //   하한(보통 220px)은 팝오버가 발을 안 덮게(여유 16px), 상한 330px 은 아주 긴
  //   화면에서 발이 너무 높이 뜨지 않게 잡는다. 화면 높이로만 정해지는 값이라 1)을 깨지 않는다.
  //   ★ 짧은 화면(`compact`)은 팝오버가 콤팩트해져(아래 `Popover compact`) 더 낮은 자리에서도 발을 안
  //     덮으므로, 하한을 `FOOT_FLOOR_SHORT`(178px)로 낮춰 짧은 무대에서 아이에게 자리를 돌려준다.
  //   ★ `props/layout.ts` 의 `footlineFromBottom(stageH, floor)` 과 **똑같은 식·같은 하한**이라야 똥·바닥
  //     소품이 발에 붙는다(소품 층은 `StageGeom.footFloor` 로 같은 하한을 받는다).
  // ★ 키(`CHAR_H`) — 이제 규격 고정값(296px)이 아니라 **무대의 약 60%(실루엣 기준)** 를 목표로 커졌다
  //   줄었다 한다(2026-09-16: 큰 화면에서 아이가 너무 작아 보였다). 다만 머리끝이 무대 위로 넘지 않게
  //   남은 높이(`100% - LIFT - HEAD_SAFE`)로 깎는다. 짧은 화면은 이 깎기가 걸려 60%보다 작아진다.
  //   깎는 기준을 **가장 큰 자세**로 잡는 이유 — 자세마다 깎으면 자세를 바꿀 때 아이가 출렁여
  //   1)이 깨진다. `HEAD_SAFE` 는 반올림에 먹히지 않도록 두는 최소 여유다.
  //   ★ 배경이 사라지지 않도록 60% 를 넘겨 키우지 말 것 — "방 안에 있다"가 유지돼야 한다(상훈님 지시).
  const LIFT = `clamp(${compact ? FOOT_FLOOR_SHORT : FOOT_FLOOR}px, 30%, 330px)`;

  // ★ 크기는 **실루엣 키(K)로 정한다** — 상자를 먼저 정하고 그 안에 그림을 넣지 않는다.
  //   규격의 모든 ratio 가 "화면 키 = K_screen(296px)" 을 전제한다(→ `props/layout.ts` 머리말) —
  //   그 전제는 K 를 재는 **기준**일 뿐이고, 실제 화면 키는 무대에 맞춰 이보다 커지거나 작아진다.
  //   소품은 캐릭터 상자 폭에서 자(K)를 뽑으므로(→ `unitPxOfWidth`) 아이가 커지면 소품도 같은 비율로 커진다.
  //   상자 크기·세로 자리는 여기서 **따라 나오는 값**이다.
  // ★ 앵커를 못 받았으면(옛 펫) K 를 모른다 → 예전처럼 **상자 기준**으로 되돌아간다. 두 길 다 돈다.
  const fit = useMemo(() => charFit(anchors.anchors, v.spriteKey), [anchors.anchors, v.spriteKey]);
  /**
   * 캐릭터 상자 — **방에 붙박인 소품이 폭만 읽는다**(크기 자 K). 자리는 안 읽는다.
   * ★ 폭은 걸음(평행이동)·자세와 무관해서, 아이가 어디에 서 있든 똥이 안 따라간다.
   */
  const charBoxRef = useRef<HTMLDivElement>(null);
  /**
   * 앵커표를 믿어도 되는가. **표와 그림이 같은 판일 때만** 참이다.
   *
   * ★ 2026-09-22 — 세 번째 경우를 더했다: **지금 화면에 걸린 그림이 여울 폴백일 때.**
   *   고정 앵커표는 여울 정본 v02 에서 뽑은 것이라(→ `props/anchors-fixed.ts` 머리말) 그 그림에
   *   대해서는 **표가 곧 실측**이다. 서버 없는 진짜 방(목)·그림이 깨져 여울로 버티는 자리가 여기다.
   *   빼 두면 여울을 그려 놓고 "표를 못 믿는다" 며 실루엣을 상자 전체(0~1)로 잡아, 옆자리를
   *   포기하고 아이를 135px 깎는다(1440 실측 499.4 → 364.0). 내 아이 그림일 때는 그대로 거짓이다.
   */
  const byK = anchors.source === 'server' || v.sample.show || charSrc === fallbackSrc;

  // ★ 말풍선 자리 — **화면 폭으로 가르지 않는다**(2026-09-20 재설계 · 안 1).
  //
  //   예전엔 `max-width: 640` 인 좁은 폰에서만 머리 위 자리를 예약하고(`BUBBLE_RESERVE`),
  //   주석에 *"넓은/긴 화면은 이미 여유가 있어 예약이 무시된다"* 고 적어 두었다.
  //   **실측은 정반대였다**(2026-09-20 · `방화면-UX-재설계안-0920.md` C-2):
  //     · 1200x844 진짜 방 — 4자 한마디 **27.9px**, 18자 55.6px, 42자 **83.3px** 잘림
  //     · 390x844 진짜 방 — 42자에서만 10.9px · 375x667 연습방 — 42자 57.3px
  //   넓은 화면일수록 아이가 무대의 60%로 크게 서서 머리 위에 22px밖에 안 남기 때문이다.
  //   즉 **잘림이 가장 심한 곳이 예약을 안 걸던 화면**이었다.
  //
  //   이제 갈림은 아래 `Bubble` 이 **그 말풍선이 실제로 몇 px 인지 재서** 정한다.
  //     1) 머리 위에 들어가면 그대로 머리 위       → 아이 크기 그대로
  //     2) 안 들어가면 **머리 옆**으로 비킨다        → 아이 크기 그대로(PC 는 아이 양옆에 180px 넘게 남는다)
  //     3) 위도 옆도 없으면(좁고 짧은 폰) 그때만     → 아이를 **딱 모자란 만큼** 낮춘다
  //   그래서 넓은 화면에서 아이가 공연히 줄지 않고, 좁은 화면에서도 필요 이상 줄지 않는다.
  const [bubbleHeadroom, setBubbleHeadroom] = useState(0);
  const HEADROOM = Math.max(HEAD_SAFE, bubbleHeadroom);
  /**
   * **첫 그림에서는 아이 키를 애니메이션하지 않는다**(2026-09-23).
   *
   * ★ 왜 — 아이 키는 말풍선이 자리를 얼마나 사느냐(`HEADROOM`)로 정해지고, 그 답은 마운트
   *   직후 `Bubble` 이 실제로 재서 알려 준다. 그런데 아이 상자에 `transition: height .28s` 가
   *   걸려 있어서, **첫 답이 오는 순간이 곧 0.28초짜리 크기 변화**가 된다 — 방에 들어가면
   *   아이가 495 → 378 로 **주르륵 줄어드는 것이 보인다**(390x844 실측. 폭마다 74~139px).
   *   재는 것이 틀린 게 아니라, **아직 아무것도 안 정해진 값에서 정답으로 가는 길**을
   *   애니메이션으로 보여 준 것이 문제다.
   * ★ 그래서 첫 페인트가 끝날 때까지만 전환을 끈다. 그 뒤(말풍선이 뜨고 지며 자리를 더 사고
   *   덜 사는 평소의 변화)는 예전처럼 부드럽게 이어진다 — 그게 이 전환을 둔 이유다.
   */
  const [sizeSettled, setSizeSettled] = useState(false);
  useEffect(() => {
    const id = requestAnimationFrame(() => setSizeSettled(true));
    return () => cancelAnimationFrame(id);
  }, []);
  // 화면에서의 실루엣 키. **무대의 약 60%** 를 목표로 하되, 머리끝이 무대 위로 안 넘게 남은 높이로 깎는다.
  //   `SIL` 은 가장 큰 자세의 실루엣이 화면에서 가질 높이다(무대 60%, 하한 150px, 머리 공간으로 상한).
  //   K_SCREEN = SIL ÷ (가장 큰 실루엣÷K) — 이렇게 뒤집어야 어떤 자세든 무대 밖으로 안 나간다.
  const silOf = (pad: number) => `min(calc(100% - ${LIFT} - ${pad}px), max(60%, 150px))`;
  const SIL = silOf(HEADROOM);
  const K_SCREEN = `calc(${SIL} / ${fit.tallestPerK.toFixed(4)})`;
  const boxHOf = (pad: number) => (byK
    ? `calc(${silOf(pad)} / ${fit.tallestPerK.toFixed(4)} * ${fit.boxHPerK.toFixed(4)})`
    : `calc(min(calc(100% - ${LIFT} - ${pad}px), max(64%, 160px)) / ${(1 - footPad).toFixed(4)})`);
  const CHAR_H = boxHOf(HEADROOM);
  /** 말풍선이 자리를 사기 **전**의 아이 상자. 말풍선 자리 계산의 기준자다(→ `Bubble` 머리말). */
  const CHAR_H_FREE = boxHOf(HEAD_SAFE);
  // 발끝이 발끝선(`LIFT`)에 오게 상자를 내린다. 앵커가 있으면 **그 자세의 발끝**을, 없으면 잰 여백을 쓴다.
  const BELOW_FOOT = byK ? fit.belowFoot : footPad;
  const CHAR_ASPECT = byK ? fit.aspect.toFixed(6) : '313/350';

  // ★ 말풍선은 **머리끝 자리**를 계산해 그 옆이나 위에 띄운다(2026-09-16 · 2026-09-20 개정).
  //   머리끝(무대 아래 기준) = 발끝선(LIFT) + 상자세로(CHAR_H) × (그 자세 머리끝→발끝 / 캔버스세로).
  //   예전엔 이 자리에 **가만히** 떠 있고 여유가 14px 뿐이라, 아이가 뛰면(`yHop` 30 + `yBob` 4)
  //   얼굴을 최대 26.0px 덮었다. 이제 말풍선이 **같은 뜀을 같이 타서** 간격이 안 변한다(→ `Bubble`).
  //
  // ★ **머리끝은 앵커표만 믿으면 안 된다**(2026-09-20). 서버 앵커가 없는 아이(옛 펫·목·연습 전)는
  //   고정 앵커표로 잡는데, 그 표는 **옛 판 그림**에서 잰 값이라 지금 그림과 어긋난다.
  //   실측(진짜 방 1200, 고정 앵커 + v7 그림): 표가 말하는 머리끝이 실제보다 44.5px 아래여서
  //   말풍선이 그만큼 얼굴을 덮었다. 그래서 앵커를 못 믿는 경우에는 발밑과 **같은 방법**으로
  //   그림에서 직접 잰 여백(`headPad`·`footPad`)으로 실루엣 높이를 잡는다.
  const headSpan = byK
    ? fit.headSpanPerBoxH
    : Math.max(0.2, 1 - footPad - headPad);
  /** 정수리 ↔ 머리 옆선 사이(상자 세로 대비). 머리 모양의 상수라 표 값을 그대로 쓴다. */
  const headSideDrop = Math.max(0, fit.headSpanPerBoxH - fit.headSidePerBoxH);
  /**
   * 말풍선이 **옆으로 비킬 때 쓰는 단 하나의 실루엣 좌·우**(0~1).
   *
   * ★★ 2026-09-22 — 예전에는 자가 **둘**이었다: 들어갈 폭은 앵커표의 선 자세로 재고(`…Upright`),
   *   그릴 자리는 지금 자세(`silLeftPerBoxW`)나 그림에서 잰 값(`sideEdges`)으로 잡았다.
   *   두 자가 어긋난 만큼 말풍선이 그대로 **무대 밖으로 나간다.** 앵커 없는 아이(진짜 방 목)는
   *   그림이 다른 출처(CDN)라 캔버스로 못 읽어 `sideEdges` 가 기본값 0~1 로 남는데(→ `useSpritePads`),
   *   폭은 0.73 으로 재고 자리는 1.0 에 잡으니 **오른쪽으로 57~115px 튀어나갔다**(1100 실측).
   *   그래서 **폭도 자리도 이 한 값으로만** 본다 — 두 기준이 영영 못 어긋난다.
   * ★ 앵커를 믿을 수 있으면 **선 자세**로 잡는다(자세마다 갈리면 아이가 출렁인다 → `layout.ts`).
   *   못 믿으면 상자 전체(0~1) — 가장 불리하게 잡아 옆자리를 포기할 뿐, 잘리지는 않는다.
   */
  const silLeft = byK ? fit.silLeftUprightPerBoxW : sideEdges.left;
  const silRight = byK ? fit.silRightUprightPerBoxW : sideEdges.right;
  // ★ 아이 그림이 줄면(배부름 0 → 0.7배) **머리도 그만큼 내려온다.** 말풍선이 옛 머리 자리에
  //   그대로 떠 있으면 머리 위로 한참 뜬다 — 같은 배율을 여기에도 건다(→ `st.charScale`).
  const headTopFromBottom = `calc(${LIFT} + ${CHAR_H} * ${(headSpan * v.st.charScale).toFixed(4)})`;
  /** 얼굴 높이(머리 옆선). 머리 옆으로 비킨 말풍선의 세로 한가운데를 여기에 맞춘다. */
  const faceFromBottom = `calc(${LIFT} + ${CHAR_H} * ${(Math.max(0, headSpan - headSideDrop) * v.st.charScale).toFixed(4)})`;
  /**
   * 무대와 **자리를 사기 전 아이 상자**를 재는 두 손잡이 — 말풍선이 이 둘로 자리를 정한다.
   *
   * ★★ **`useRef` 가 아니라 콜백 ref(상태)다**(2026-09-23). 왜 — 리액트는 커밋할 때 **자식부터**
   *   내려간다. 그래서 자식(`Bubble`)의 `useLayoutEffect` 가 도는 시점에는 **부모인 이 무대의
   *   ref 가 아직 안 붙어 있다.** 예전에는 `Bubble` 이 `if (!stage || !probe) return` 으로 그냥
   *   빠져나갔고, 그 한 번이 **첫 마운트 전부**였다 — `measure()` 가 한 번도 안 돌고
   *   **ResizeObserver 도 안 달렸다.** 그래서 방에 들어간 첫 1.1초 동안 말풍선이 초기값
   *   ("머리 위" · 양보 0) 그대로 서서 무대 밖으로 12~58px 잘렸고(360x800 44.0 · 390x640 58.4 ·
   *   375x667 55.2 · 1200x900 12.0 실측), 창 크기를 바꿔도 안 고쳐졌다(옵저버가 없으니까).
   *   자세가 `hello`→`base` 로 바뀌며 dep 이 흔들릴 때에야 한 번 재고 제자리로 **툭 튀었다.**
   * ★ 콜백 ref 는 **요소가 붙는 순간 상태를 바꿔** 한 번 더 그리게 한다. 그 두 번째 그림에서
   *   `Bubble` 은 진짜 요소를 받아 반드시 재고 옵저버를 단다. 재시도 횟수를 세거나 타이머를
   *   놓을 필요가 없다 — 요소가 없으면 애초에 다시 그릴 일도 없다.
   * ★ `setState` 는 함수 정체가 안 변하므로 ref 콜백이 매 그림마다 떼었다 붙지 않는다.
   */
  const [stageEl, setStageEl] = useState<HTMLDivElement | null>(null);
  const [charProbeEl, setCharProbeEl] = useState<HTMLDivElement | null>(null);

  return (
    <div style={{ flex: '1 1 auto', display: 'flex', flexDirection: 'column', minHeight: 0, position: 'relative' }}>
      {/* 팝오버가 열려 있으면 무대 아무 데나 눌러 닫을 수 있다. */}
      {v.pop.show && <div onClick={actions.closePop} style={{ position: 'absolute', inset: 0, zIndex: 2 }} />}

      {v.hud.show && <Hud y={y} />}
      {v.sample.show && <SampleHud y={y} />}

      {/* ★ 후기는 이 한 줄이 전부다 — 띄울지 말지(이미 냈는가 · 아기 시간표 중인가 · 받은 움직임이
          도착했는가)는 **FeedbackSheet 이 정한다.** 여기서 판정하면 스킨이 그 규칙을 알아야 하고,
          규칙이 바뀔 때마다 스킨이 같이 바뀐다 — 스크랩북과 같은 약속이다(skins/Scrapbook.tsx).
          ★ 자리 — 머리줄 **아래, 무대 위**. 띠는 덮개 없이 세로 흐름에 끼어드는 한 줄이라
            **아래 돌봄 타일을 한 번도 가리지 않는다**(예전에 돌봄 버튼을 덮어 띠로 바꾼 그 이유).
            여울에는 도감 구역이 따로 없어서 스크랩북의 그 자리를 대신하는 곳이다.
          ★ 목(여울 연습방·시안 미리보기)에서는 안 그린다 — 후기는 실서버 전용이다. */}
      <FeedbackSheet
        petId={!s.sampleMode && live.pet?.phase === 'ALIVE' ? live.petId : null}
        // 받은 움직임이 실제로 있을 때만 "받은 움직임, 어땠어요?" 를 묻는다.
        advancedArrived={(live.pet?.learnedToday?.length ?? 0) > 0 || live.pet?.firstGift?.status === 'OPEN'}
        // 아기 시간표는 **서버가 센다**(계약 — 끝나면 블록이 null 이다). 화면이 다시 세지 않는다.
        tutorialActive={live.pet?.tutorial?.active === true || s.tutorOn}
        // 전면 판(해금 축하·선물)과 앨범 벽이 떠 있는 동안에는 저절로 안 올라온다.
        hold={!!s.fire || v.wall.show || v.frame.show}
        tone="yeoul"
        // ★ 개발용 미리보기 — 이동 창에서 켜면 목(연습방)에서도 후기 판을 mock 으로 강제로 띄운다.
        //   실서버 경로는 위 props 그대로이고, 이 값만 갈래를 나눈다(공개 도메인에선 켜질 길이 없다).
        preview={v.fbPreview}
      />

      {/* ── 무대 ───────────────────────────────────────────────── */}
      <div
        ref={setStageEl}
        data-part="stage"
        onClick={actions.closePop}
        style={{
          flex: '1 1 auto', position: 'relative', width: '100%', minHeight: 0, overflow: 'hidden',
          background: v.st.wall, transition: 'background .55s ease, filter .35s ease',
        }}
      >
        <div style={{ position: 'absolute', inset: 0, backgroundImage: v.st.pattern, opacity: 0.5 }} />

        {/* 창문 — 낮엔 해, 밤엔 달.
            ★ 자리·크기를 **무대 기준(%)** 으로 잡는다(2026-09-16). 예전엔 고정 px(left28·top34·98x98)라
              큰 화면에서는 넓은 벽 구석에 작은 사각형이 홀로 떠 다른 요소와 동떨어져 보였다.
              이제 무대에 비례해 커지고 자리도 벽의 같은 지점에 붙어 방의 일부로 읽힌다.
              해·달은 창 안에서 %로 잡아 창이 커져도 같은 자리에 온다. */}
        <div style={{
          position: 'absolute', left: '7%', top: '7%',
          width: 'clamp(92px, 15%, 156px)', aspectRatio: '1 / 1', borderRadius: radius.md,
          border: `5px solid ${v.st.frame}`, background: v.st.sky, overflow: 'hidden',
        }}>
          {v.st.moon && <div style={{ position: 'absolute', right: '15%', top: '13%', width: '28%', height: '28%', borderRadius: '50%', background: '#F7EDCD', boxShadow: '0 0 20px rgba(247,237,205,.75)' }} />}
          {v.st.sun && <div style={{ position: 'absolute', right: '16%', top: '15%', width: '24%', height: '24%', borderRadius: '50%', background: '#FBE7B4' }} />}
        </div>

        {/* 바닥 — ★ 벽↔바닥 경계(수평선)를 **발끝선(`LIFT`) 위**에 둔다(2026-09-16).
            예전엔 고정 `min(266px,44%)` 이라 짧은 화면(SE 378px)에서 경계(44%=167px)가 발끝선(220px)보다
            **아래**로 내려가 아이가 바닥 위 허공에 뜨고 그 아래 바닥이 텅 비어 보였다. 이제 경계를
            발끝선보다 한 뼘 위(무대 8%, 28~90px)로 올려 아이가 언제나 바닥에 발을 딛는다. */}
        <div style={{ position: 'absolute', left: 0, right: 0, bottom: 0, height: `calc(${LIFT} + clamp(28px, 8%, 90px))`, background: v.st.floor, borderTop: `1px solid ${ink(.09)}` }} />

        {/* ★ 말풍선 자리 계산의 **기준자**. 눈에 안 보이고 아무것도 안 덮는다.
            말풍선이 아이를 낮추면(위 `HEADROOM`) 아이 상자가 줄어드는데, 그 줄어든 상자로 다시
            자리를 재면 값이 서로를 쫓아 출렁인다. 그래서 **자리를 사기 전 상자**를 따로 하나 둔다. */}
        <div
          ref={setCharProbeEl} aria-hidden data-part="char-probe"
          style={{
            position: 'absolute', left: '50%', bottom: LIFT, height: CHAR_H_FREE,
            aspectRatio: CHAR_ASPECT, transform: 'translateX(-50%)',
            visibility: 'hidden', pointerEvents: 'none',
          }}
        />

        {/* 그림자 — 캐릭터와 같은 걸음으로 움직인다. */}
        <div style={{
          position: 'absolute', left: 0, right: 0, // 그림자는 발끝을 따라간다 — 발끝에서 18px 아래가 중심(예전 값과 같다).
          bottom: `calc(${LIFT} - 30px)`, height: 24,
          display: 'flex', justifyContent: 'center',
          animation: 'yWander 21s ease-in-out infinite', animationPlayState: v.st.play,
        }}>
          <span style={{ display: 'block', width: 'min(236px,62%)', height: '100%', borderRadius: '50%', background: ink(.15), filter: 'blur(7px)' }} />
        </div>

        {/* 아이 — 좌우로 오가고(wander) 가끔 뛴다(hop). 눌러서 쓰다듬는다.
            ⚠️ 자는 동안은 **감춘다**(임시) — 자는 그림이 아직 없어 깨어 있는 그림이 커튼 밑에
            비치면 자는 것으로 안 읽힌다. 진짜 그림이 오면 이 감춤을 걷어낸다(판정 5). */}
        <div
          data-part="pet"
          // 지금 어떤 자세를 짓고 있는지. 화면을 밖에서 확인할 때 쓰는 손잡이다(`data-room`·`data-action` 과 같은 쓰임).
          data-sprite={v.spriteKey}
          hidden={v.hidePet}
          onClick={(e) => { e.stopPropagation(); actions.onPet(); }}
          style={{
            position: 'absolute', left: 0, right: 0,
            bottom: `calc(${LIFT} - ${CHAR_H} * ${BELOW_FOOT.toFixed(4)})`,
            height: CHAR_H, display: 'flex', justifyContent: 'center', zIndex: 2,
            // 말풍선이 뜰 때 아이가 소폭 낮아지는데(BUBBLE_RESERVE), 툭 튀지 않게 부드럽게 잇는다.
            // ★ 단 **첫 그림만 빼고**(→ `sizeSettled`) — 거기선 부드러움이 곧 "아이가 줄어드는 연출"이 된다.
            transition: sizeSettled ? 'height .28s ease, bottom .28s ease' : 'none',
            animation: 'yWander 21s ease-in-out infinite', animationPlayState: v.st.play,
          }}
        >
          {/* ★ 가로는 캔버스 비율로 **따라 나온다**. 여백까지 포함한 판이라 무대보다 넓어질 수 있는데,
              넘치는 몫은 전부 투명 여백이다(여울 base 는 좌우 각 122px). 그래서 안 줄인다 —
              줄이면 그만큼 아이가 작아져 방금 맞춘 K 가 다시 어긋난다. */}
          <div ref={charBoxRef} style={{ position: 'relative', height: '100%', aspectRatio: CHAR_ASPECT, maxWidth: byK ? 'none' : '88%', flex: 'none' }}>
            {v.guide.tap && (
              <>
                <span style={{ position: 'absolute', left: '50%', top: '52%', marginLeft: -70, width: 140, height: 140, borderRadius: '50%', border: `2px solid ${acc(.5)}`, animation: 'yRipple 1.9s ease-out infinite', pointerEvents: 'none' }} />
                <span style={{
                  position: 'absolute', left: '50%', bottom: 18, transform: 'translateX(-50%)',
                  display: 'flex', alignItems: 'center', gap: gap.sm, padding: '5px 12px', borderRadius: radius.pill,
                  background: paperA(.94), border: `1px solid ${acc(.22)}`,
                  fontSize: fz.sm, color: C.accent, whiteSpace: 'nowrap',
                  animation: 'yTapdot 1.9s ease-in-out infinite', pointerEvents: 'none',
                }}>
                  <span style={{ width: 6, height: 6, borderRadius: '50%', background: C.accent }} />
                  {v.guide.label}
                </span>
              </>
            )}
            {/* 아이 뒤에 깔리는 것(매트). 반전 바깥이라 걸음마다 뒤집히지 않는다. */}
            <PropLayer z="below_char" scene={scene} table={table} anchors={anchors} />
            {/* ★ 배부름 0 이면 **그림만 0.7배**로 줄인다(정본 §게이지 · 2026-09-22 판정 J).
                ★ 상자(`charBox`)는 그대로 둔다 — 상자를 줄이면 바닥 소품(똥)이 읽는 자(상자 폭)까지
                  같이 줄어 방 안 물건이 통째로 작아진다. 발끝은 그대로 바닥에 두려고 아래가 축이다.
                ★★ **배율은 제 겹을 따로 쓴다.** 아래 `yFace`·`yHop` 은 키프레임이 `transform` 을
                  건드려서(걸음 뒤집기·뜀), 같은 칸에 인라인 `transform` 을 적으면 **애니메이션이 이긴다**
                  (2026-09-22 실측: 0.7 을 줬는데 그림 크기가 그대로였다). */}
            <div style={{
              width: '100%', height: '100%', transformOrigin: 'bottom center', transition: 'transform .3s ease',
              ...(v.st.charScale === 1 ? null : { transform: `scale(${v.st.charScale})` }),
            }}>
            <div style={{
              width: '100%', height: '100%', animation: 'yFace 21s steps(1,end) infinite', animationPlayState: v.st.play,
            }}>
              <div style={{ width: '100%', height: '100%', animation: 'yHop 9.5s ease-in-out infinite', animationPlayState: v.st.play }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={charSrc} alt=""
                  data-sprite-fallback={charSrc === fallbackSrc && wantSrc !== fallbackSrc ? '1' : undefined}
                  onError={() => {
                    if (charSrc === fallbackSrc) return;   // 여울마저 실패하면 더 갈 곳이 없다
                    // eslint-disable-next-line no-console
                    console.warn(`[여울] 내 아이 그림이 열리지 않습니다 — ${charSrc}. 여울 그림으로 답니다. `
                      + '서버가 준 주소인데 파일이 없다는 뜻이라, 부화 생성이 끝났는지 확인해야 합니다.');
                    setBrokenSrc((prev) => new Set(prev).add(charSrc));
                  }}
                  style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block', animation: 'yBob 4.6s ease-in-out infinite', filter: v.st.charFilter }}
                />
              </div>
            </div>
            </div>
            {/* 아이 앞에 얹히는 것(머리 옆 기호·손 앞 먹을 것·발치 소품). */}
            <PropLayer scene={scene} table={table} anchors={anchors} />
          </div>
        </div>

        {/* ★ 방 바닥에 **붙박인** 것(똥·하루 소품·매트·가방). 캐릭터 상자 밖이라 아이가 걸어도 안 따라간다
            (상훈님 2026-09-13 "캐릭터가 움직인다고 똥도 같이 움직이면 안돼"). 매트만 아이 뒤에 깔린다. */}
        <RoomPropLayer z="below_char" scene={scene} table={table} anchors={anchors} charBox={charBoxRef} />
        <RoomPropLayer scene={scene} table={table} anchors={anchors} charBox={charBoxRef} />

        {/* 화면 전체에 까는 것(거품·먼지·물줄기·커튼·달) — 발끝선 기준이라 무대에 직접 붙는다.
            ★ 아이 앞뒤로 **두 겹**이다. 달은 뒤(`below_char`), 먼지·거품·물줄기·커튼은 앞. */}
        <ScreenPropLayer z="below_char" scene={scene} table={table} anchors={anchors} />
        <ScreenPropLayer scene={scene} table={table} anchors={anchors} />

        {/* 자는 중 — 커튼을 친다. */}
        {v.st.curtain && (
          <>
            <div style={{ position: 'absolute', inset: 0, background: 'linear-gradient(180deg,rgba(43,52,82,.6),rgba(43,52,82,.3))', animation: 'yFadeIn .5s ease' }} />
            <div style={{ position: 'absolute', left: 0, top: 0, bottom: 0, width: '27%', background: 'linear-gradient(90deg,#3E4A72,#5A6894)', backgroundImage: 'repeating-linear-gradient(90deg,rgba(255,255,255,.16) 0 3px,transparent 3px 26px)', borderRight: '3px solid #2C3557', boxShadow: '6px 0 16px rgba(28,34,58,.45)' }} />
            <div style={{ position: 'absolute', right: 0, top: 0, bottom: 0, width: '27%', background: 'linear-gradient(270deg,#3E4A72,#5A6894)', backgroundImage: 'repeating-linear-gradient(90deg,rgba(255,255,255,.16) 0 3px,transparent 3px 26px)', borderLeft: '3px solid #2C3557', boxShadow: '-6px 0 16px rgba(28,34,58,.45)' }} />
            {/* ★ 글씨에 바탕을 깔았다(판정 22) — 흰 글씨만 얹으면 커튼 무늬에 묻힌다.
                이름을 넣어 누가 자는지 분명히 한다(판정 13). */}
            <div style={{ position: 'absolute', left: 0, right: 0, bottom: 22, display: 'flex', justifyContent: 'center' }}>
              <span style={{ padding: '6px 16px', borderRadius: radius.pill, background: 'rgba(28,34,58,.55)', fontFamily: GAEGU, fontSize: fz.h2, color: '#F6EEDD' }}>{v.sleepLine}</span>
            </div>
          </>
        )}
        {/* 아픔 — 벽·창문은 그대로 두고 **채도만** 낮춘다(2026-09-16). backdrop 로 뒤(벽·무늬·창문·바닥)를
            탈색하되 아이(zIndex:2)는 제 필터(saturate .5)를 그대로 쓴다. 예전의 단색 회색 슬래브(질감·창문이
            사라져 "화면 깨짐"으로 읽힘)를 걷어냈다. backdrop 을 못 그리는 곳에서도 옅은 냉기 톤은 남는다. */}
        {v.st.sick && <div style={{ position: 'absolute', inset: 0, backdropFilter: 'saturate(.45)', WebkitBackdropFilter: 'saturate(.45)', background: 'rgba(140,144,156,.12)', animation: 'yFadeIn .4s ease', pointerEvents: 'none' }} />}

        {/* 말풍선이 **아이에게 붙어** 같은 걸음·같은 뜀을 탄다. */}
        <Bubble
          show={v.bub.show} text={v.bub.text} play={v.st.play}
          headSpan={headSpan} faceSpan={Math.max(0, headSpan - headSideDrop)}
          headSpanReserve={fit.headSpanTallestPerBoxH}
          silLeft={silLeft} silRight={silRight} avoid={headSideTaken}
          headBottom={headTopFromBottom} faceBottom={faceFromBottom}
          stage={stageEl} probe={charProbeEl} onHeadroom={setBubbleHeadroom}
        />

        {v.hearts.show && (
          <div data-part="hearts" style={{ position: 'absolute', left: '50%', bottom: '44%', animation: 'yFloatup 1.1s ease forwards', fontSize: fz.h1, letterSpacing: 3, color: '#D97386', textShadow: '0 1px 5px rgba(255,255,255,.8)' }}>
            {v.hearts.text}
          </div>
        )}
      </div>

      {/* ── 아래 — 팝오버와 타일 ───────────────────────────────────
          ★ 무대 바닥(`v.st.floor`)을 이 컨트롤 영역 위쪽으로 **이어 준다**(2026-09-16). 예전엔 이 칸이
            셸 크림색이라 무대 바닥(탄색)과 사이에 크림 띠 seam 이 생겨 "빈 베이지 띠"로 보였다.
            위 38px 를 바닥 톤에서 셸로 풀어 seam 을 없애고 방 바닥이 타일까지 자연스럽게 내려오게 한다. */}
      <div onClick={actions.bottomTap} style={{ position: 'relative', flex: 'none', padding: '10px 12px 22px', background: `linear-gradient(180deg, ${v.st.floor} 0, ${C.shell} 38px)` }}>
        {/* ★ 떠 있는 것은 **한 칸 안에 쌓는다** — 선반·대화·팝오버·토스트가 서로 자리를 안 뺏는다.
            토스트는 그 칸의 `bottom:100%` 라 늘 그 위로 뜬다(2026-09-20 · 재설계안 H6).
            예전엔 미니카드와 같은 상자 안에 있어 좌하단에서 둘이 26px 겹쳤다 — 겹침 규칙을
            새로 두지 않고 부모를 바꿔 자리를 갈랐다. */}
        <div style={{ position: 'absolute', left: 12, right: 12, bottom: 116, zIndex: 5, display: 'flex', flexDirection: 'column', alignItems: 'flex-start', gap: gap.sm }}>
          {v.ask.show && <AskCard y={y} />}
          {/* 발밑 빈 땅(진짜 방 194.8px = 세로 23.1%)에 놓는 **낮은 선반**.
              구석에 따로 떠 있던 셋(다음 배울 것·약·대화)이 여기 한 줄로 앉는다. */}
          <Shelf y={y} />
          {/* 좌우 맞히기 — 선반과 **같은 땅**에 놓는 낮은 판. 게임이 도는 동안 선반은 내려가 있다. */}
          {v.game.show && <GuessPanel y={y} />}
          {v.chat.show && <ChatBar y={y} />}
          {v.toast.show && <Toast text={v.toast.text} />}
          {v.pop.show && <Popover y={y} compact={compact} />}
        </div>

        <Tiles y={y} />
      </div>

      <Album y={y} />
      <Panels y={y} />
    </div>
  );
}

// ── 조각들 ──────────────────────────────────────────────────────────────

// ── 아이에게 붙는 말풍선 ─────────────────────────────────────────────────
//
// ★ **왜 말풍선이 아이를 따라다니나** — 2026-09-20 에 세 안(아이에게 붙이기 / 바닥까지 꾸미기 /
//   무대 밖 고정 말띠)을 다 만들어 상훈님이 눈으로 비교하신 뒤 **이 안을 고르셨다.** 기준은 하나였다:
//   *"아이가 나한테 말을 거는 느낌은 무조건 나야 해서."* 무대 밖 말띠는 잘림·겹침이 구조적으로
//   0 이라 점수는 더 높았지만 꼬리가 없어 그 느낌이 가장 약했고, 그게 zzal 의 유일한 정서 자산이다.
//   → 그러니 **꼬리와 "아이를 따라간다"는 성질을 성능·단순함과 바꾸지 말 것.** 자리를 옮기거나
//   최적화할 일이 생기면 이 두 가지를 먼저 지키고 나머지를 조정한다.
//   (세 안 비교판과 개발 창 리모컨은 결정 뒤 걷어냈다 — 죽은 코드를 남기지 않는다.)
//
// ★ 지키는 것 하나 — **"아이가 나에게 말한다"**. 그래서 꼬리는 어디에 뜨든 아이를 가리키고,
//   말풍선은 아이와 **같은 걸음(`yWander`)·같은 뜀(`yHop`)** 을 탄다(새 키프레임은 안 만든다).
// ★ 껍데기는 **말이 없을 때도 늘 붙어 있다.** 늦게 붙으면 CSS 애니메이션이 그때부터 시작해
//   아이와 박자가 어긋난다 — 그러면 같은 키프레임을 타도 걸음·뜀이 따로 논다.
// ★ 자리는 세 가지고, 위에서부터 고른다(실측으로 판정 — 재설계안 E-1).
//   1) 머리 위        : 아래끝을 머리끝 20px 위에 붙이고 위로 자란다
//   2) 머리 옆        : 1)이 무대 위끝(+8px)을 넘길 때. **여백이 큰 쪽 → 모자라면 반대쪽**,
//                       세로는 얼굴 높이. 놓기 전에 무대 안으로 한 번 더 물린다(마지막 빗장)
//   3) 머리 위 + 양보 : **양옆 다** 자리가 없을 때만 아이를 **딱 모자란 만큼** 낮춘다
//   어느 쪽이든 무대 밖으로 나가는 경우가 없으므로 **잘림은 0** 이다.

/** 머리끝 ↔ 말풍선 아래끝. 숨쉬기(`yBob` 4px)를 늘 덮고도 남는다(예전 14px). */
const BUBBLE_GAP = 20;
/** 무대 위끝에서 남기는 최소 여유. 이 값이 지켜지는 한 잘림은 0 이다. */
const BUBBLE_EDGE = 8;
/** 아이 옆에 말풍선을 놓을 수 있는 최소 폭. 이보다 좁으면 두세 글자씩 끊겨 오히려 못 읽는다. */
const BUBBLE_SIDE_MIN = 132;
const BUBBLE_MAX_W = 280;
/** 걸음(`yWander`)의 좌우 진폭. 옆자리는 **가장 불리한 쪽**으로 재야 걸어가도 안 잘린다. */
const WANDER = 30;
/**
 * 뜀(`yHop`)의 위쪽 진폭. 말풍선이 **아이와 같이 뛰므로** 그만큼 위로 올라간다 —
 * 위 여유를 이만큼 더 잡아 두지 않으면 **뛰는 순간에만** 윗변이 잘린다(2026-09-20 실측:
 * 연습방 1200 에서 21.6px, 390 에서 22.4px). 겹침을 없앤 대가로 생기는 몫이라 여기서 갚는다.
 */
const HOP = 31;
const BUBBLE_LINE = `1px solid ${ink(.13)}`;

type BubblePlace =
  | { at: 'above'; w: number; left: number }
  | { at: 'side'; w: number; left: number; top: number; tail: number; side: 'left' | 'right' };

/** 두 자리가 같은가. 같은 값으로 다시 그리지 않으려고 본다(아래 `ro.observe(card)` 의 짝). */
function samePlace(a: BubblePlace, b: BubblePlace): boolean {
  if (a.at !== b.at) return false;
  if (a.w !== b.w || a.left !== b.left) return false;
  if (a.at === 'above' || b.at === 'above') return true;
  return a.top === b.top && a.tail === b.tail && a.side === b.side;
}

function Bubble({
  show, text, play, headSpan, faceSpan, headSpanReserve, silLeft, silRight, avoid,
  headBottom, faceBottom, stage, probe, onHeadroom,
}: {
  show: boolean; text: string; play: string;
  /** 머리 옆을 이미 소품이 쓰고 있는 쪽. 옆으로 비킬 때 **이쪽은 피한다**(겹침 0). */
  avoid: 'left' | 'right' | null;
  /** 발끝선 ↔ 정수리 · 발끝선 ↔ 얼굴선 (상자 세로 대비). 무대 px 로 옮기는 건 여기서 한다. */
  headSpan: number; faceSpan: number;
  /**
   * **자리를 얼마나 예약할지 정할 때 쓰는 머리 높이** — 자세를 안 본다(가장 높은 머리끝).
   * 지금 자세로 정하면 누웠을 때 예약이 0 이 되어 아이가 갑자기 커진다(→ `layout.ts` 머리말).
   */
  headSpanReserve: number;
  /**
   * 실루엣 좌·우 가장자리(상자 가로 대비). 아이 옆에 자리가 얼마나 남았는지 재고,
   * **그 자리에 말풍선을 놓을 때도 같은 값을 쓴다** — 자가 둘이면 어긋난 만큼 무대 밖으로 나간다
   * (→ `Room` 의 `silLeft` 정의). 자세를 안 보는 값이라 자세가 바뀌어도 자리가 안 흔들린다.
   */
  silLeft: number; silRight: number;
  headBottom: string; faceBottom: string;
  /**
   * 무대와 기준자 **요소 그 자체**(ref 상자가 아니다 — → `Room` 의 `stageEl` 머리말).
   * 둘이 다 붙기 전에는 `null` 이고, 붙는 순간 부모가 다시 그려 여기로 들어온다.
   */
  stage: HTMLDivElement | null;
  probe: HTMLDivElement | null;
  onHeadroom: (px: number) => void;
}) {
  const cardRef = useRef<HTMLDivElement>(null);
  /**
   * **늘 붙어 있는 두 줄짜리 자**(보이지 않는다). 예약 높이는 이것으로만 잰다 —
   * 말풍선이 없을 때도, 한 줄일 때도 같은 자리를 사 두려면 재는 자가 변하면 안 된다.
   */
  const reserveRef = useRef<HTMLDivElement>(null);
  const [place, setPlace] = useState<BubblePlace>({ at: 'above', w: BUBBLE_MAX_W, left: -BUBBLE_MAX_W / 2 });

  useLayoutEffect(() => {
    // 아직 안 붙었으면 이번엔 잴 수 없다. **다음 그림에서 반드시 다시 온다** —
    // 두 요소는 부모의 상태라, 붙는 순간이 곧 다시 그리는 순간이다(→ `Room` 의 `stageEl`).
    if (!stage || !probe) return undefined;
    // ★ 같은 답이면 상태를 안 건드린다 — 말풍선 자신을 관찰 대상에 넣었기 때문에(아래),
    //   매번 새 객체를 넣으면 그리기→크기변화→다시 재기가 끝없이 돈다.
    const put = (next: BubblePlace) => setPlace((prev) => (samePlace(prev, next) ? prev : next));
    const measure = () => {
      const card = cardRef.current;
      const res = reserveRef.current;
      if (!res) return;
      const st = stage.getBoundingClientRect();
      const pb = probe.getBoundingClientRect();
      if (!st.height || !pb.height) return;
      // 기준자(`char-probe`)는 **말풍선이 자리를 사기 전** 아이 상자다 — 줄어든 상자로 다시 재면
      // 두 값이 서로를 쫓아 출렁인다. 그래서 여기서 읽는 머리·발·폭은 전부 '양보 전' 값이다.
      const lift = st.bottom - pb.bottom;
      // ★★ 예약은 **가장 높은 머리끝**으로 잰다(자세 무관). 지금 자세로 재면 누웠을 때만
      //   말풍선이 그냥 들어가 예약이 0 이 되고, 깨는 순간 아이가 108px 튄다(2026-09-22 dev 실측).
      const headTopY = st.height - (lift + pb.height * Math.max(headSpan, headSpanReserve));
      const faceY = st.height - (lift + pb.height * faceSpan);
      const boxL = (st.width - pb.width) / 2;
      // ★★ 실루엣 좌·우는 **이 한 쌍뿐이다**(2026-09-22). 폭을 재는 자와 자리를 잡는 자를 가르면
      //   그 차이가 곧 잘림이 된다 — 실제로 진짜 방(목)에서 오른쪽으로 115px 나갔다(→ `silLeft` 정의).
      const silL = boxL + pb.width * silLeft;
      const silR = boxL + pb.width * silRight;

      // 1) 머리 위 — 뜀(HOP)까지 미리 갚아 둔다. 안 그러면 **뛰는 순간에만** 윗변이 잘린다.
      const aboveW = Math.min(BUBBLE_MAX_W, Math.round(st.width - 32));
      res.style.width = `${aboveW}px`;
      if (card) card.style.width = `${aboveW}px`;
      // ★★ 높이는 **늘 두 줄 기준**이다(2026-09-22 상훈님 판정 C). 지금 말풍선이 한 줄이라고
      //   덜 예약하면, 두 줄짜리 말이 오는 순간 아이가 35px 내려앉는다(dev 실측 368 → 335).
      //   두 줄로 잡아 두면 한 줄이든 없든 자리가 같아 **흔들림이 0** 이다. 대가는 아이가 그만큼
      //   늘 작다는 것이고, 상훈님이 그 값을 알고 고르셨다.
      const aboveH = Math.max(res.offsetHeight, card?.offsetHeight ?? 0);
      const need = aboveH + BUBBLE_GAP + BUBBLE_EDGE + HOP;
      if (need <= headTopY) {
        put({ at: 'above', w: aboveW, left: -Math.round(aboveW / 2) });
        onHeadroom(0);
        return;
      }

      // 2) 머리 옆 — 여백이 큰 쪽부터, **모자라면 반대쪽**, 둘 다 모자라면 3) 으로 내려간다.
      //    ★ 소품이 이미 쓰는 쪽(`avoid`)은 아예 후보에서 뺀다 — 거기 두면 겹친다.
      //    ★ 고르는 기준(여백)은 **무대 크기와 선 자세**만 본다 — 글자 수·자세를 안 보므로
      //      한 매치 동안 말풍선이 좌우로 튀지 않는다.
      const gapL = silL - WANDER;
      const gapR = st.width - silR - WANDER;
      const cands = (['right', 'left'] as const)
        .filter((c) => c !== avoid)
        .sort((a, b) => (b === 'right' ? gapR : gapL) - (a === 'right' ? gapR : gapL));
      for (const side of cands) {
        const gap = side === 'right' ? gapR : gapL;
        const sideW = Math.min(BUBBLE_MAX_W, Math.floor(gap - 12));
        if (sideW < BUBBLE_SIDE_MIN) continue;
        // 보이는 말풍선이 없으면 예약 카드로 잰다 — 자리는 같은 자로 재야 뜰 때 안 움직인다.
        const meas = card ?? res;
        meas.style.width = `${sideW}px`;
        const h = meas.offsetHeight;
        // 옆자리도 같이 뛴다 — 위쪽은 HOP 만큼 더 물리고, 아래는 숨쉬기(yBob 4px)만 본다.
        const topMin = BUBBLE_EDGE + HOP;
        const top = Math.min(
          Math.max(faceY - h / 2, topMin),
          Math.max(topMin, st.height - BUBBLE_EDGE - 4 - h),
        );
        const inner = Math.round((side === 'right' ? silR - st.width / 2 : st.width / 2 - silL) + 10);
        // ★★ 마지막 빗장 — 계산이 어디서 어긋나도 **무대 밖으로는 못 나간다**(2026-09-22).
        //   위 `gap` 이 이미 지켜야 하는 값이지만, 자가 하나라도 틀리면 곧장 잘림으로 나타나므로
        //   여기서 한 번 더 물린다. 걸음 진폭(WANDER)까지 남겨 **걸어가도** 안 잘린다.
        //   좌우로 미는 일만 하므로 `side` 가 바뀌지 않는다 — 꼬리는 그대로 아이를 가리킨다.
        const half = st.width / 2;
        const lo = BUBBLE_EDGE + WANDER - half;
        const hi = half - (BUBBLE_EDGE + WANDER) - sideW;
        const want = side === 'right' ? inner : -(inner + sideW);
        put({
          at: 'side', w: sideW, side,
          left: hi >= lo ? Math.min(Math.max(want, lo), hi) : Math.round(-sideW / 2),
          top: Math.round(top - faceY),
          tail: Math.round(Math.min(Math.max(faceY - top, 16), Math.max(16, h - 16))),
        });
        onHeadroom(0);
        return;
      }

      // 3) 위도 옆도 없다(좁고 짧은 폰) — 그때만 아이가 **딱 모자란 만큼** 자리를 내준다.
      res.style.width = `${aboveW}px`;
      if (card) card.style.width = `${aboveW}px`;
      put({ at: 'above', w: aboveW, left: -Math.round(aboveW / 2) });
      onHeadroom(Math.ceil(need));
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(stage);
    ro.observe(probe);
    // ★ **말풍선 자신도 본다**(2026-09-20 실측으로 추가). 글씨체(Gaegu)가 늦게 도착하면 글이
    //   한 줄에서 두 줄로 불어나는데, 그때 다시 재지 않으면 **이미 내린 결정이 낡은 채로 남는다** —
    //   실측에서 연습방 18자가 한 줄(47.7px)로 재어져 "머리 위" 로 갔다가, 글씨체가 와서
    //   두 줄(75.4px)이 되자 뛰는 순간 5.3~17px 잘렸다. 자리 계산은 늘 같은 입력에서 같은 답을
    //   내므로(폭을 재기 전에 스스로 정한다) 이 관찰이 되먹임 고리를 만들지 않는다 — `put` 참조.
    if (cardRef.current) ro.observe(cardRef.current);
    if (reserveRef.current) ro.observe(reserveRef.current);
    return () => ro.disconnect();
  }, [show, text, headSpan, faceSpan, headSpanReserve, silLeft, silRight, avoid, stage, probe, onHeadroom]);

  const tailBase: React.CSSProperties = {
    position: 'absolute', width: 11, height: 11, background: C.paper,
  };

  return (
    <div
      data-part="bubble-box"
      style={{
        position: 'absolute', left: '50%', bottom: place.at === 'above' ? headBottom : faceBottom,
        width: 0, height: 0, zIndex: 3,
        animation: 'yWander 21s ease-in-out infinite', animationPlayState: play,
      }}
    >
      <div style={{
        position: 'absolute', left: 0, bottom: 0, width: 0, height: 0,
        animation: 'yHop 9.5s ease-in-out infinite', animationPlayState: play,
      }}>
        {/* ★★ **두 줄짜리 자**(2026-09-22 판정 C·D). 늘 붙어 있고 절대 안 보인다.
            왜 — 아이 크기는 "말풍선이 머리 위에 들어가느냐" 로 정해진다. 그 판단을 **지금 떠 있는
            말풍선**으로 하면 말이 없을 때·한 줄일 때·두 줄일 때가 전부 달라져 아이가 출렁인다
            (dev 실측 390: 없음 370.6 · 한 줄 368.0 · 두 줄 335.2 · 잠 478.2).
            그래서 **늘 두 줄짜리 카드 하나만 자로 쓴다** — 자가 안 변하니 자리도 안 변한다.
            ★ 안 보이는 동안에도 **재야** 하므로 `display:none` 이 아니라 `visibility:hidden` 이다.
            ★ 대가 = 아이가 늘 두 줄만큼 작다. 상훈님이 그 값을 알고 고르셨다(판정 C 1안). */}
        <div
          ref={reserveRef} data-part="bubble-reserve" aria-hidden
          style={{
            position: 'absolute', boxSizing: 'border-box', left: place.left, width: place.w,
            bottom: BUBBLE_GAP,
            border: BUBBLE_LINE, borderRadius: radius.md, padding: pad.chip,
            visibility: 'hidden', pointerEvents: 'none',
          }}
        >
          {/* 빈 칸 **두 줄**. 재는 것은 글이 아니라 두 줄짜리 카드의 높이다. */}
          <span style={{ fontFamily: GAEGU, fontSize: fz.xl, lineHeight: 1.3, color: C.ink, whiteSpace: 'pre-line' }}>{'\u00A0\n\u00A0'}</span>
        </div>
        {show && (
          <div
            ref={cardRef} data-part="bubble" data-place={place.at}
            style={{
              position: 'absolute', boxSizing: 'border-box', left: place.left, width: place.w,
              ...(place.at === 'above' ? { bottom: BUBBLE_GAP } : { top: place.top }),
              background: C.paper, border: BUBBLE_LINE, borderRadius: radius.md,
              padding: pad.chip, boxShadow: shadow.card,
              textAlign: place.at === 'above' ? 'center' : 'left',
              animation: 'yPop .28s ease',
            }}
          >
            <span style={{ fontFamily: GAEGU, fontSize: fz.xl, lineHeight: 1.3, color: C.ink }}>{text}</span>
            {/* 꼬리는 **언제나 아이를 가리킨다** — 위면 아래 한가운데, 옆이면 아이 쪽 옆면 얼굴 높이. */}
            {place.at === 'above' ? (
              <div style={{ ...tailBase, left: '50%', bottom: -6, transform: 'translateX(-50%) rotate(45deg)', borderRight: BUBBLE_LINE, borderBottom: BUBBLE_LINE }} />
            ) : place.side === 'right' ? (
              <div style={{ ...tailBase, left: -6, top: place.tail, transform: 'translateY(-50%) rotate(45deg)', borderLeft: BUBBLE_LINE, borderBottom: BUBBLE_LINE }} />
            ) : (
              <div style={{ ...tailBase, right: -6, top: place.tail, transform: 'translateY(-50%) rotate(45deg)', borderRight: BUBBLE_LINE, borderTop: BUBBLE_LINE }} />
            )}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * 발밑 선반 — 구석에 따로 떠 있던 셋(다음 배울 것 · 약 · 대화)을 **한 줄로 앉힌다**(안 1·2).
 *
 * ★ 왜 — 발끝 아래 194.8px(세로 23.1%)이 늘 비어 있었다. 잘못 비운 게 아니라(팝오버가 쓸 자리다)
 *   **닫혀 있는 동안 아무도 안 쓰는 것**이 문제였다. 그래서 그 땅에 낮은 선반을 놓고 떠 있던 것을 앉힌다.
 * ★ 표시 조건은 **하나도 안 바꿨다** — 팝오버·대화·시트가 열리면 셋이 각자 사라지고(`mini.show` 등)
 *   선반도 같이 사라져 팝오버가 그 자리를 쓴다. 겹침 규칙을 새로 두지 않았다.
 */
function Shelf({ y }: { y: Yeoul }) {
  const { v } = y;
  if (!(v.mini.show || v.medFab.show || v.fab.show)) return null;
  // ★ **앉힐 것이 없으면 선반을 안 깐다.** 연습방에는 '다음 배울 것' 카드가 없어서, 종이 띠에 동그란
  //   단추 하나만 놓이면 "덜 만든 줄" 로 읽혔다(실측 스크린샷). 그때는 단추만 제자리에 둔다.
  const solid = v.mini.show;
  return (
    <div
      data-part="shelf" data-solid={solid ? '1' : '0'}
      onClick={(e) => e.stopPropagation()}
      style={{
        width: '100%', boxSizing: 'border-box', display: 'flex', alignItems: 'flex-end', gap: gap.sm,
        ...(solid
          ? {
            padding: '8px 9px', borderRadius: radius.lg, background: C.paper,
            border: `1px solid ${C.line}`, boxShadow: shadow.shelf,
          }
          : {}),
      }}
    >
      {v.mini.show ? <MiniCard y={y} /> : <span style={{ flex: 1 }} />}
      {/* 종이가 없는 판에서도 단추는 오른쪽 끝에 붙는다(위 빈 칸이 밀어 준다). */}
      {v.medFab.show && <MedChip y={y} />}
      {v.fab.show && <ChatFab y={y} />}
    </div>
  );
}

/** 선반 칸 한 벌 — 셋이 같은 결이라야 한 줄로 읽힌다. */
const slotChip: React.CSSProperties = {
  flex: 'none', width: 46, height: 46, borderRadius: radius.md,
  border: `1px solid ${C.lineHard}`, background: C.slot,
  display: 'flex', alignItems: 'center', justifyContent: 'center',
};

function MedChip({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <button
      onClick={(e) => { e.stopPropagation(); actions.onMed(); }}
      disabled={v.medFab.off} data-action="med" aria-label="약 주기"
      style={{ ...slotChip, position: 'relative', borderColor: C.accent, animation: 'yBlink 1.3s ease-in-out infinite' }}
    >
      <span style={{ position: 'relative', width: 28, height: 15, display: 'block' }}>
        <span style={{ position: 'absolute', inset: 0, border: `2px solid ${C.accent}`, borderRadius: radius.pill, background: `linear-gradient(90deg,${C.accent} 0 50%,${C.paper} 50% 100%)` }} />
      </span>
    </button>
  );
}

/** 잠깐 뜨는 알림 한 줄. 자리는 **부모가 정한다**(→ 위 머리말) — 자기 자리를 스스로 안 고른다. */
function Toast({ text }: { text: string }) {
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: '100%', marginBottom: 9, zIndex: 7, display: 'flex', justifyContent: 'center', pointerEvents: 'none' }}>
      <span data-part="toast" style={{ background: ink(.92), color: C2.onDark, borderRadius: radius.pill, padding: '7px 16px', fontSize: fz.sm, animation: 'yFadeIn .2s ease' }}>{text}</span>
    </div>
  );
}

/** 돌보기 타일 다섯. */
function Tiles({ y }: { y: Yeoul }) {
  return (
    <div data-part="tiles" style={{ display: 'grid', gridTemplateColumns: 'repeat(5,1fr)', gap: gap.sm, width: '100%', boxSizing: 'border-box', position: 'relative', zIndex: 6 }}>
      {y.v.tiles.map((r) => (
        <button
          key={r.key} data-room={r.key}
          onClick={(e) => { e.stopPropagation(); r.pick(); }}
          // ★ 튜토리얼이 다른 칸을 가리키는 동안에는 **진짜로 안 눌린다**(2026-09-22 판정).
          //   자는 동안 잠기는 것은 여전히 눌려서 한 줄을 말한다 — 그건 상태이지 안내가 아니다.
          disabled={r.off}
          style={{
            position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: gap.sm,
            padding: '11px 4px 10px', borderRadius: radius.md,
            borderStyle: 'solid', borderWidth: r.bw, borderColor: r.bd, background: r.tileBg, animation: r.anim,
            // 자는 동안은 눌러도 안 열린다(판정 13). 눌리는 것처럼 보이지 않게 흐리게.
            opacity: r.dim ? 0.45 : 1,
          }}
        >
          <span style={{ position: 'relative', width: 26, height: 26, flex: 'none', color: r.fg }}>
            {r.layers.map((p, i) => <span key={i} style={cssText(p)} />)}
          </span>
          {/* ★ 줄높이 1 은 **한글이 들어갈 수 없는 칸**이다(2026-09-21 판정 8) — 글자 상자가 글씨
              크기와 똑같아서 받침·윗선의 잉크가 칸 밖으로 비어져 나온다. 1.3 이면 담긴다. */}
          <span style={{ fontSize: fz.sm, lineHeight: 1.3, letterSpacing: '.01em', color: r.fg }}>{r.label}</span>
          {r.hasBadge && (
            <span style={{
              position: 'absolute', top: -5, right: -3, minWidth: 18, height: 18, padding: '0 4px', boxSizing: 'border-box',
              borderRadius: radius.pill, background: C.paper, border: `1px solid ${C.lineHard}`,
              font: `${monoSize.xs}px ${MONO}`, color: C.sub2, display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>{r.badge}</span>
          )}
        </button>
      ))}
    </div>
  );
}

// ── 좌우 맞히기 — 발밑 낮은 판 ───────────────────────────────────────────
//
// ★ 왜 발밑인가 — 예전엔 아래에서 올라오는 시트가 화면의 54%를 먹고 **아이를 40.8% 가렸다.**
//   시트는 없앴고(어두운 막도 없다), **아이를 한 픽셀도 안 가리는** 낮은 판만 남았다.
//   ⚠️ **시트가 아니다** — 무대 위에 얹힌 판이고, 선반·팝오버와 **같은 땅·같은 자리**를 쓴다.
// ★ 처음에는 손을 아이 몸(`hand_front` 앵커)에 붙였는데(재설계안 안 1), 상훈님이 3210 에서
//   직접 보시고 **손을 아래로 내리는 쪽**을 고르셨다(2026-09-21). 재설계안 안 2 의 배치다.
// ★ **새 그림·새 키프레임을 만들지 않았다.** 손 여섯 장은 이미 있던 자산 그대로다.
// ★ 게임의 말은 **아이 말풍선**이 한다(`v.bub`). 게임 전용 말 장치를 따로 만들지 않는다.

/** 그 손이 지금 무슨 그림인가. 주먹 · 펼친 빈 손 · 펼친 사탕 손 셋뿐이다. */
function handSrc(side: 'LEFT' | 'RIGHT', open: boolean, kind: 'candy' | 'empty'): string {
  const lr = side === 'LEFT' ? 'l' : 'r';
  return propUrl(open ? `guess_${lr}_open_${kind}` : `guess_${lr}_fist`, 1);
}

function GuessPanel({ y }: { y: Yeoul }) {
  const g = y.v.game;
  return (
    <div
      data-part="guess-panel"
      onClick={(e) => e.stopPropagation()}
      style={{
        width: '100%', boxSizing: 'border-box', display: 'flex', flexDirection: 'column',
        alignItems: 'center', gap: gap.md, padding: '10px 10px 9px', borderRadius: radius.lg,
        background: C.paper, border: `1px solid ${C.line}`, boxShadow: shadow.shelf,
      }}
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: gap.md, width: '100%' }}>
        {(['LEFT', 'RIGHT'] as const).map((side) => {
          const open = g.openSide === side;
          return (
            <button
              key={side} data-action={side === 'LEFT' ? 'guess-left' : 'guess-right'}
              data-open={open ? '1' : '0'}
              onClick={(e) => { e.stopPropagation(); if (g.can) g.pick(side)(); }}
              disabled={!g.can}
              aria-label={side === 'LEFT' ? '왼손 고르기' : '오른손 고르기'}
              style={{
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                minHeight: 84, padding: '8px 6px', borderRadius: radius.md,
                border: `1px solid ${C.lineHard}`, background: g.can ? C.slot : C.slotDim,
                cursor: g.can ? 'pointer' : 'default',
              }}
            >
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img
                src={handSrc(side, open, g.openKind)} alt=""
                style={{ width: 64, height: 64, objectFit: 'contain', display: 'block', animation: open ? 'yPop .22s ease' : 'yFadeIn .2s ease' }}
              />
            </button>
          );
        })}
      </div>
      {/* 판 표시는 **점만**이다(2026-09-21 상훈님) — "N번째 · 3번 맞히면 이겨요 · 오늘 N판 남음"
          글줄은 지웠다. 규칙(하루 3판 · 매치당 5판 3승)은 그대로 돌고, 화면에서 글로 안 말할 뿐이다.
          ★ 색만으로 가르지 않는다 — 맞힘은 채운 원, 빗나감은 ✕, 아직은 빈 원. */}
      {/* ★ 나가는 문은 **하나**다(2026-09-21 판정 3). 타일을 눌러 판을 접는 것은 이탈이 아니고
          (판은 살아 있다) 이 ✕ 만 기권이다. 그래서 ✕ 는 묻고 나가고, 타일은 안 묻는다.
          ★ **아파도 눌린다** — 서버가 아픔 거절을 만들지 않았다(`quitFailLine` 머리말).
          ★ 자리 — 손 두 칸을 **안 건드리는** 아래 줄 오른쪽 끝. 점 줄은 가운데 그대로 두고
            그 줄 안에서 오른쪽으로 비켜 앉혀, 고르다가 잘못 눌릴 거리를 둔다. */}
      <div style={{ position: 'relative', width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <div data-part="guess-marks" style={{ display: 'flex', gap: gap.lg }}>
          {g.marks.map((m, i) => (
            <span
              key={i} data-mark={m.hit === null ? 'none' : m.hit ? 'hit' : 'miss'}
              style={{
                width: 12, height: 12, borderRadius: '50%', boxSizing: 'border-box',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                border: `1.5px solid ${m.hit === null ? C.lineHard : m.hit ? '#41633A' : ink(.32)}`,
                background: m.hit === true ? '#E4F0DC' : 'transparent',
                font: `${monoSize.xs}px ${MONO}`, color: C.sub2, lineHeight: 1,
                outline: m.now ? `2px solid ${C.accentDim}` : 'none',
              }}
            >{m.hit === false ? '✕' : ''}</span>
          ))}
        </div>
        <button
          data-action="guess-quit" onClick={(e) => { e.stopPropagation(); g.quit(); }}
          aria-label="게임 나가기"
          // ★ 누르는 자리 44px(판정 5). 절대 배치라 **자리를 안 옮기고** 단추만 키운다 —
          //   보이는 동그라미는 26 그대로이고, 오른쪽 끝도 그대로다.
          style={{
            position: 'absolute', right: -9, top: '50%', transform: 'translateY(-50%)',
            width: TAP_MIN, height: TAP_MIN, display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: 'none', background: 'none', padding: 0,
          }}
        >
          <span style={{
            width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center',
            borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot,
            fontSize: fz.sm, color: C.sub2, lineHeight: 1,
          }}>✕</span>
        </button>
      </div>
    </div>
  );
}

/** 시안이 타일 아이콘을 CSS 한 줄로 적어 두었다. 그 줄을 React 스타일 객체로 옮긴다. */
function cssText(text: string): React.CSSProperties {
  const out: Record<string, string> = { position: 'absolute' };
  for (const part of text.split(';')) {
    const i = part.indexOf(':');
    if (i < 0) continue;
    const k = part.slice(0, i).trim().replace(/-([a-z])/g, (_, c: string) => c.toUpperCase());
    out[k] = part.slice(i + 1).trim();
  }
  return out as React.CSSProperties;
}

/**
 * 진짜 방의 머리줄.
 *
 * ★ 윗줄은 **아이 이름**이다(상훈님 2026-09-08). 예전엔 서비스 이름('여울')이 그 자리를 쓰고
 *   아이 이름은 아랫줄에 함께 있었다 — 이름이 두 줄로 나뉘어 어느 쪽이 이 아이인지 흐렸다.
 *   글꼴·크기는 그대로 둔다(손글씨 22px 이 이름에 더 어울린다).
 * ★ 아랫줄은 **며칠째 · 친밀도**만. 이름과 그 뒤 가운뎃점은 뺐다.
 */
function Hud({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  // 좁은 폰에서는 머리 띠(이름 줄·며칠째 줄)를 얇게 줄여 무대에 세로를 돌려준다(데스크톱은 그대로).
  const narrow = useIsWide(NARROW_Q);
  return (
    <>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: narrow ? '6px 16px 2px' : '11px 20px 5px' }}>
        {/* 이름이 길어도(12자) 아래 버튼과 부딪히지 않게 한 줄로 자른다. */}
        <span data-part="pet-title" style={{
          fontFamily: GAEGU, fontWeight: 700, fontSize: narrow ? fz.xl : fz.h2, lineHeight: 1.2, color: C.ink,
          maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
        }}>{v.pet.name}</span>
      </div>
      <div style={{ flex: 'none', display: 'flex', alignItems: 'center', gap: gap.sm, padding: narrow ? '0 16px 5px' : '0 20px 8px' }}>
        <span style={{ fontSize: fz.md, color: '#635A52' }}>{v.pet.dayText}</span>
        <span style={{ fontSize: fz.sm, color: C2.dim }}>·</span>
        <span style={{ fontSize: fz.md, color: '#635A52' }}>친밀도 {v.pet.bond}%</span>
        <span style={{ flex: 1 }} />
        {/* ★ 누르는 자리 44px(2026-09-22 판정 5). **보이는 알약은 그대로 두고** 단추만 키운다 —
            머리줄이 두꺼워지면 그만큼 무대가 낮아져 아이가 작아진다. 음수 여백이 제자리를 지킨다. */}
        <button
          onClick={actions.openSettings} data-part="pet-info" data-hl={v.hud.hl ? '1' : undefined}
          data-off={v.hud.off ? '1' : undefined} disabled={v.hud.off}
          style={{
            display: 'flex', alignItems: 'center', justifyContent: 'flex-end', flex: 'none',
            minHeight: TAP_MIN, margin: '-10px 0', padding: 0, border: 'none', background: 'none',
          }}
        >
          <span style={{
            display: 'flex', alignItems: 'center', gap: gap.xs, padding: pad.tiny,
            borderRadius: radius.pill,
            // 튜토리얼 4칸(성격)은 이 버튼 안에서 하는 일이라, 타일 대신 여기가 깜빡인다.
            border: v.hud.hl ? `2px solid ${C.accent}` : `1px solid ${C2.lineWarm}`,
            background: C2.paperWarm, animation: v.hud.hl ? 'yNudge 1.9s ease-in-out infinite' : 'none',
            // ★ 튜토리얼이 다른 칸을 가리킬 때는 흐리게(판정 J8) — 눌리기는 하고, 누르면 이유가 뜬다.
            opacity: v.hud.off ? 0.45 : 1,
            fontSize: fz.sm, lineHeight: 1, color: C2.muted,
          }}>
            <span style={{ position: 'relative', width: 13, height: 13, display: 'block' }}>
              <span style={{ position: 'absolute', left: 0, top: 5.5, width: 13, height: 2, borderRadius: 2, background: 'currentColor' }} />
              <span style={{ position: 'absolute', left: 5.5, top: 0, width: 2, height: 13, borderRadius: 2, background: 'currentColor', transform: 'rotate(45deg)' }} />
            </span>
            아이 정보
          </span>
        </button>
        {/* 조각 도장은 여기 없다 — 좌측 하단 카드가 맡는다(2026-09-07 지시). */}
      </div>
    </>
  );
}

/**
 * 여울 샘플 방의 **머리 띠**. 진짜 방의 HUD 가 있어야 할 자리를 샘플 방도 똑같이 채운다.
 *
 * ★ 왜 무대 위가 아니라 무대 밖인가(2026-09-07 상훈님 지시 "너무 애매한 위치에 애매하게 있다")
 *   전에는 '정보 수정'·알 배지·안내 카드 셋이 **각각 무대 그림 위에 떠 있었다.** 서로 관계가 없어
 *   보이고, 창문과 아이 머리를 덮었다. 진짜 방에는 이미 머리 영역이 있고 무대가 깨끗한데,
 *   샘플 방만 `hud.show = !sampleMode` 로 그 자리가 비어 셋이 무대로 흘러내린 것이었다.
 *   그래서 같은 자리에 샘플 전용 띠를 만들어 셋을 들이고, **무대에는 아이만 남긴다.**
 *   여백·배경은 진짜 방 HUD 와 같은 값을 쓴다(좌우 20px · 셸 바탕).
 */
function SampleHud({ y }: { y: Yeoul }) {
  const { v } = y;
  // 좁은 폰에서는 띠 여백을 줄여 무대에 세로를 돌려준다(데스크톱은 그대로).
  const narrow = useIsWide(NARROW_Q);
  return (
    // 띠는 무대를 그만큼 잡아먹는다 — 아이가 주인공이라 여백을 최소로 잡았다.
    <div data-part="sample-hud" style={{ flex: 'none', display: 'flex', flexDirection: 'column', gap: narrow ? gap.xs : gap.sm, padding: narrow ? '4px 14px 4px' : '8px 20px 7px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: gap.sm }}>
        {/* ★ 누르는 자리 44px(판정 5) — 알약은 그대로, 띠 높이도 그대로(음수 여백). */}
        <button
          onClick={v.sample.exit} data-part="sample-exit"
          style={{ display: 'flex', alignItems: 'center', minHeight: TAP_MIN, margin: '-10px 0', padding: 0, border: 'none', background: 'none' }}
        >
          <span style={{ display: 'flex', alignItems: 'center', gap: gap.xs, padding: '5px 11px 5px 9px', borderRadius: radius.pill, border: `1px solid ${C2.lineWarm}`, background: C2.paperWarm, fontSize: fz.sm, lineHeight: 1, color: C2.muted }}>‹ 정보 수정</span>
        </button>
        <span style={{ flex: 1 }} />
        {/* 알은 여전히 눌러서 알 화면으로 간다. 띠 안으로 들어온 만큼 고리·후광은 걷어냈다. */}
        <button
          onClick={v.sample.forceHatch} data-part="sample-egg"
          style={{ display: 'flex', alignItems: 'center', gap: gap.sm, padding: '4px 11px 4px 5px', borderRadius: radius.pill, border: `1px solid ${C2.lineWarm}`, background: C2.paperWarm }}
        >
          <span style={{ width: 22, height: 22, borderRadius: '50%', background: v.sample.ring, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <span style={{ width: 17, height: 17, borderRadius: '50%', background: C.paper, display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' }}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={EGG_IMG.idle} alt="" style={{ width: 11, height: 13, objectFit: 'contain', display: 'block', animation: v.sample.eggAnim }} />
            </span>
          </span>
          <span style={{ fontSize: fz.sm, lineHeight: 1, color: C2.muted }}>{v.sample.eggNote}</span>
          <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint2 }}>{v.sample.eggCount}</span>
        </button>
      </div>
    </div>
  );
}

function ChatFab({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  return (
    <button
      onClick={(e) => { e.stopPropagation(); actions.openChat(); }}
      data-part="chat-fab" data-off={v.fab.off ? '1' : undefined} disabled={v.fab.off}
      style={{
        ...slotChip, position: 'relative',
        borderStyle: 'solid', borderWidth: v.fab.bw, borderColor: v.fab.bd,
        animation: v.fab.anim,
        // ★ 튜토리얼 중 다른 칸이면 **흐리게 둔다**(판정 J8) — 없애면 "사라졌다" 로 읽힌다.
        opacity: v.fab.off ? 0.45 : 1,
        cursor: v.fab.off ? 'default' : 'pointer',
      }}
      // ★★ 2026-09-22 — **`disabled` 를 붙인다.** 이제 이 단추는 튜토리얼 중 **진짜로 안 눌린다**
      //   (상훈님 판정: "나머지는 비활성해서 누르지 않게"). 속성이 사실과 맞으므로 낭독기·자동
      //   검사가 읽는 것도 사실이다 — 예전 금지는 "눌리는데 비활성으로 표시" 하던 경우였다.
      aria-label="대화하기"
    >
      <span style={{ position: 'relative', width: 24, height: 24, color: '#5A554E' }}>
        <span style={{ position: 'absolute', left: 1, top: 3, width: 22, height: 15, border: '2px solid currentColor', borderRadius: 8 }} />
        <span style={{ position: 'absolute', left: 6, top: 17, width: 7, height: 5, background: 'currentColor', borderRadius: '0 0 3px 3px', transform: 'skewX(-18deg)' }} />
        <span style={{ position: 'absolute', left: 7, top: 9, width: 3, height: 3, borderRadius: '50%', background: 'currentColor' }} />
        <span style={{ position: 'absolute', left: 13, top: 9, width: 3, height: 3, borderRadius: '50%', background: 'currentColor' }} />
      </span>
      {v.fab.dot && <span style={{ position: 'absolute', top: 1, right: 1, width: 11, height: 11, borderRadius: '50%', background: C.accent, border: `2px solid ${C.paper}` }} />}
    </button>
  );
}

/** 왼쪽 아래 작은 카드 — 튜토리얼 중엔 부름, 아니면 "다음에 배울 것". */
/**
 * 좌측 하단 카드 — 튜토리얼 부름 / 로드맵 / 조각 도장, 셋을 차례로 맡는다.
 *
 * ★ 평소엔 **요약만** 보이고, 누르거나 손을 올리면 위로 펴지며 설명이 나온다
 *   (2026-09-07 상훈님 지시). 모달로 방을 덮지 않고 **제자리에서** 펴는 쪽을 골랐다 —
 *   샘플 방 안내 카드가 이미 접기/펴기를 쓰고 있어 같은 문법이면 화면이 한 벌로 읽힌다.
 * ★ 펼침이 **위로** 자란다. 아래는 타일 자리라 내려갈 곳이 없어서 `bottom` 으로 붙였다.
 * ★ 팝오버·대화·시트가 열리면 이 카드는 **아예 사라진다**(`mini.show` 조건). 그래서 펼친 채로
 *   그것들과 겹칠 일이 없다 — 겹침을 막는 규칙을 따로 두지 않고 표시 조건 하나로 끝냈다.
 */
/**
 * 안내 카드의 손잡이 한 칸. **셋이 같은 결이라야 한 줄로 읽힌다.**
 * ★ 터치 타깃 36px 을 지킨다 — 예전 연습방 알약은 높이 21px 이라 손가락이 자주 빗나갔다.
 */
function TutChip({ label, onTap, primary = false, ...rest }: {
  label: string; onTap: () => void; primary?: boolean;
} & React.HTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...rest}
      onClick={(e) => { e.stopPropagation(); onTap(); }}
      style={{
        minHeight: 36, padding: pad.chip, borderRadius: radius.pill, fontSize: fz.md,
        border: primary ? 'none' : `1px solid ${C.lineHard}`,
        background: primary ? C.accent : C.slot,
        color: primary ? C.accentInk : C.sub,
      }}
    >{label}</button>
  );
}

function MiniCard({ y }: { y: Yeoul }) {
  const m = y.v.mini;
  // ★ 펼침은 **훅이 들고 있다**(2026-09-22 판정 K). 여기 두면 카드가 사라졌다 다시 뜰 때마다
  //   접힌 채로 돌아온다 — 팝오버 한 번만 열어도 펼쳐 둔 목록이 닫혔다.
  const open = m.open;
  const more = `yeoul-mini-more${open ? ' is-open' : ''}`;
  const canOpen = m.hasGoal || m.hasShards;

  return (
    <div
      className="yeoul-mini"
      onClick={(e) => { e.stopPropagation(); if (canOpen) m.toggle(); }}
      data-part="mini" data-open={open ? '1' : '0'}
      style={{
        // ★ 이제 **선반 위 한 칸**이다(2026-09-20) — 구석에 따로 떠 있지 않는다. 펼침은 그대로
        //   위로 자란다(선반이 아래에 붙어 있어 내려갈 곳이 없는 것은 예전과 같다).
        flex: 1, minWidth: 0, alignSelf: 'stretch',
        display: 'flex', flexDirection: 'column', justifyContent: 'center', gap: gap.sm,
        padding: '8px 11px', borderRadius: radius.md,
        background: C.slot, border: `1px solid ${C.lineHard}`,
        cursor: canOpen ? 'pointer' : 'default',
      }}
    >
      {m.isTut && (
        <span data-part="tutor" style={{ display: 'flex', flexDirection: 'column', gap: gap.sm }}>
          <span style={{ display: 'flex', alignItems: 'baseline', gap: gap.sm }}>
            <span style={{ fontFamily: GAEGU, fontSize: fz.lg, lineHeight: 1.3, color: C.ink }}>{m.tutText}</span>
            <span style={{ flex: 1 }} />
            <span data-part="tut-step" style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint2, whiteSpace: 'nowrap' }}>{m.tutStep}</span>
          </span>
          {/* ★ 손잡이 한 줄. **연습방과 진짜 방이 같은 자리·같은 크기**를 쓴다(2026-09-21 A-07).
              예전엔 연습방이 11.5px 알약 셋(높이 21px), 진짜 방이 12.5px 알약 하나(높이 36px)로 따로 놀았다.
              ★ 서버 튜토리얼에는 '나중에' 가 없다 — 건너뛸 방법이 서버에 없어서, 눌러도 아무 일이
              안 나면 고장으로 읽힌다. 마지막 칸에서만 "이제 시작할게요" 가 나온다. */}
          {(m.tutPrev.show || m.tutNext.show || m.tutBtn.show || m.tutHint) && (
            <span style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: gap.xs }}>
              {m.tutPrev.show && <TutChip label="이전" onTap={m.tutPrev.tap} data-tutor-prev />}
              {m.tutNext.show && <TutChip label={m.tutNext.label} onTap={m.tutNext.tap} primary data-tutor-next />}
              {m.tutBtn.show && <TutChip label={m.tutBtn.label} onTap={m.tutBtn.tap} data-action="tut-btn" />}
              {m.tutHint && <span style={{ fontSize: fz.sm, color: C.faint2 }}>{m.tutHint}</span>}
            </span>
          )}
        </span>
      )}

      {/* ── 로드맵 ── 접히면 다음 하나만, 펴면 넷 전부 */}
      {m.hasGoal && (
        <>
          {/* 접혔을 때만 보이는 요약 한 줄 — 펴지면 CSS 가 감춘다(→ `ui.ts` `.yeoul-mini-sum`).
              목록 첫 줄과 같은 내용이라, 같이 띄우면 같은 말이 두 번 뜬다. */}
          <span className="yeoul-mini-sum" data-part="mini-sum" style={{ alignItems: 'baseline', gap: gap.sm }}>
            <span style={{ fontSize: fz.sm, color: C.ink }}>{m.name}</span>
            <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint2 }}>{m.cond}</span>
          </span>
          <span className={more} data-part="mini-more" style={{ flexDirection: 'column', gap: gap.xs, paddingTop: 2 }}>
            <span style={{ fontSize: fz.xs, color: C.faint2 }}>배울 것</span>
            {m.goals.map((g) => (
              <span key={g.name} style={{ display: 'flex', alignItems: 'baseline', gap: gap.sm }}>
                <span style={{ fontSize: fz.sm, color: g.done ? C.faint2 : C.ink, textDecoration: g.done ? 'line-through' : 'none' }}>{g.name}</span>
                <span style={{ flex: 1 }} />
                <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint2, whiteSpace: 'nowrap' }}>{g.cond}</span>
              </span>
            ))}
          </span>
        </>
      )}

      {/* ── 조각 ── 접히면 도장 넷만(이름 없이), 펴면 조건 한 줄씩 */}
      {m.hasShards && (
        <>
          <span data-part="shards" data-part-sum="1" style={{ display: 'flex', alignItems: 'center', gap: gap.sm }}>
            <span style={{ display: 'flex', gap: gap.xs }}>
              {m.shards.map((x) => (
                <span key={x.label} data-shard={x.label} data-on={x.on ? '1' : '0'}
                  style={{
                    width: 13, height: 13, borderRadius: 3, transform: 'rotate(45deg)',
                    border: `1px solid ${x.on ? C.accent : ink(.22)}`,
                    background: x.on ? C.accentSoft : C.slotDim,
                  }} />
              ))}
            </span>
            <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint2 }}>조각 {m.shardCount}</span>
          </span>
          <span className={more} data-part="mini-more" style={{ flexDirection: 'column', gap: gap.xs, paddingTop: 2 }}>
            {m.shards.map((x) => (
              <span key={x.label} style={{ display: 'flex', alignItems: 'baseline', gap: gap.sm }}>
                <span style={{ width: 8, height: 8, flex: 'none', borderRadius: 2, transform: 'rotate(45deg)', border: `1px solid ${x.on ? C.accent : ink(.22)}`, background: x.on ? C.accentSoft : C.slotDim }} />
                <span style={{ fontSize: fz.sm, color: x.on ? C.ink : C.sub2 }}>{x.label}</span>
                <span style={{ flex: 1 }} />
                <span style={{ fontSize: fz.xs, color: C.faint2, whiteSpace: 'nowrap' }}>{x.cond}</span>
              </span>
            ))}
            <span style={{ fontSize: fz.xs, lineHeight: 1.5, color: C.faint2 }}>잠들 때 세어 보고 다시 시작해요</span>
          </span>
        </>
      )}
    </div>
  );
}

/** 여울이 하나씩 묻는 설문. 온보딩 칸이 아니라 샘플 방 안에서 묻는다(시안 확정). */
/**
 * 여울이 하나씩 묻는 창.
 *
 * ★ '여울이 물어봐요' 딱지를 뺐다(2026-09-07 상훈님 지시). 딱지 밑에 '연령대' 같은 항목 이름이
 *   붙어 있으니 설문지로 읽혔다. 말하는 사람은 **말투로** 드러나야지 라벨로 붙이는 게 아니다.
 *   그래서 묻는 말이 카드의 첫 줄이고, 진행(1/6)은 답을 다 읽은 뒤 눈에 걸리도록 맨 아래 구석에 둔다.
 * ★ 무대의 말풍선과 헷갈리면 안 되므로 카드 꼴(둥근 모서리·그림자)은 그대로 둔다.
 */
function AskCard({ y }: { y: Yeoul }) {
  const a = y.v.ask;
  return (
    <div
      data-part="ask"
      className="yeoul-ask"
      onClick={(e) => e.stopPropagation()}
      style={{
        width: '100%', boxSizing: 'border-box', padding: '15px 15px 11px', borderRadius: radius.lg,
        background: C.paper, border: `1px solid ${C.lineSoft}`, boxShadow: shadow.pop,
        display: 'flex', flexDirection: 'column', gap: gap.md, animation: 'yPopIn .2s cubic-bezier(.2,.9,.25,1)',
      }}
    >
      <span style={{ fontFamily: GAEGU, fontSize: fz.xl, lineHeight: 1.35, color: C.ink }}>{a.text}</span>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: gap.sm }}>
        {a.opts.map((o) => (
          <button
            key={o.text} onClick={o.pick} data-ask-opt={o.text} className="yeoul-ask-opt"
            style={{
              display: 'flex', flexDirection: 'column', alignItems: 'center', gap: gap.xs,
              padding: o.note ? '7px 13px' : '9px 14px', borderRadius: radius.pill,
              border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: fz.md, color: C.ink,
            }}
          >
            <span>{o.text}</span>
            {/* 말은 크게, 시각은 작고 흐리게 — 욕실 팝오버의 '목욕 / 오늘 1회' 와 같은 규칙. */}
            {o.note && <span style={{ fontSize: fz.xs, opacity: 0.75 }}>{o.note}</span>}
          </button>
        ))}
      </div>

      {/* 넷 중에 없을 수 있는 문항(호칭)은 직접 적는 줄을 함께 둔다. */}
      {a.hasInput && (
        <div style={{ display: 'flex', alignItems: 'center', gap: gap.sm }}>
          <input
            value={a.draft} onChange={(e) => a.onDraft(e.target.value)} maxLength={a.inputMax}
            placeholder={a.inputPh} data-ask-input
            // 조합이 끝나는 순간 한 번 더 적어 둔다 — 조합 중에 상태가 한 글자 뒤처져도 여기서 맞춰진다.
            onCompositionEnd={(e) => a.onDraft(e.currentTarget.value)}
            // 마지막 한글을 확정하려고 누른 Enter 는 '넘기기' 가 아니다(대화 입력칸과 같은 규칙).
            onKeyDown={(e) => {
              if (e.key !== 'Enter') return;
              if (e.nativeEvent.isComposing || e.keyCode === 229) return;
              if (a.hasConfirm) a.confirm();
            }}
            style={{
              flex: 1, minWidth: 0, padding: pad.chip, borderRadius: radius.pill,
              border: `1px solid ${C.lineHard}`, background: C.paper, fontSize: fz.md, color: C.ink, outline: 'none',
            }}
          />
          {a.hasConfirm && (
            <button
              onClick={a.confirm} data-ask-confirm
              style={{ flex: 'none', padding: pad.chip, borderRadius: radius.pill, border: 'none', background: C.accent, color: C.accentInk, fontSize: fz.md, whiteSpace: 'nowrap' }}
            >이렇게 불러 주세요</button>
          )}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'center', gap: gap.sm }}>
        <button onClick={a.skip} data-ask-skip style={{ padding: '4px 2px', border: 'none', background: 'none', fontSize: fz.sm, color: C.faint2 }}>나중에</button>
        <span style={{ flex: 1 }} />
        <span style={{ font: `${monoSize.xs}px ${MONO}`, color: C.faint2 }}>{a.step}</span>
      </div>
    </div>
  );
}

/**
 * 대화 — 시트가 아니라 타일 위에 뜨는 한 줄(9/6 상훈님 결정: 대화는 놀이 밖 독립).
 *
 * ★★ 입력칸을 **리액트가 붙들지 않는다**(2026-09-11, 상훈님 "'그냥하고 있어' 를 쳤는데 '어' 만 갔다").
 *   한글은 자판을 누를 때마다 글자가 확정되는 게 아니라 **조합(IME)** 을 거친다. 조합 중에는
 *   브라우저가 입력칸 안에 아직 확정되지 않은 글자를 들고 있는데, 리액트가 `value` 로 그 칸을
 *   붙들고 있으면 조합 도중의 **되돌려쓰기 한 번**에 조합 버퍼가 끊긴다. 그러면 앞 글자가 날아가고
 *   마지막으로 조합하던 한 글자만 남는다 — 정확히 상훈님이 보신 모습이다.
 *   그래서 값은 브라우저에 맡기고(`defaultValue`), 리액트는 **읽기만** 한다.
 *   상태(`draft`)는 그대로 따라 적어 둔다 — 다른 곳에서 쓰던 값이라 끊지 않는다.
 *
 * ★ 보낼 때도 **칸이 지금 들고 있는 글자**를 그대로 집어 보낸다. 화면에 보이는 것과 보내는 것이
 *   다르면 아무 소리도 안 나고 사용자만 잘린 말을 본다.
 * ★ Enter 는 **조합 중이면 무시**한다. 한글에서 마지막 글자를 확정하려고 누른 Enter 까지
 *   보내기로 받으면, 확정 전의 글자로 보내 버린다.
 */
function ChatBar({ y }: { y: Yeoul }) {
  const { v, actions } = y;
  const box = useRef<HTMLInputElement>(null);
  const composing = useRef(false);
  const send = () => {
    const el = box.current;
    actions.onSend(el?.value ?? '');
    if (el) el.value = '';
  };
  return (
    <div
      data-part="chat-bar"
      onClick={(e) => e.stopPropagation()}
      style={{ width: '100%', display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: gap.sm, animation: v.chat.anim }}
    >
      {/* ★★ **아이 말은 무대 말풍선 하나뿐이다**(2026-09-22 상훈님 판정 2안 — *"캐릭터 위의
          말풍선만 있으면 돼"*). 2026-09-20 에 이 자리에 두던 '아이 말' 한 줄을 걷어냈다 —
          같은 문장이 무대와 입력칸 위에 **두 번** 떠서, 어느 쪽이 아이가 지금 하는 말인지
          흐려졌다. 아이 말은 **아이 위에** 있어야 "아이가 나한테 말을 건다" 가 된다.
          ★ **내가 쓴 줄은 남긴다** — 이건 아이 말이 아니라 내가 무엇을 보냈는지 확인하는
          유일한 자리다(입력칸은 보내는 순간 비워진다). 판정은 "아이 말은 무대에만" 까지였다. */}
      {v.chat.hasMine && (
        <span data-part="chat-mine" style={{ display: 'flex', alignItems: 'center', gap: gap.sm, maxWidth: '82%', padding: '7px 13px', borderRadius: radius.pill, background: C.accentSoft, border: `1px solid ${acc(.22)}`, animation: 'yPopIn .2s cubic-bezier(.2,.9,.25,1)' }}>
          <span style={{ fontFamily: GAEGU, fontSize: fz.lg, lineHeight: 1.2, color: '#8B3A2C' }}>{v.chat.mine}</span>
          <span style={{ width: 5, height: 5, borderRadius: '50%', background: acc(.45) }} />
        </span>
      )}
      <div style={{ width: '100%', boxSizing: 'border-box', display: 'flex', alignItems: 'center', gap: gap.sm, padding: '7px 7px 7px 15px', borderRadius: radius.pill, background: C.paper, border: `1.5px solid ${C.ink}`, boxShadow: shadow.card }}>
        {/* 열린 부름이 없거나 보내는 중이면 적을 수 없다 — 자리표시글이 이유를 말한다. */}
        <input
          ref={box} defaultValue="" onChange={(e) => actions.onDraft(e.target.value)} maxLength={CHAT_MAX}
          placeholder={v.chat.hint} disabled={!v.chat.can} data-part="chat-input"
          onCompositionStart={() => { composing.current = true; }}
          onCompositionEnd={(e) => { composing.current = false; actions.onDraft(e.currentTarget.value); }}
          onKeyDown={(e) => {
            if (e.key !== 'Enter') return;
            // 조합을 확정하려고 누른 Enter 다 — 보내기가 아니다(브라우저마다 신호가 달라 셋 다 본다).
            if (composing.current || e.nativeEvent.isComposing || e.keyCode === 229) return;
            send();
          }}
          style={{ flex: 1, minWidth: 0, border: 'none', background: 'none', fontSize: fz.md, color: C.ink, outline: 'none' }}
        />
        {/* ★ 누르는 자리 44px(판정 5) — 동그라미는 28 그대로 두고 단추만 키운다(줄 높이 유지). */}
        <button
          onClick={actions.closeChat} aria-label="대화 닫기"
          style={{ width: TAP_MIN, height: TAP_MIN, margin: '-8px 0', flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'center', border: 'none', background: 'none', padding: 0 }}
        >
          <span style={{ width: 28, height: 28, borderRadius: radius.pill, border: `1px solid ${C.lineHard}`, background: C.slot, fontSize: fz.sm, color: C.sub2, lineHeight: 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>✕</span>
        </button>
        <button
          onClick={send} disabled={!v.chat.canSend} data-action="chat-send"
          style={{
            // ★ 누르는 자리 44px(판정 5) — 알약이 5px 두꺼워질 뿐 모양은 그대로다.
            flex: 'none', minHeight: TAP_MIN, padding: pad.chip, borderRadius: radius.pill, border: 'none',
            background: v.chat.canSend ? C.accent : C.off, color: v.chat.canSend ? C.accentInk : C2.dim2, fontSize: fz.md,
          }}
        >보내기</button>
      </div>
    </div>
  );
}

/**
 * 팝오버 — 누른 타일 바로 위에 뜨고, 꼬리가 그 타일을 가리킨다.
 *
 * ★ 자리는 **실제 타일을 재서** 정한다(2026-09-10). 예전에는 칸 번호로 `left: 50%` 를 주고
 *   `translateX(-50%)` 로 당기면서, 넘칠 때의 물림(clamp)을 **첫 칸과 마지막 칸에만** 걸어 두었다.
 *   그래서 2번째(욕실)는 왼쪽으로, 4번째(침실)는 오른쪽으로 대칭으로 잘렸다
 *   (360px 에서 각각 13px · 390px 에서 4px. 430 이상은 넉넉해서 안 드러났다).
 *   칸 번호로 예외를 더 두면 타일이 늘거나 순서가 바뀔 때 같은 자리에서 또 깨진다.
 *   그래서 **모든 팝오버를 무대 안으로 물린다** — 예외 없는 규칙 하나로.
 *
 * ★ 재는 것은 `useLayoutEffect` 다. 그려지기 **전에** 자리를 잡아야 눈에 띄는 튐이 없다.
 * ★ 겉(자리)과 속(나타나는 동작)을 두 겹으로 나눈 것은 그대로다 — 한 요소에 인라인 `transform` 과
 *   `animation` 을 같이 걸면 키프레임이 인라인 값을 덮어 팝오버가 엉뚱한 자리에 떴다가
 *   끝나는 순간 튀어 들어온다(2026-09-07 실측: 앨범 타일에서 215px 순간이동).
 */
function Popover({ y, compact = false }: { y: Yeoul; compact?: boolean }) {
  const p = y.v.pop;
  const room = y.v.selK;
  const box = useRef<HTMLDivElement>(null);
  const [geo, setGeo] = useState<{ left: number; tail: number } | null>(null);

  useLayoutEffect(() => {
    const el = box.current;
    const host = el?.parentElement;
    if (!el || !host) return undefined;
    const place = () => {
      const tile = document.querySelector(`[data-room="${room}"]`);
      const c = host.clientWidth;
      const w = el.offsetWidth;
      if (!c || !w) return;
      // 가리킬 곳 = 그 타일의 한가운데(무대 좌표). 못 찾으면 한가운데로 둔다.
      const hostL = host.getBoundingClientRect().left;
      const center = tile
        ? (tile.getBoundingClientRect().left + tile.getBoundingClientRect().width / 2) - hostL
        : c / 2;
      // 무대 밖으로 나가지 않게 물린다. 무대가 팝오버보다 좁으면 왼쪽에 붙인다.
      const left = Math.max(0, Math.min(center - w / 2, Math.max(0, c - w)));
      // 꼬리는 타일을 계속 가리키되, 모서리를 넘어가지 않게 안쪽으로 물린다.
      const tail = Math.max(14, Math.min(center - left, w - 14));
      setGeo({ left, tail });
    };
    place();
    const ro = new ResizeObserver(place);
    ro.observe(host);
    ro.observe(el);
    return () => ro.disconnect();
  }, [room]);

  return (
    // ★ 자리 잡기(겉)와 나타나는 동작(속)을 **두 겹으로 나눈다.**
    //   한 요소에 인라인 `transform: translateX` 와 `animation: yPopIn` 을 같이 걸면,
    //   키프레임도 transform 을 건드리기 때문에 재생되는 0.2초 동안 인라인 값이 통째로 덮이고
    //   팝오버가 엉뚱한 자리(오른쪽 끝 타일이면 화면 밖)에 떴다가 끝나는 순간 튀어 들어온다.
    //   실측: 앨범 타일에서 left 344 → 129 로 215px 순간이동(2026-09-07).
    //   키프레임에 translateX 를 박는 방법은 안 쓴다 — 타일마다 값이 달라 키프레임이 다섯 벌 된다.
    <div
      ref={box}
      data-part="pop"
      onClick={(e) => e.stopPropagation()}
      style={{
        position: 'relative', width: 'min(252px,92%)',
        // 재기 전 첫 그림은 한가운데. `useLayoutEffect` 가 그려지기 전에 제자리로 옮긴다.
        left: geo ? geo.left : 0,
      }}
    >
    <div
      data-part="pop-card"
      // ★ 짧은 화면(`compact`)은 여백·글자를 조금씩 줄여 팝오버 높이를 낮춘다(색·모양·구성은 그대로).
      //   낮아진 만큼 발끝선 예약(`FOOT_FLOOR_SHORT`)도 낮춰 아이가 커진다. 버튼 터치 타깃은 유지한다.
      style={{
        position: 'relative', width: '100%',
        padding: compact ? '8px 12px' : '12px 13px', boxSizing: 'border-box', borderRadius: radius.lg,
        background: C.paper, border: `1px solid ${C.lineSoft}`, boxShadow: shadow.pop,
        display: 'flex', flexDirection: 'column', gap: compact ? gap.sm : gap.md, animation: p.anim,
      }}
    >
      <span style={{ display: 'flex', flexDirection: 'column', gap: compact ? gap.xs : gap.sm }}>
        <span style={{ display: 'flex', alignItems: 'baseline', gap: gap.sm }}>
          <span style={{ fontFamily: GAEGU, fontSize: compact ? fz.lg : fz.xl, lineHeight: compact ? 1.15 : 1.25, color: C.ink }}>{p.say}</span>
          <span style={{ flex: 1 }} />
          <span style={{ font: `${monoSize.sm}px ${MONO}`, color: C.faint }}>{p.count}</span>
        </span>
        {p.hasBar && (
          <span style={{ display: 'flex', gap: gap.xs }}>
            {p.bar.map((g, i) => <span key={i} style={{ flex: 1, height: compact ? 9 : 11, borderRadius: radius.pill, background: g.bg }} />)}
          </span>
        )}
      </span>
      <span style={{ display: 'flex', flexDirection: 'column', gap: compact ? gap.xs : gap.sm }}>
        {/* ★ '여기서 ○○ 누르기' 안내 줄은 없앴다(2026-09-07 상훈님 지시).
            같은 말을 세 번 하고 있었다 — 위 안내 카드가 무엇을 할지 말하고, 대상 버튼이 깜빡인다.
            깜빡임(yBlink)은 남긴다. 글자 없이 가리킬 수 있는 유일한 수단이라 그것까지 없애면 못 찾는다. */}
        {p.a && <PopButton b={p.a} compact={compact} />}
        {p.hasB && p.b && <PopButton b={p.b} compact={compact} />}
      </span>
      <span data-part="pop-tail" style={{ position: 'absolute', left: geo ? geo.tail : '50%', bottom: -6, width: 12, height: 12, background: C.paper, borderRight: `1px solid ${C.lineSoft}`, borderBottom: `1px solid ${C.lineSoft}`, transform: 'translateX(-50%) rotate(45deg)' }} />
    </div>
    </div>
  );
}

function PopButton({ b, compact = false }: { b: NonNullable<Yeoul['v']['pop']['a']>; compact?: boolean }) {
  return (
    // ★ 진짜 `disabled` 다(계약 10절 "거절될 버튼은 미리 잠가 둔다"). 회색으로만 칠하고 눌리게 두면
    //   눌러 봐야 왜 안 되는지 알 수 있고, 서버에는 나갈 필요 없던 요청이 나간다.
    //   ⚠️ `aria-disabled` 를 늘 달지 않는다 — `"false"` 도 검사 도구에 '비활성' 으로 읽힌다.
    //   ★ 짧은 화면(`compact`)은 세로 여백만 살짝 줄인다(13→10px). 글자·터치 폭은 그대로라 여전히 잘 눌린다.
    <button
      onClick={b.tap} data-action={b.label} disabled={b.off}
      data-off={b.off ? '1' : undefined} data-why={b.why || undefined}
      style={{ display: 'flex', alignItems: 'center', gap: gap.sm, padding: compact ? '10px 14px' : '13px 14px', borderRadius: radius.md, border: b.bd, background: b.bg, color: b.fg, textAlign: 'left', animation: b.anim, cursor: b.off ? 'default' : 'pointer' }}
    >
      <span style={{ fontSize: fz.lg }}>{b.label}</span>
      <span style={{ flex: 1 }} />
      {/* ★ 남은 횟수와 **못 누르는 이유**를 다른 결로 그린다(2026-09-21 A-20).
          예전엔 둘이 같은 칸·같은 색이라 "4번 남음" 과 "아플 땐 간식을 안 먹어요" 가 구분되지 않았다.
          ★ 색만으로 가르지 않는다(렌즈 §5-5) — 이유 앞에 모양 표시를 하나 세운다.
          숫자는 고정폭이라 자릿수가 바뀌어도 자리가 안 흔들린다. */}
      {b.why
        ? (
          <span data-note="why" style={{ display: 'flex', alignItems: 'center', gap: gap.xs, minWidth: 0 }}>
            <span aria-hidden style={{ flex: 'none', fontSize: fz.xs, lineHeight: 1, color: C.accent }}>{LV.off.shape}</span>
            <span style={{ fontSize: fz.sm, lineHeight: 1.35, color: C.accent, textAlign: 'right' }}>{b.why}</span>
          </span>
        )
        : <span style={{ font: `${monoSize.sm}px ${MONO}`, color: b.subFg }}>{b.count}</span>}
    </button>
  );
}
