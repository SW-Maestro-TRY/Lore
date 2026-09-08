"use client";

import { useEffect, useState, type ReactNode } from "react";
import { useAuth } from "@common/auth/useAuth";
import { creditBalance } from "@common/api/credits";
import CreditHistory from "./CreditHistory";
import CreditCharge from "./CreditCharge";
import "./mypage.css";

/* 마이페이지 — **세 도메인이 같이 쓰는 작업실.**
 *
 * ## 왜 common 에 있나
 *
 * 이 화면이 답하는 것은 「나는 누구고, 얼마 남았고, 내가 만든 것이 무엇인가」
 * 다. 앞의 둘은 도메인과 무관하고, 세 번째만 도메인마다 다르다. 그런데
 * 여태 웹툰 안에 있어서 짤이나 예고편에서 들어오면 **웹툰 탭 안으로**
 * 들어와야 했다.
 *
 * ## 껍데기와 칸을 가른다
 *
 * 여기(common)가 가진 것은 **레일과 크레딧**뿐이다 — 나 · 잔액 · 충전 ·
 * 내역 · 로그아웃. 무엇을 만들었는지는 도메인이 {@link Section} 으로 끼운다.
 * 그래야 공통 코드가 웹툰의 작품이나 짤의 펫을 알 필요가 없다.
 *
 * ```tsx
 * <MyPage sections={[
 *   { key: "works", label: "웹툰", count: 7, group: "만든 것", node: <MyWorks/> },
 *   { key: "chars", label: "캐릭터",        group: "재료",     node: <MyChars/> },
 * ]} />
 * ```
 *
 * 도메인이 늘어도 이 파일은 안 바뀐다 — 레일에 줄이 하나 느는 것이 전부다.
 * 그것이 카드를 위에서부터 쌓는 대신 레일로 짠 이유다.
 *
 * ## 좁은 화면
 *
 * **모바일이 기본이다.** 레일이 세로로 쌓이면 나·잔액·갈 곳이 첫 화면을 다
 * 먹고 정작 보러 온 것이 스크롤 아래로 밀린다. 그래서 폰에서는 눕고,
 * 860px 부터 왼쪽에 선다(mypage.css).
 */
export interface Section {
  /** 레일에서 이 칸을 가리키는 값. 화면 안에서만 쓴다. */
  key: string;
  /** 레일에 보이는 이름. */
  label: string;
  /** 레일 이름 옆에 붙는 수. 없으면 안 그린다. */
  count?: number;
  /** 레일에서 위에 붙는 묶음 이름(「만든 것」·「재료」). 같은 값끼리 묶인다. */
  group?: string;
  /** 본문 머리에 붙는 한 줄 설명. */
  hint?: string;
  /** 본문 머리 오른쪽에 놓을 단추들. */
  actions?: ReactNode;
  /** 본문. */
  node: ReactNode;
}

