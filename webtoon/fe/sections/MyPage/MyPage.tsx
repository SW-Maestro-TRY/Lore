"use client";

import { useEffect, useState } from "react";
import MyCharacters from "./MyCharacters";
import { useAuth } from "@common/auth/useAuth";
import {
  creditBalance as browserCredit, listRuns, myAccountRuns, myRuns, setVisibility,
  type RunCard,
} from "../../lib/nhApi";
import { creditBalance } from "@common/api/credits";
import CreditHistory from "@common/mypage/CreditHistory";
import CreditCharge from "@common/mypage/CreditCharge";
import { louArt } from "../../lib/louArt";
import { WorkCard } from "../Works/Works";

/* 마이페이지 — **작업실.**
 *
 * ## 왜 웹툰 화면과 다르게 생겼나
 *
 * 이 화면은 웹툰만의 것이 아니라 LORE 가 공통으로 쓰는 자리다. 그런데 여태
 * 웹툰 청록(`--accent`)으로 칠해져 있어서, 짤이나 예고편에서 들어온 사람에게는
 * 남의 집으로 읽힌다. 그래서 여기서만 **LORE 토큰**을 쓴다 — `--webtoon`(보라)
 * · `--text` · `--surface-2` · Archivo. 이 값들은 `common/fe/styles/tokens.css`
 * 에 있고 앱 껍데기(apps/web/app/layout.tsx)가 이미 불러 둔다.
 *
 * ## 구조 — 왼쪽 레일 하나
 *
 * 왼쪽에 **내가 누구인지 · 얼마 남았는지 · 어디로 갈지**를 고정해 두고,
 * 오른쪽만 갈아 끼운다. 전에는 프로필 카드 · 크레딧 카드 · 캐릭터 줄 · 작품
 * 목록이 위에서부터 쌓여 있었는데, 그 구조에는 **짤과 예고편이 들어올 자리가
 * 없다** — 나중에 끼워 넣으면 웹툰만 또 특별해진다. 레일이면 한 줄만 늘면 된다.
 *
 * 지금 레일에 웹툰과 캐릭터만 있는 것은 그 둘만 실제로 있기 때문이다.
 * 없는 것을 미리 그려 두지 않는다.
 */
