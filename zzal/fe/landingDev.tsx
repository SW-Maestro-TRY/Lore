// zzal 랜딩 "기존 ↔ 적용후" 비교 장치 — 변경 단위 on/off 상태 + dev 전용 "변경 목록" 패널.
//
// 왜 이렇게 하나
//   방향서(랜딩-업로드-uiux방향-0917.md)의 랜딩 변경들을 한 번에 갈아치우지 않고
//   **변경 단위별로 토글**해, 상훈님이 한 화면에서 기존과 적용후를 하나씩 켜 보며 고르게 한다.
//   각 섹션은 이 모듈의 useDevFlag(id) 로 자기 플래그를 읽어 기존/적용후 둘 중 하나를 그린다.
//
// 게이팅
//   패널은 useDevVisible() 로만 뜬다 — 공개 도메인(*.lorecomic.com)에선 절대 안 뜨고
//   로컬·테일넷 dev 에서만 뜬다. 새 게이팅을 만들지 않고 tamagotchi/useDevVisible 을 그대로 쓴다.
//
// 상태 저장
//   per-viewer localStorage(try/catch). 첫 렌더는 늘 전부 OFF(기존 화면) — 서버·브라우저 렌더가
//   갈리면 하이드레이션 경고가 나므로(useDevVisible 과 같은 이유) useEffect 로 뒤늦게 불러온다.
"use client";

import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";
import { useDevVisible } from "./tamagotchi/useDevVisible";
import { C, GAEGU, MONO, radius } from "./tamagotchi/yeoul/ui";

export interface ChangeUnit {
  id: string;
  label: string;
  /** 무엇이 바뀌나 한 줄. */
  desc: string;
}

/** 방향서 → 6~10개 독립 변경 단위. 순서 = 화면 위→아래(팔레트만 페이지 전체). */
export const CHANGE_UNITS: ChangeUnit[] = [
  {
    id: "toy-palette",
    label: "토이 팔레트",
    desc: "빨강+노랑 → 여울 저채도(액센트 1개)로 바탕·강조색 교체",
  },
  {
    id: "hero-copy",
    label: "히어로 카피",
    desc: '"만화로 데뷔하다!" → "안녕! 같이 키우자!", 가짜 수치·생성기 배지 삭제',
  },
  {
    id: "hero-mascot",
    label: "히어로 마스코트",
    desc: "기울인 콜라주(oc 이미지) → 여울 마스코트 중앙 + 숨쉬기(±4px)",
  },
  {
    id: "season-banner",
    label: "사계절 배너 정리",
    desc: "테이프·기울임 사계절 콜라주 배너 숨김(새 랜딩 스펙에 없음)",
  },
  {
    id: "how-3step",
    label: "3컷 안내 재구성",
    desc: '"딱 세 컷!" 3열 카드 → "올리기·태어남·같이 놀기" 세로 스택',
  },
  {
    id: "cta",
    label: "마감 CTA",
    desc: '"데뷔시킬 준비?"·"무료로 만들기" → "같이 키우러 가기", 하드섀도우 제거',
  },
  {
    id: "footer",
    label: "푸터 정리",
    desc: '"자캐툰 ✎" 브랜딩 제거, © 최소 표기',
  },
];

type Flags = Record<string, boolean>;

interface Ctx {
  flags: Flags;
  setFlag: (id: string, v: boolean) => void;
  setAll: (v: boolean) => void;
}

const DevFlagsCtx = createContext<Ctx>({
  flags: {},
  setFlag: () => {},
  setAll: () => {},
});

const LS_KEY = "zzal.landing.devflags.v1";

function save(next: Flags) {
  try {
    localStorage.setItem(LS_KEY, JSON.stringify(next));
  } catch {
    /* 프라이빗 창·차단 등에서 던질 수 있다. 저장만 못 할 뿐 화면은 정상 동작한다. */
  }
}

export function LandingDevProvider({ children }: { children: ReactNode }) {
  const [flags, setFlags] = useState<Flags>({});

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
      const next = Object.fromEntries(CHANGE_UNITS.map((u) => [u.id, v]));
      save(next);
      return next;
    });

  const toy = !!flags["toy-palette"];

  return (
    <DevFlagsCtx.Provider value={{ flags, setFlag, setAll }}>
      <div className={"zzal-page" + (toy ? " zt-toy" : "")}>{children}</div>
    </DevFlagsCtx.Provider>
  );
}

/** 이 변경 단위가 "적용후"인가(ON). 기본 OFF=기존. */
export function useDevFlag(id: string): boolean {
  return !!useContext(DevFlagsCtx).flags[id];
}

/**
 * dev 전용 "변경 목록" 패널. 항목마다 [라벨 + 토글 + 한 줄 설명], 맨 위에 전체 켜기/끄기.
 * useDevVisible() 로만 뜬다 — 공개 도메인에선 null.
 */
