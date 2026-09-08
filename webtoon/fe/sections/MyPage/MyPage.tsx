"use client";

import { useEffect, useState } from "react";
import CommonMyPage, { type Section } from "@common/mypage/MyPage";
import MyCharacters from "./MyCharacters";
import {
  listRuns, myAccountRuns, myRuns, setVisibility, type RunCard,
} from "../../lib/nhApi";
import { louArt } from "../../lib/louArt";
import { WorkCard } from "../Works/Works";

/* 마이페이지 — **웹툰이 자기 칸만 끼운다.**
 *
 * 껍데기(레일 · 나 · 크레딧 · 충전 · 내역 · 로그아웃)는 `@common/mypage`
 * 에 있다. 그 화면이 답하는 「나는 누구고 얼마 남았나」는 도메인과 무관해서,
 * 짤이나 예고편에서 들어와도 같아야 하기 때문이다. 여기 남는 것은 **웹툰만
 * 아는 것** — 내가 만든 웹툰과 내 캐릭터다.
 *
 * 짤이 붙을 때 이 파일을 볼 필요가 없다. 자기 `Section` 을 만들어 같은
 * 껍데기에 끼우면 된다.
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
  const [runs, setRuns] = useState<RunCard[] | null>(null);
  const [failed, setFailed] = useState(false);

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
    return () => { alive = false; };
  }, []);

  const shown = runs || [];
  const hidden = shown.filter((r) => r.public === false).length;

  const sections: Section[] = [
    {
      key: "works",
      label: "내 웹툰",
      group: "만든 것",
      count: shown.length || undefined,
      hint: shown.length
        ? `${shown.length}편${hidden ? ` · 나만 보기 ${hidden}` : ""}`
        : undefined,
      actions: (
        <>
          <button type="button" className="me-btn" onClick={onBrowse}>둘러보기</button>
          <button type="button" className="me-btn me-btn-go" onClick={onCreate}>
            새 웹툰 만들기
          </button>
        </>
      ),
      node: (
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
      ),
    },
    {
      key: "chars",
      label: "내 캐릭터",
      group: "재료",
      hint: "웹툰을 만들 때마다 다시 적지 않아도 돼요",
      actions: (
        <button type="button" className="me-btn" onClick={onCharacters}>
          캐릭터 탭으로
        </button>
      ),
      node: <MyCharacters onOpen={onCharacters} />,
    },
  ];

  return (
    <CommonMyPage
      sections={sections}
      creditHint="한 편 12 C"
      onGuestPrimary={onCreate}
      guestPrimaryLabel="새 웹툰 만들기"
    />
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
