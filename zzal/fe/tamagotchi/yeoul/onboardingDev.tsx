// zzal 온보딩 "기존 ↔ 적용후" 비교 장치 — 변경 단위(OB-01~09) on/off 상태 + dev 전용 "변경 목록" 패널.
//
// 왜 이렇게 하나
//   방향서(온보딩-방-통일디자인방향-0918.md)의 온보딩 변경들을 한 번에 갈아치우지 않고
//   **변경 단위별로 토글**해, 상훈님이 한 화면에서 기존과 적용후를 하나씩 켜 보며 고르게 한다.
//   Onboarding.tsx 가 이 모듈의 useOnbFlag(id) 로 자기 플래그를 읽어 기존/적용후 둘 중 하나를 그린다.
//   (랜딩 v1 의 landingDev.tsx 와 같은 방식. 여기는 온보딩 전용이라 버전 선택기 없이 토글만 둔다.)
//
// 게이팅
//   패널은 useDevVisible() 로만 뜬다 — 공개 도메인(*.lorecomic.com)에선 절대 안 뜨고
//   로컬·테일넷 dev 에서만 뜬다. 새 게이팅을 만들지 않고 tamagotchi/useDevVisible 을 그대로 쓴다.
//
// 상태 저장
//   per-viewer localStorage(try/catch). 첫 렌더는 늘 전부 OFF(기존 화면) — 서버·브라우저 렌더가
//   갈리면 하이드레이션 경고가 나므로(useDevVisible 과 같은 이유) useEffect 로 뒤늦게 불러온다.
//
// ★ 불변식 — 이 토글들은 **겉모습만** 바꾼다. 핸들러·data-action·서버 호출·상태·화면 이동은 전부 보존한다.
'use client';

import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from 'react';
import { useDevVisible } from '../useDevVisible';
import { C, GAEGU, MONO, radius } from './ui';

export interface OnbUnit {
  id: string;
  label: string;
  /** 무엇이 바뀌나 한 줄. */
  desc: string;
}

/** 방향서 부록 D → 온보딩 변경 단위 9개. 순서 = 화면 위→아래(셸·질감이 먼저). */
export const ONB_UNITS: OnbUnit[] = [
  { id: 'ob-01', label: '셸 질감', desc: '밋밋한 단색 배경 → 은은한 종이 도트 질감(랜딩 v2와 동일)' },
  { id: 'ob-02', label: '제목 타이포', desc: 'GAEGU 제목에 자간 -.5px·text-wrap:balance·스케일 랜딩 정렬' },
  { id: 'ob-03', label: '진입 등장', desc: '툭 나타남 → 제목·부제·내용·CTA 순차 떠오름(1회, reduced-motion 정지)' },
  { id: 'ob-04', label: 'CTA 마감', desc: '네모 accent 버튼 → 알약형·GAEGU·hover·active scale·잉크 틴트 그림자' },
  { id: 'ob-05', label: '알·부화 액자', desc: '밋밋한 네모 → 종이 액자(paper+line+xl+slot 안쪽) 프레이밍' },
  { id: 'ob-06', label: '업로드 드롭존', desc: '점선 박스 톤을 랜딩 토큰(dash·paper/slot·라운드)으로 정돈' },
  { id: 'ob-07', label: '예시 그리드', desc: '빗금+색면 → 종이/slot 계열 차분한 카드(빗금 약화)' },
  { id: 'ob-08', label: '진행 점·뒤로', desc: 'dots·뒤로 버튼을 랜딩 line/paper/pill 톤으로' },
  { id: 'ob-09', label: '(문구) 랜딩 카피', desc: '"같이 살 수 있어요" → 장난감 톤 카피(표시 텍스트만)' },
  { id: 'ob-10', label: '한 화면 맞춤(반응형)', desc: '각 단계를 세로 스크롤 없이 한 뷰포트에(폰·탭·PC 압축·2열)' },
];