export default function MyPage({
  onOpenWork,
  onOpenEditor,
  onCreate,
  onBrowse,
  onCharacters,
}: {
  onOpenWork: (runId: string, episode: number) => void;
  onOpenEditor: (runId: string, episode: number) => void;
  onCreate: () => void;
  onBrowse: () => void;
  /** 캐릭터 탭으로. */
  onCharacters: () => void;
}) {
  const { status, user, isAuthenticated, signOut } = useAuth();
  const [runs, setRuns] = useState<RunCard[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [credit, setCredit] = useState<number | null>(null);
  /** 지금 열려 있는 창. 둘이 같이 뜨면 안 되므로 하나로 센다. */
  const [open, setOpen] = useState<"history" | "charge" | null>(null);
  /** 오른쪽에 무엇을 띄울지. 레일이 이것만 바꾼다. */
  const [tab, setTab] = useState<"works" | "chars">("works");

  useEffect(() => {
    let alive = true;
    /* 서버에 **내 계정 것**을 묻는다. 계정에 이어진 브라우저들이 만든 것을
       모아서 준다(webtoon/be 의 MyWebtoonService) — 그래서 기기를 바꿔도
       보이고, 나만 보기로 내려 둔 것도 온다.

       못 받으면 이 브라우저가 만든 것만이라도 보여준다. 로그인은 됐는데
       목록만 못 받은 상황에서 빈 화면을 주면 "내 작품이 다 사라졌다" 로
       읽힌다 — 그것보다는 덜 보이는 편이 낫다. */
    myAccountRuns()
      .then((got) => { if (alive) setRuns(got); })
      .catch(() => {
        const mine = new Set(myRuns());
        listRuns()
          .then((got) => { if (alive) setRuns((got.runs || []).filter((r) => mine.has(r.run_id))); })
          .catch(() => { if (alive) setFailed(true); });
      });
    /* 잔액은 **계정** 것을 먼저 본다. 로그인해서 들어온 화면이므로 계정 쪽이
       맞는 값이고, 브라우저(uid) 것은 로그인 안 한 사람이 쓰던 값이다.
       계정 쪽을 못 받으면 브라우저 것이라도 보여준다 — 크레딧 칸이 통째로
       비면 얼마 남았는지 볼 자리가 아예 없어진다. */
    creditBalance()
      .then((got) => { if (alive) setCredit(got.balance); })
      .catch(() => browserCredit()
        .then((got) => { if (alive) setCredit(got.balance); })
        .catch(() => { /* 잔액을 못 받아도 목록은 보여준다 */ }));
    return () => { alive = false; };
  }, []);

  if (status === "loading") {
    return (
      <section className="me">
        <p className="me-loading">불러오는 중…</p>
      </section>
    );
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
          <div className="me-guest-acts">
            <button type="button" className="me-btn me-btn-go" onClick={onCreate}>
              새 웹툰 만들기
            </button>
            <button type="button" className="me-btn" onClick={onBrowse}>
              둘러보기
            </button>
          </div>
        </div>
      </section>
    );
  }

  const id = user?.email.split("@")[0] || "나";
  const shown = runs || [];
  const hidden = shown.filter((r) => r.public === false).length;

  return (
    <section className="me">
      {/* ── 레일 ────────────────────────────────────────────────
          나 · 잔액 · 갈 곳. 화면이 좁아지면 가로로 눕는다. */}
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
            사람이 어디로 가야 하는지 모르고, 줄어든 이유가 궁금한 사람도 물을
            자리가 없다. (실제 결제는 아직이다 — #155) */}
        <div className="me-credit">
          <p className="me-credit-n">{credit ?? "…"}<small>C</small></p>
          <p className="me-credit-u">한 편 12 C</p>
          <div className="me-credit-acts">
            <button type="button" className="me-btn me-btn-xs"
                    onClick={() => setOpen("charge")}>충전</button>
            <button type="button" className="me-btn me-btn-xs"
                    onClick={() => setOpen("history")}>내역</button>
          </div>
        </div>

        {/* 짤·예고편이 생기면 여기 한 줄씩 는다. 그때 이 화면의 구조는
            안 바뀐다 — 레일을 쓴 이유가 그것이다. */}
        <nav className="me-nav">
          <p className="me-nav-grp">만든 것</p>
          <button type="button" className={`me-nav-a${tab === "works" ? " on" : ""}`}
                  onClick={() => setTab("works")}>
            웹툰<span>{shown.length || ""}</span>
          </button>
          <p className="me-nav-grp">재료</p>
          <button type="button" className={`me-nav-a${tab === "chars" ? " on" : ""}`}
                  onClick={() => setTab("chars")}>
            캐릭터
          </button>
        </nav>

        {/* 만들기는 **본문 머리**에 있다(「내 웹툰」 옆). 여기 두면 어느 탭에
            있든 같은 자리에 뜨는데, 캐릭터를 보고 있는 사람에게 웹툰 만들기를
            들이미는 셈이 된다. 목록 옆에 있으면 "이 목록에 한 편 더" 로 읽힌다. */}
        <div className="me-rail-foot">
          <button type="button" className="me-quit" onClick={() => void signOut()}>
            로그아웃
          </button>
        </div>
      </aside>

      {/* ── 본문 ──────────────────────────────────────────────── */}
      <div className="me-main">
        {open === "history" && <CreditHistory onClose={() => setOpen(null)} />}
        {open === "charge" && <CreditCharge onClose={() => setOpen(null)} />}

        {tab === "works" && (
          <>
            <div className="me-top">
              <h2>내 웹툰</h2>
              {shown.length > 0 && (
                <p className="me-top-sub">
                  {shown.length}편{hidden ? ` · 나만 보기 ${hidden}` : ""}
                </p>
              )}
              <div className="me-top-acts">
                <button type="button" className="me-btn" onClick={onBrowse}>둘러보기</button>
                <button type="button" className="me-btn me-btn-go" onClick={onCreate}>
                  새 웹툰 만들기
                </button>
              </div>
            </div>

            <div className="works-grid me-works">
              {failed && (
                <div className="works-empty">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={louArt("error")} alt="" aria-hidden="true" />
                  <b>목록을 가져오지 못했어요</b>
                  서버가 떠 있는지 확인해 주세요.
                </div>
              )}
              {!failed && !runs && <p className="works-empty">불러오는 중…</p>}
              {runs?.length === 0 && (
                <div className="works-empty">
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={louArt("empty")} alt="" aria-hidden="true" />
                  <b>아직 만든 웹툰이 없어요</b>
                  첫 작품이 이 자리에 걸립니다.
                  <br />
                  <button type="button" className="inline-link" onClick={onCreate}>
                    내 캐릭터로 웹툰 만들기
                  </button>
                </div>
              )}
              {runs?.map((r) => (
                <WorkCard
                  key={r.run_id}
                  run={r}
                  onOpen={onOpenWork}
                  tools={<MyTools run={r} onOpenEditor={onOpenEditor} />}
                />
              ))}
            </div>
          </>
        )}

        {tab === "chars" && (
          <>
            <div className="me-top">
              <h2>내 캐릭터</h2>
              <p className="me-top-sub">웹툰을 만들 때마다 다시 적지 않아도 돼요</p>
              <button type="button" className="me-btn" onClick={onCharacters}>
                캐릭터 탭으로
              </button>
            </div>
            <MyCharacters onOpen={onCharacters} />
          </>
        )}
      </div>
    </section>
  );
}

/* 내 작품에만 붙는 줄 — 공개 스위치와 편집실로 가는 길.
   남의 작품에 있을 수 없는 것이라 둘러보기에는 안 붙는다. */
function MyTools({
  run,
  onOpenEditor,
}: {
  run: RunCard;
  onOpenEditor: (runId: string, episode: number) => void;
}) {
  const [pub, setPub] = useState(run.public !== false);
  const [failed, setFailed] = useState(false);

  /* 목록을 다시 받으면 서버가 준 값으로 되돌린다 — 안 하면 화면이 들고 있는
     값과 서버가 갈린 채로 남는다. */
  useEffect(() => { setPub(run.public !== false); }, [run.public]);
  const [busy, setBusy] = useState(false);
  const first = run.episodes?.[0] || 1;

  /* 스위치를 누르면 그 자리에서 서버에 알린다. 실패하면 되돌린다 — 껐다고
     보이는데 실제로는 걸려 있는 것이 제일 나쁘다. */
  const toggle = (want: boolean) => {
    setPub(want);
    setBusy(true);
    setFailed(false);
    setVisibility(run.run_id, want)
      .catch(() => { setPub(!want); setFailed(true); })
      .finally(() => setBusy(false));
  };

  /* 「둘러보기에 공개 / 나만 보기」 라고 적었더니 무엇을 누르는 건지 안
     읽혔다 — 체크가 켜진 것이 "지금 공개" 인지 "공개하겠다" 인지 헷갈린다.
     지금 상태를 그냥 **공개 / 비공개** 로 말하고, 스위치는 그 상태를 뒤집는
     것으로 둔다. */
  return (
    <>
      <label className={`works-pub${pub ? "" : " is-off"}`}>
        <input type="checkbox" role="switch" className="works-pub-box"
               checked={pub} disabled={busy}
               onChange={(e) => toggle(e.target.checked)} />
        <span>{pub ? "공개" : "비공개"}</span>
      </label>
      {failed && <span className="works-pub-fail">바꾸지 못했습니다</span>}
      <button type="button" className="works-edit"
              onClick={() => onOpenEditor(run.run_id, first)}>
        편집실에서 열기 →
      </button>
    </>
  );
}