export function DevChangeList() {
  const visible = useDevVisible();
  const { flags, setFlag, setAll } = useContext(DevFlagsCtx);
  const [open, setOpen] = useState(true);

  if (!visible) return null;

  const onCount = CHANGE_UNITS.filter((u) => flags[u.id]).length;

  if (!open) {
    return (
      <button
        type="button"
        data-part="landing-dev-toggle"
        onClick={() => setOpen(true)}
        style={{
          position: "fixed",
          right: 0,
          bottom: 90,
          zIndex: 40,
          padding: "10px 5px",
          borderRadius: "8px 0 0 8px",
          border: `1px solid ${C.line}`,
          borderRight: "none",
          background: "rgba(255,251,244,.92)",
          font: `10px ${MONO}`,
          color: C.sub,
          writingMode: "vertical-rl",
          letterSpacing: ".08em",
          cursor: "pointer",
        }}
      >
        변경 {onCount}/{CHANGE_UNITS.length}
      </button>
    );
  }

  return (
    <div
      data-part="landing-dev"
      style={{
        position: "fixed",
        right: 12,
        bottom: 12,
        zIndex: 40,
        width: "min(340px, calc(100% - 24px))",
        maxHeight: "76vh",
        overflow: "auto",
        display: "flex",
        flexDirection: "column",
        gap: 10,
        padding: "13px 14px",
        borderRadius: radius.lg,
        background: "rgba(255,251,244,.97)",
        border: `1px solid ${C.line}`,
        boxShadow: "0 10px 28px rgba(74,64,56,.20)",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ fontFamily: GAEGU, fontWeight: 700, fontSize: 16, color: C.ink }}>
          변경 목록
        </span>
        <span style={{ font: `10px ${MONO}`, color: C.faint }}>
          {onCount}/{CHANGE_UNITS.length} 적용후
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
            cursor: "pointer",
          }}
        >
          ✕
        </button>
      </div>

      <div style={{ display: "flex", gap: 6 }}>
        <button
          type="button"
          data-part="landing-dev-all-on"
          onClick={() => setAll(true)}
          style={miniBtn(true)}
        >
          전체 켜기
        </button>
        <button
          type="button"
          data-part="landing-dev-all-off"
          onClick={() => setAll(false)}
          style={miniBtn(false)}
        >
          전체 끄기
        </button>
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {CHANGE_UNITS.map((u) => {
          const on = !!flags[u.id];
          return (
            <div
              key={u.id}
              style={{
                display: "flex",
                alignItems: "flex-start",
                gap: 10,
                padding: "9px 10px",
                borderRadius: radius.md,
                background: on ? C.accentSoft : C.paper,
                border: `1px solid ${on ? C.accentDim : C.lineHard}`,
              }}
            >
              <div style={{ flex: 1, minWidth: 0 }}>
                <div
                  style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 6,
                    fontSize: 13,
                    color: C.ink,
                    fontWeight: 600,
                  }}
                >
                  <span>{u.label}</span>
                  <span style={{ font: `9px ${MONO}`, color: C.faint }}>{u.id}</span>
                </div>
                <div style={{ fontSize: 11.5, color: C.sub, lineHeight: 1.4, marginTop: 3 }}>
                  {u.desc}
                </div>
              </div>
              <Toggle
                on={on}
                id={u.id}
                onChange={(v) => setFlag(u.id, v)}
              />
            </div>
          );
        })}
      </div>

      <div style={{ font: `9px ${MONO}`, color: C.faint, lineHeight: 1.4 }}>
        이 패널은 로컬·테일넷 dev 에서만 보입니다. 공개 사이트엔 안 뜹니다.
      </div>
    </div>
  );
}

function miniBtn(primary: boolean) {
  return {
    flex: 1,
    padding: "6px 0",
    borderRadius: radius.pill,
    border: `1px solid ${primary ? C.accent : C.lineHard}`,
    background: primary ? C.accent : C.paper,
    color: primary ? C.accentInk : C.sub,
    fontSize: 12,
    cursor: "pointer",
  } as const;
}

/** 기존/적용후 를 가르는 작은 스위치. */
function Toggle({
  on,
  id,
  onChange,
}: {
  on: boolean;
  id: string;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={`${id} ${on ? "적용후" : "기존"}`}
      data-part="landing-dev-switch"
      data-unit={id}
      data-on={on ? "1" : "0"}
      onClick={() => onChange(!on)}
      style={{
        flex: "none",
        width: 66,
        height: 28,
        borderRadius: radius.pill,
        border: `1px solid ${on ? C.accent : C.lineHard}`,
        background: on ? C.accent : C.slot,
        color: on ? C.accentInk : C.sub,
        fontSize: 10.5,
        fontWeight: 700,
        cursor: "pointer",
        display: "flex",
        alignItems: "center",
        justifyContent: on ? "flex-start" : "flex-end",
        padding: "0 9px",
        letterSpacing: ".02em",
      }}
    >
      {on ? "적용후" : "기존"}
    </button>
  );
}