type Flags = Record<string, boolean>;

interface Ctx {
  flags: Flags;
  setFlag: (id: string, v: boolean) => void;
  setAll: (v: boolean) => void;
}

const OnbDevCtx = createContext<Ctx>({
  flags: {},
  setFlag: () => {},
  setAll: () => {},
});

const LS_KEY = 'zzal.onboarding.devflags.v1';

function save(next: Flags) {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(next));
  } catch {
    /* 프라이빗 창·차단 등에서 던질 수 있다. 저장만 못 할 뿐 화면은 정상 동작한다. */
  }
}

export function OnbDevProvider({ children }: { children: ReactNode }) {
  const [flags, setFlags] = useState<Flags>({});
  // 첫 렌더는 늘 전부 OFF(기존) — 서버·브라우저 렌더가 갈리면 하이드레이션 경고가 나므로 useEffect 로 뒤늦게 불러온다.
  useEffect(() => {
    try {
      const raw = localStorage.getItem(LS_KEY);
      if (raw) setFlags(JSON.parse(raw) as Flags);
    } catch {
      /* 읽기 실패 시 전부 OFF(기존) 로 둔다. */
    }
  }, []);

  const setFlag = (id: string, v: boolean) =>
    setFlags((prev) => {
      const next = { ...prev, [id]: v };
      save(next);
      return next;
    });

  const setAll = (v: boolean) =>
    setFlags(() => {
      const next = Object.fromEntries(ONB_UNITS.map((u) => [u.id, v]));
      save(next);
      return next;
    });

  return (
    <OnbDevCtx.Provider value={{ flags, setFlag, setAll }}>{children}</OnbDevCtx.Provider>
  );
}

/**
 * 이 변경 단위가 "적용후"인가(ON). 기본(공개 사이트·첫 렌더)은 늘 false(기존 화면).
 * Onboarding.tsx 가 이걸로 겉모습만 갈아 끼운다 — 핸들러·이동·서버 호출은 값과 무관하게 그대로다.
 */
export function useOnbFlag(id: string): boolean {
  return !!useContext(OnbDevCtx).flags[id];
}

/**
 * dev 전용 "변경 목록" 패널. 항목마다 [라벨 + 토글 + 한 줄 설명], 맨 위에 전체 켜기/끄기.
 * useDevVisible() 로만 뜬다 — 공개 도메인에선 null.
 * ★ 왼쪽 아래에 둔다 — 오른쪽은 여울 이동 창(DevJump)이 이미 쓴다(겹침 방지).
 */
