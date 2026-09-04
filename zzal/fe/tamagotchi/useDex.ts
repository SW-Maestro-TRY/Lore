// 도감 한 판을 만드는 훅. 서버가 준 목록을 스킨이 그대로 그릴 수 있는 모양으로 내놓는다.
//
// ★ 왜 프론트 상수(MOVES 13개)를 안 쓰는가
//   무엇을 몇 개 열지는 서버 설정(app.zzal.motions) 하나가 정본이다. 화면이 칸 수를 자기가
//   정하면 설정에 2개만 들어 있어도 13칸을 그리고, 사용자는 **영영 안 채워질 11칸**을 본다.
//   그래서 총 칸 수는 서버 total 이고, 붙이는 순간 칸이 줄어드는 것이 정상이다.
//
// ★★ 안 연 것의 이름은 쓰지 않는다
//   서버가 잠긴 자리의 이름을 아예 안 내려준다. 생성이 실패하면 다른 동작으로 갈아끼워야 하는데
//   이름을 미리 약속해 버리면 그때 어길 말이 생긴다(ZzalMotion·constants.ts 주석의 원칙).
//   그래서 잠긴 칸은 **이름 없는 빈 자리**이고, 여울이가 대신 서 있다.
//
// ★ 하나도 없으면 완전히 빈다. 그것도 정상 상태다 — 스킨이 "재우면 하나씩 늘어나요" 를
//   말해 주기 때문에, 여기서 가짜 칸을 채워 넣어 빈 화면을 감추지 않는다.

'use client';

import { useEffect, useState, type CSSProperties } from 'react';
import { assetUrl } from '../lib/assets';
import { copyImageLink, downloadImage, imageFileName } from '../lib/download';
import { getDex, type OpenedMotion } from '../lib/_v1/motion';

/** 스크랩북의 종이 결. 카드가 한 장씩 삐뚤게 붙어 있는 각도(도). */
const DEXROT = [-1.4, .9, -.6, 1.3, -1.1, .7, -1.6, 1.1, -.8, 1.5, -1, .6];

const GAEGU = "'Gaegu',cursive";
const INK = '#3A352B';

/** 스킨이 그대로 그리는 카드 한 장. 모양을 바꾸면 Scrapbook 의 JSX 가 같이 바뀌어야 한다. */
export interface DexCard {
  /** 잠긴 칸은 빈 문자열이다(이름을 미리 약속하지 않는다). */
  name: string;
  open: boolean;
  locked: boolean;
  img: string;
  cardStyle: CSSProperties;
  mediaStyle: CSSProperties;
  imgStyle: CSSProperties;
  nameStyle: CSSProperties;
  save: () => void;
  share: () => void;
}

export interface UseDexOptions {
  /** 볼 펫. 없으면(비로그인·아직 못 물어봄) 아무것도 안 부르고 빈 도감이 된다. */
  petId: number | null;
  /**
   * 지금까지 연 개수. 값이 바뀌면 다시 물어본다.
   *
   * ★ 폴링하지 않는 이유 — 도감이 늘어나는 순간은 **깨우기 한 곳뿐**이고, 그때 펫 상태의
   *   unlockedCount 가 올라간다. 그 값만 보고 있으면 3초마다 두드릴 필요가 없다.
   */
  unlocked: number;
  /** 가로로 펼친 화면인가. 기울기를 얼마나 살릴지가 달라진다(세로에서는 덜 기울인다). */
  pc: boolean;
  /** 잠긴 칸에 대신 세울 그림. 스킨의 기본 캐릭터 그림을 넘긴다. */
  fallbackImg: string;
  /** 한마디 띄우기. 저장·공유가 쓴다. */
  say: (message: string) => void;
  /**
   * 펫 이름. 받은 파일 이름에 들어간다.
   *
   * ★ 여러 아이를 키우게 되면(유료 슬롯) 받은 파일이 전부 같은 이름이 되어
   *   어느 아이 것인지 구분이 안 된다. 지금은 한 마리지만 이름을 넣어 둔다.
   */
  petName?: string | null;
}

/**
 * 도감 카드 목록. 길이가 곧 총 칸 수(서버 total)다.
 *
 * 아직 못 물어봤거나 펫이 없으면 빈 배열이다 — 스킨은 그때 "재우면 하나씩 늘어나요" 를 띄운다.
 */
