"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import {
  creditBalance, listRuns, myRuns, setVisibility, type RunCard,
} from "../../lib/nhApi";
import { louArt } from "../../lib/louArt";
import { WorkCard } from "../Works/Works";

/* 마이페이지 — haeun/landing/web 의 #mypage 를 옮겼다.
 *
 * **로그인한 사람의 자리다.** 헤더의 「마이페이지」가 여기로 보낸다
 * (`/webtoon?view=mypage`). 로그인 안 했으면 들어올 일이 없지만, 주소를
 * 직접 치고 들어올 수는 있으므로 그때는 로그인하라고만 말한다.
 *
 * ⚠ **목록은 아직 이 브라우저 것만이다.** 작품은 계정이 아니라 브라우저
 * uid 로 묶여 있어서, 다른 기기에서 로그인하면 안 보인다. 계정에 붙이는 것이
 * 이 이슈(#223)의 다음 걸음이다 — 그때 이 함수만 서버 목록으로 바꾸면 되고
 * 화면은 안 바뀐다. 지금 "저장한 작품" 이라고 안 쓰고 "내가 만든 웹툰" 이라고
 * 쓰는 것도 그래서다. 서버에 담아 둔 것이 아니라 이 브라우저가 만든 것이다.
 */
export default function MyPage({
  onOpenWork,
  onOpenEditor,
  onCreate,
  onBrowse,
}: {
  onOpenWork: (runId: string, episode: number) => void;
  onOpenEditor: (runId: string, episode: number) => void;
  onCreate: () => void;
  onBrowse: () => void;
}) {
  const { status, user, isAuthenticated, signOut } = useAuth();
  const [runs, setRuns] = useState<RunCard[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [credit, setCredit] = useState<number | null>(null);

  useEffect(() => {
    let alive = true;
    /* 목록은 둘러보기와 같은 주소에서 받아 **내 것만** 남긴다. 서버에 "내
       것만 달라" 고 물을 길이 아직 없어서다(위 주석). 비공개로 내린 작품도
       내 목록에는 남아야 하므로 그 필터는 여기서 안 건다. */
    const mine = new Set(myRuns());
    listRuns()
      .then((got) => { if (alive) setRuns((got.runs || []).filter((r) => mine.has(r.run_id))); })
      .catch(() => { if (alive) setFailed(true); });
    creditBalance()
      .then((got) => { if (alive) setCredit(got.balance); })
      .catch(() => { /* 잔액을 못 받아도 목록은 보여준다 */ });
    return () => { alive = false; };
  }, []);

  if (status === "loading") {
    return (
      <section className="mypage">
        <header className="mypage-head"><h2>불러오는 중…</h2></header>
      </section>
    );
  }

  /* 로그인 안 하고 주소로 들어온 경우. 헤더의 「로그인」을 가리키기만 한다 —
     여기서 모달을 또 띄우면 로그인 창을 여는 자리가 둘이 된다. */
  if (!isAuthenticated) {
    return (
      <section className="mypage">
        <header className="mypage-head">
          <div className="mypage-who">
            <p className="eyebrow">마이페이지</p>
            <h2>로그인이 필요합니다</h2>
            <p className="mypage-meta">위 <b>로그인</b>을 눌러 주세요.</p>
          </div>
          <div className="mypage-actions">
            <button type="button" className="btn btn-primary btn-sm" onClick={onCreate}>
              새 웹툰 만들기
            </button>
            <button type="button" className="btn btn-quiet btn-sm" onClick={onBrowse}>
              둘러보기
            </button>
          </div>
        </header>
      </section>
    );
  }

  const hidden = (runs || []).filter((r) => r.public === false).length;

  return (
    <section className="mypage">
      <header className="mypage-head">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className="mypage-photo" src="/static/lou/react/idle/01.webp" alt="" />
        <div className="mypage-who">
          <p className="eyebrow">마이페이지</p>
          {/* 이메일 전체를 제목에 걸면 좁은 화면에서 밀린다 — 아이디만 크게
              쓰고 전체는 아래 줄에 둔다. */}
          <h2>{user?.email.split("@")[0]}</h2>
          {/* 이메일과 편수를 한 줄에 이어 붙였더니 좁은 화면에서 접히면서
              가운뎃점이 줄 앞에 남았다 — 두 줄로 나눈다. */}
          <p className="mypage-meta">{user?.email}</p>
          {runs && (
            <p className="mypage-meta">
              내가 만든 웹툰 {runs.length}편
              {hidden ? ` · 그중 ${hidden}편은 나만 보기` : ""}
            </p>
          )}
        </div>
        <div className="mypage-actions">
          <button type="button" className="btn btn-primary btn-sm" onClick={onCreate}>
            새 웹툰 만들기
          </button>
          <button type="button" className="btn btn-quiet btn-sm" onClick={() => void signOut()}>
            로그아웃
          </button>
        </div>
      </header>

      {/* 크레딧. 상단 배지에도 숫자가 있던 자리지만 여기는 **자리**다 —
          얼마 남았는지 보고 충전할지 정하는 곳.
          ⚠ 이 값은 계정이 아니라 이 브라우저(uid) 것이다. 가격·충전은 아직
          안 붙었다(#16 · #155) — 그래서 「충전하기」를 그리지 않는다. 눌러도
          아무 일이 안 일어나는 단추를 두느니 없는 편이 낫다. */}
      <div className="mypage-credit">
        <div className="mypage-credit-main">
          <p className="eyebrow">크레딧</p>
          <p className="mypage-credit-num">
            <b>{credit ?? "…"}</b> <span>C</span>
          </p>
          <p className="mypage-credit-hint">한 편에 12 C</p>
        </div>
      </div>

      <div className="mypage-section">
        <div className="mypage-section-head">
          <h3>내가 만든 웹툰</h3>
          <button type="button" className="btn btn-quiet btn-sm" onClick={onBrowse}>
            둘러보기
          </button>
        </div>

        {/* 옆으로 미는 줄. 세로로 쌓으면 몇 편만 있어도 화면이 길어지고,
            여기서 보고 싶은 것은 "무엇이 있나" 지 한 편 한 편이 아니다. */}
        <div className="works-grid works-rail">
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
                내 캐릭터로 웹툰 만들기 →
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
  const [busy, setBusy] = useState(false);
  const first = run.episodes?.[0] || 1;

  /* 스위치를 누르면 그 자리에서 서버에 알린다. 실패하면 되돌린다 — 껐다고
     보이는데 실제로는 걸려 있는 것이 제일 나쁘다. */
  const toggle = (want: boolean) => {
    setPub(want);
    setBusy(true);
    setVisibility(run.run_id, want)
      .catch(() => setPub(!want))
      .finally(() => setBusy(false));
  };

  return (
    <>
      <label className="works-pub">
        <input type="checkbox" className="works-pub-box" checked={pub} disabled={busy}
               onChange={(e) => toggle(e.target.checked)} />
        <span>{pub ? "둘러보기에 공개" : "나만 보기"}</span>
      </label>
      <button type="button" className="works-edit"
              onClick={() => onOpenEditor(run.run_id, first)}>
        편집실에서 열기 →
      </button>
    </>
  );
}
