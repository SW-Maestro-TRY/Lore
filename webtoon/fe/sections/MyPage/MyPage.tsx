"use client";

import { useEffect, useState } from "react";
import MyCharacters from "./MyCharacters";
import { useAuth } from "@common/auth/useAuth";
import {
  creditBalance as browserCredit, listRuns, myAccountRuns, myRuns, setVisibility,
  type RunCard,
} from "../../lib/nhApi";
import { creditBalance } from "@common/api/credits";
import CreditHistory from "./CreditHistory";
import CreditCharge from "./CreditCharge";
import { louArt } from "../../lib/louArt";
import { WorkCard } from "../Works/Works";

/* 마이페이지 — haeun/landing/web 의 #mypage 를 옮겼다.
 *
 * **로그인한 사람의 자리다.** 헤더의 「마이페이지」가 여기로 보낸다
 * (`/webtoon?view=mypage`). 로그인 안 했으면 들어올 일이 없지만, 주소를
 * 직접 치고 들어올 수는 있으므로 그때는 로그인하라고만 말한다.
 *
 * 목록은 **계정 것**이다. 작품 자체에는 계정 번호가 안 박혀 있고 브라우저
 * uid 로만 묶여 있어서, 서버가 (계정 ↔ uid) 연결을 들고 있다가 그 브라우저들이
 * 만든 것을 모아 준다(webtoon/be 의 BrowserLink · MyWebtoonService). 그래서
 * 로그인 전에 만든 것도, 다른 기기에서 만든 것도 따라온다.
 *
 * 그래도 "저장한 작품" 이라고 쓰지 않는다 — 서버에 담아 둔 것이 아니라 내가
 * 만든 것이고, uid 는 꾸밀 수 있는 값이라 소유의 증명도 아니다.
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
      {/* **프로필과 크레딧을 한 덩어리로.** 따로 두었더니 흰 카드가 둘 쌓이고,
          크레딧 칸은 숫자 하나에 카드를 통째로 쓰느라 안이 텅 비었다. 다만
          한 줄로 다 밀어 넣지는 않는다 — 위는 나(누구인가 · 무엇을 할 것인가),
          아래는 크레딧(얼마 남았나)으로 줄을 나눈다. */}
      <header className="mypage-head">
        <div className="mypage-who">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="mypage-photo" src="/static/lou/react/idle/01.webp" alt="" />
          <div className="mypage-name">
            <p className="eyebrow">마이페이지</p>
            {/* 이메일 전체를 제목에 걸면 좁은 화면에서 밀린다 — 아이디만 크게
                쓰고 전체는 아래 줄에 둔다. */}
            <h2>{user?.email.split("@")[0]}</h2>
            <p className="mypage-meta">{user?.email}</p>
          </div>
        </div>

        <div className="mypage-actions">
          <button type="button" className="btn btn-primary btn-sm" onClick={onCreate}>
            새 웹툰 만들기
          </button>
          <button type="button" className="btn btn-quiet btn-sm" onClick={() => void signOut()}>
            로그아웃
          </button>
        </div>

        {/* 크레딧은 여기서 **잔액과 갈 자리**만 말한다. 잔액만 보여 주면
            모자란 사람이 어디로 가야 하는지 모르고, 줄어든 이유가 궁금한
            사람도 물을 자리가 없다. (실제 결제는 아직이다 — #155) */}
        <div className="mypage-credit">
          <p className="mypage-credit-num">
            <b>{credit ?? "…"}</b> <span>C</span>
          </p>
          <p className="mypage-credit-hint">한 편에 12 C</p>
          <div className="mypage-credit-acts">
            <button type="button" className="btn btn-quiet btn-sm"
                    onClick={() => setOpen("charge")}>충전</button>
            <button type="button" className="btn btn-quiet btn-sm"
                    onClick={() => setOpen("history")}>내역</button>
          </div>
        </div>
      </header>

      {open === "history" && <CreditHistory onClose={() => setOpen(null)} />}
      {open === "charge" && <CreditCharge onClose={() => setOpen(null)} />}


      {/* **내 캐릭터를 작품보다 먼저 둔다.** 웹툰은 캐릭터로 만드는 것이라,
          여기 들어온 사람이 다음에 할 일은 대개 "저 캐릭터로 하나 더" 다.
          옆으로 넘겨 보게 해서 작품 목록을 아래로 밀지 않는다. */}
      <MyCharacters onOpen={onCharacters} />

      {/* **내 캐릭터를 작품보다 먼저 둔다.** 웹툰은 캐릭터로 만드는 것이라,
          여기 들어온 사람이 다음에 할 일은 대개 "저 캐릭터로 하나 더" 다. */}

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