export function useDex({ petId, unlocked, pc, fallbackImg, say, petName }: UseDexOptions): DexCard[] {
  const [opened, setOpened] = useState<OpenedMotion[]>([]);
  const [total, setTotal] = useState(0);

  useEffect(() => {
    if (petId === null) {
      setOpened([]);
      setTotal(0);
      return;
    }

    let alive = true;
    const controller = new AbortController();

    getDex(petId, controller.signal)
      .then((dex) => {
        if (!alive) return;
        setOpened(dex.opened);
        setTotal(dex.total);
      })
      .catch(() => {
        // ★ 여기서 화면에 에러를 띄우지 않는다. 도감은 곁다리라, 못 읽었다고 다마고치 화면
        //   전체에 경고를 올리면 정작 할 수 있는 일(밥·쓰다듬)까지 방해한다. 빈 도감으로 둔다.
      });

    return () => {
      alive = false;
      controller.abort();
    };
  }, [petId, unlocked]);

  // 총 칸을 그리고 앞에서부터 연 것으로 채운다. 나머지는 이름 없는 잠긴 칸이다.
  //
  // ★ total 이 목록보다 작을 수 있다 — 설정에서 동작을 빼면 이미 연 것이 그대로 남는다.
  //   그때는 연 것을 지우지 않고 칸을 늘린다(이미 사용자가 가진 것을 화면에서 빼앗지 않는다).
  return Array.from({ length: Math.max(total, opened.length) }, (_, i) => {
    const motion = opened[i] ?? null;
    const open = motion !== null;
    // ★ 화면에는 label 을 쓴다. name 은 실험의 블록 파일명("교감1_머리쓰다듬")이라
    //   갈래·번호 같은 내부 사정이 그대로 드러난다.
    const name = motion?.label ?? '';
    return {
      name,
      open,
      locked: !open,
      // 잠긴 칸에는 자물쇠 대신 기본 그림이 흐리게 선다.
      img: motion ? assetUrl(motion.imageKey) : fallbackImg,
      cardStyle: {
        position: 'relative', background: open ? '#FFFEFA' : 'rgba(252,249,238,.75)',
        border: '1px solid ' + (open ? '#EDE6D4' : '#E2DAC4'), borderRadius: 2,
        overflow: 'hidden', padding: 4,
        boxShadow: open ? '3px 4px 0 rgba(58,53,43,.1)' : 'none',
        // ★ 칸 수가 각도 표보다 많아질 수 있으므로 나머지로 돌려 쓴다. 그냥 인덱스로 집으면
        //   13번째 칸에서 undefined 가 나와 rotate(NaNdeg) 가 되고, 카드가 통째로 안 그려진다.
        transform: `rotate(${(DEXROT[i % DEXROT.length] * (pc ? 1 : .45)).toFixed(2)}deg)`,
      } as CSSProperties,
      mediaStyle: {
        position: 'relative', aspectRatio: '313 / 350', background: '#F2EDDD', overflow: 'hidden',
      } as CSSProperties,
      imgStyle: {
        width: '100%', height: '100%', objectFit: 'contain', display: 'block',
        filter: open ? 'none' : 'grayscale(.75) opacity(.42)',
      } as CSSProperties,
      nameStyle: {
        fontFamily: GAEGU, fontWeight: 700, fontSize: 15, color: open ? INK : '#A79C82',
      } as CSSProperties,
      // ★ 어느 갈래로 끝나든 반드시 한마디를 띄운다. 아무 일도 안 일어나는 버튼이 가장 나쁘다 —
      //   사용자는 자기 손이 빗나간 줄 알고 계속 누르고, 우리는 아무것도 모른다.
      //   되는 경우·아이폰이라 새 탭으로 연 경우·아직 그림이 없는 경우가 전부 다른 말이어야 한다.
      save: () => {
        if (!motion) return;
        // ★ await 없이 곧바로 부른다. downloadImage 안의 iOS 갈래가 새 탭을 여는데,
        //   여기서 한 번이라도 기다렸다 부르면 "사용자가 누른 것" 자격을 잃어 팝업으로 막힌다.
        void downloadImage(assetUrl(motion.imageKey), imageFileName(petName || 'lore', name)).then((r) => {
          if (r.outcome === 'saved') say(name + ' 저장했어요');
          else if (r.outcome === 'opened') say('새 탭에서 열었어요. 그림을 꾹 눌러 저장해 주세요');
          // 가짜 생성이거나 만들다 실패하면 S3 에 파일이 없다. 고장이 아니라 아직인 것이라 말이 다르다.
          else if (r.code === 'http_404') say('아직 그림이 준비되지 않았어요');
          else if (r.code === 'popup_blocked') say('새 탭이 막혀 있어요. 팝업을 허용해 주세요');
          else say('저장하지 못했어요. 잠시 뒤 다시 눌러 주세요');
        });
      },
      // ★ 지금은 그림 주소를 복사하는 데까지다. X 공유·OG 카드는 **인증 없이 남의 펫이 보이는
      //   새 표면**을 만드는 일이라 따로 정해야 한다. 그 페이지가 생기면 복사할 주소만 바뀐다.
      share: () => {
        if (!motion) return;
        void copyImageLink(assetUrl(motion.imageKey)).then((r) => {
          // 클립보드가 막힌 브라우저에서는 주소창을 띄워 직접 복사하게 했다. 말이 달라야 한다.
          if (r.manual) say('주소를 직접 복사해 주세요');
          else if (r.ok) say('링크를 복사했어요');
          else say('링크를 복사하지 못했어요');
        });
      },
    };
  });
}