export default function MyPage({
  sections,
  creditHint,
  onGuestPrimary,
  guestPrimaryLabel = "만들러 가기",
}: {
  sections: Section[];
  /** 크레딧 칸 아래 한 줄 — 도메인마다 값이 다르다(웹툰은 「한 편 12 C」). */
  creditHint?: string;
  /** 로그인 안 한 사람에게 보여줄 갈 곳. */
  onGuestPrimary?: () => void;
  guestPrimaryLabel?: string;
}) {
  const { status, user, isAuthenticated, signOut } = useAuth();
  const [credit, setCredit] = useState<number | null>(null);
  /** 지금 열려 있는 창. 둘이 같이 뜨면 안 되므로 하나로 센다. */
  const [open, setOpen] = useState<"history" | "charge" | null>(null);
  const [tab, setTab] = useState(sections[0]?.key ?? "");

  useEffect(() => {
    let alive = true;
    creditBalance()
      .then((got) => { if (alive) setCredit(got.balance); })
      .catch(() => { /* 잔액을 못 받아도 나머지는 보여준다 */ });
    return () => { alive = false; };
  }, []);

  /* 도메인이 칸을 늦게 채울 수 있다(목록을 받아 온 뒤에 넣는 식). 그때
     고른 칸이 사라져 있으면 본문이 통째로 빈다 — 첫 칸으로 되돌린다. */
  useEffect(() => {
    if (sections.length && !sections.some((s) => s.key === tab)) {
      setTab(sections[0].key);
    }
  }, [sections, tab]);

  if (status === "loading") {
    return <section className="me"><p className="me-loading">불러오는 중…</p></section>;
  }

  /* 로그인 안 하고 주소로 들어온 경우. 헤더의 「로그인」을 가리키기만 한다 —
     여기서 모달을 또 띄우면 로그인 창을 여는 자리가 둘이 된다. */
  if (!isAuthenticated) {
    return (
      <section className="me">
        <div className="me-guest">
          <p className="me-eyebrow">마이페이지</p>
          <h2>로그인이 필요합니다</h2>
          <p className="me-guest-sub">위 <b>로그인</b>을 눌러 주세요.</p>
          {onGuestPrimary && (
            <div className="me-guest-acts">
              <button type="button" className="me-btn me-btn-go" onClick={onGuestPrimary}>
                {guestPrimaryLabel}
              </button>
            </div>
          )}
        </div>
      </section>
    );
  }

  const id = user?.email.split("@")[0] || "나";
  const now = sections.find((s) => s.key === tab) ?? sections[0];

  /* 묶음 이름은 **처음 나온 자리에서만** 그린다. 도메인이 순서를 정하므로
     여기서 다시 정렬하지 않는다 — 정렬하면 도메인이 의도한 차례가 깨진다. */
  const seen = new Set<string>();

  return (
    <section className="me">
      <aside className="me-rail">
        <div className="me-who">
          <span className="me-face" aria-hidden="true">{id.slice(0, 1).toUpperCase()}</span>
          <span className="me-who-txt">
            {/* 이메일 전체는 좁은 화면에서 밀리므로 아이디를 크게 쓰고
                전체는 아래 줄에 작게 둔다. */}
            <b>{id}</b>
            <span title={user?.email}>{user?.email}</span>
          </span>
        </div>

        {/* 크레딧은 **잔액과 갈 자리**를 함께 준다. 잔액만 보여 주면 모자란
            사람이 어디로 가야 하는지 모르고, 줄어든 이유가 궁금한 사람도
            물을 자리가 없다. (실제 결제는 아직이다 — #155) */}
        <div className="me-credit">
          <p className="me-credit-n">{credit ?? "…"}<small>C</small></p>
          {creditHint && <p className="me-credit-u">{creditHint}</p>}
          <div className="me-credit-acts">
            <button type="button" className="me-btn me-btn-xs"
                    onClick={() => setOpen("charge")}>충전</button>
            <button type="button" className="me-btn me-btn-xs"
                    onClick={() => setOpen("history")}>내역</button>
          </div>
        </div>

        <nav className="me-nav">
          {sections.map((s) => {
            const head = s.group && !seen.has(s.group) ? s.group : null;
            if (s.group) seen.add(s.group);
            return (
              <div key={s.key} style={{ display: "contents" }}>
                {head && <p className="me-nav-grp">{head}</p>}
                <button type="button"
                        className={`me-nav-a${s.key === tab ? " on" : ""}`}
                        onClick={() => setTab(s.key)}>
                  {s.label}
                  {s.count ? <span>{s.count}</span> : null}
                </button>
              </div>
            );
          })}
        </nav>

        <div className="me-rail-foot">
          <button type="button" className="me-quit" onClick={() => void signOut()}>
            로그아웃
          </button>
        </div>
      </aside>

      <div className="me-main">
        {open === "history" && <CreditHistory onClose={() => setOpen(null)} />}
        {open === "charge" && <CreditCharge onClose={() => setOpen(null)} />}

        {now && (
          <>
            <div className="me-top">
              <h2>{now.label}</h2>
              {now.hint && <p className="me-top-sub">{now.hint}</p>}
              {now.actions && <div className="me-top-acts">{now.actions}</div>}
            </div>
            {now.node}
          </>
        )}
      </div>
    </section>
  );
}