export function OnbChangeList() {
  const visible = useDevVisible();
  const { flags, setFlag, setAll } = useContext(OnbDevCtx);
  const [open, setOpen] = useState(false);

  if (!visible) return null;

  const onCount = ONB_UNITS.filter((u) => flags[u.id]).length;

  if (!open) {
    return (
      <button
        type="button"
        data-part="onb-dev-toggle"
        onClick={() => setOpen(true)}
        style={{
          position: 'fixed',
          left: 0,
          bottom: 90,
          zIndex: 40,
          padding: '10px 5px',
          borderRadius: '0 8px 8px 0',
          border: `1px solid ${C.line}`,
          borderLeft: 'none',
          background: 'rgba(255,251,244,.92)',
          font: `10px ${MONO}`,
          color: C.sub,
          writingMode: 'vertical-rl',
          letterSpacing: '.08em',
          cursor: 'pointer',
        }}
      >
        온보딩 {onCount}/{ONB_UNITS.length}
      </button>
    );
  }

  return (
    <div
      data-part="onb-dev"
      style={{
        position: 'fixed',
        left: 12,
        bottom: 12,
        zIndex: 40,
        width: 'min(340px, calc(100% - 24px))',
        maxHeight: '76vh',
        overflow: 'auto',
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
        padding: '13px 14px',
        borderRadius: radius.lg,
        background: 'rgba(255,251,244,.97)',
        border: `1px solid ${C.line}`,
        boxShadow: '0 10px 28px rgba(74,64,56,.20)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 16, color: C.ink }}>
          온보딩 미리보기
        </span>
        <span style={{ flex: 1 }} />
        <button
          type="button"
          onClick={() => setOpen(false)}
          aria-label="닫기"
          style={{
            width: 24,
            height: 24,
            borderRadius: radius.pill,
            border: `1px solid ${C.lineHard}`,
            background: C.slot,
            fontSize: 11,
            color: C.sub2,
            lineHeight: 1,
            cursor: 'pointer',
          }}
        >
          ✕
        </button>
      </div>

      <div style={{ font: `9px ${MONO}`, color: C.faint, lineHeight: 1.4 }}>
        겉모습만 바꿉니다 · 업로드·이름·부화 동작은 그대로예요
      </div>

      <div style={{ display: 'flex', gap: 6 }}>
        <button type="button" data-part="onb-dev-all-on" onClick={() => setAll(true)} style={miniBtn(true)}>
          전체 켜기
        </button>
        <button type="button" data-part="onb-dev-all-off" onClick={() => setAll(false)} style={miniBtn(false)}>
          전체 끄기
        </button>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        {ONB_UNITS.map((u) => {
          const on = !!flags[u.id];
          return (
            <div
              key={u.id}
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                gap: 10,
                padding: '9px 10px',
                borderRadius: radius.md,
                background: on ? C.accentSoft : C.paper,
                border: `1px solid ${on ? C.accentDim : C.lineHard}`,
              }}
            >
              <div style={{ flex: 1, minWidth: 0 }}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 6,
                    fontSize: 13,
                    color: C.ink,
                    fontWeight: 600,
                  }}
                >
                  <span>{u.label}</span>
                  <span style={{ font: `9px ${MONO}`, color: C.faint }}>{u.id}</span>
                </div>
                <div style={{ fontSize: 11.5, color: C.sub, lineHeight: 1.4, marginTop: 3 }}>{u.desc}</div>
              </div>
              <Toggle on={on} id={u.id} onChange={(v) => setFlag(u.id, v)} />
            </div>
          );
        })}
      </div>

      <div style={{ font: `9px ${MONO}`, color: C.faint, lineHeight: 1.4 }}>
        {onCount}/{ONB_UNITS.length} 적용후 · 이 패널은 로컬·테일넷 dev 에서만 보입니다.
      </div>
    </div>
  );
}

function miniBtn(primary: boolean) {
  return {
    flex: 1,
    padding: '6px 0',
    borderRadius: radius.pill,
    border: `1px solid ${primary ? C.accent : C.lineHard}`,
    background: primary ? C.accent : C.paper,
    color: primary ? C.accentInk : C.sub,
    fontSize: 12,
    cursor: 'pointer',
  } as const;
}

/** 기존/적용후 를 가르는 작은 스위치. */
function Toggle({ on, id, onChange }: { on: boolean; id: string; onChange: (v: boolean) => void }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={`${id} ${on ? '적용후' : '기존'}`}
      data-part="onb-dev-switch"
      data-unit={id}
      data-on={on ? '1' : '0'}
      onClick={() => onChange(!on)}
      style={{
        flex: 'none',
        width: 66,
        height: 28,
        borderRadius: radius.pill,
        border: `1px solid ${on ? C.accent : C.lineHard}`,
        background: on ? C.accent : C.slot,
        color: on ? C.accentInk : C.sub,
        fontSize: 10.5,
        fontWeight: 700,
        cursor: 'pointer',
        display: 'flex',
        alignItems: 'center',
        justifyContent: on ? 'flex-start' : 'flex-end',
        padding: '0 9px',
        letterSpacing: '.02em',
      }}
    >
      {on ? '적용후' : '기존'}
    </button>
  );
}
