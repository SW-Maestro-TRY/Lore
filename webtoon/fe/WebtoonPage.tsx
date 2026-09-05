// Webtoon 탭의 실제 화면. (담당: 하은)
//
// haeun/landing 프로토타입(index.html + app.js)을 React 로 옮긴 것이다.
// 화면 여섯(홈 · 위자드 · 진행 · 결과 · 둘러보기 · 마이페이지)과 편집실.
//
// **만들기는 이제 진짜로 돈다.** 위자드에서 「웹툰 만들기」를 누르면
// `/api/webtoon/nh/create` 로 나가고(그 앞에 스프링이 서서 생성 하네스로
// 넘긴다 — webtoon/be 참고), 진행 화면이 그 작업을 0.8초마다 받아 그린다.
// 사람이 멈춰 서는 자리 둘(시트 확인 · 이야기 고르기)도 실제 검수다.
//
// **결과 · 둘러보기 · 편집실도 진짜다.** 다 그리면 진행 화면이 run_id 를
// 넘겨주고 결과 화면이 그것으로 방금 만든 작품을 연다. 둘러보기는 실제 작품
// 목록을 걸고, 편집실은 그 작품을 열어 다시 그리기 · 지난 판 되돌리기 ·
// 이미지로 뽑기가 전부 실제로 돈다.
//
// 아직 mock 인 것: **마이페이지 하나**. 이 화면만 계정에 달려 있는데, 웹툰
// 탭이 Lore 앱 계정을 쓸지 하네스 쪽 계정을 쓸지가 아직 안 정해졌다 —
// 정해지기 전에 아무거나 붙이면 로그인이 두 개가 된다.
//
// **자기 머리는 안 그린다.** 원본은 혼자 뜨는 페이지라 자기 머리(.topbar)가
// 필요했지만, 여기서는 Lore 앱 헤더가 이미 위에 있다 — 둘 다 그리면 머리가
// 두 개가 되고 "LORE" 가 두 번 나온다.
//
// 전역 오염을 막으려고 .webtoon-page 스코프로 감싼다(webtoon.css 참고).
"use client";

import { useEffect, useState } from "react";
import "./webtoon.css";

import Hero from "./sections/Hero";
import HowGalleryFaq from "./sections/HowGalleryFaq";
import Foot from "./sections/Foot";
import Wizard from "./sections/Wizard/Wizard";
import Progress from "./sections/Progress/Progress";
import Result from "./sections/Result/Result";
import Works from "./sections/Works/Works";
import MyPage from "./sections/MyPage/MyPage";
import Editor from "./sections/Editor/Editor";
import { STYLE_INFO, type WizardForm } from "./lib/wizardData";
import { createJob } from "./lib/nhApi";

type View = "landing" | "create" | "running" | "result" | "works" | "mypage" | "editor";

export default function WebtoonPage() {
  // 원본(app.js 의 view())은 body[data-view] 로 화면을 스위치한다. 여기서는
  // 그것을 상태로 둔다.
  const [view, setView] = useState<View>("landing");
  /** 지금 지켜보고 있는 작업. 진행 화면이 이것으로 서버에 묻는다. */
  const [jobId, setJobId] = useState<string | null>(null);
  /** 다 만들어진 작품. 결과·편집실이 이것으로 서버에 묻는다. */
  const [runId, setRunId] = useState<string | null>(null);
  const [styleLabel, setStyleLabel] = useState("");
  const goHome = () => setView("landing");

  /* 주소로 바로 열기 — `/webtoon?run=<id>` 는 그 작품의 완성본 화면이다.
     공유 링크가 이 길로 들어온다(원본 app.js 의 `?run=` 과 같은 자리).
     남의 작품이면 결과 화면이 알아서 내려받기·편집실을 감춘다. */
  useEffect(() => {
    const asked = new URLSearchParams(window.location.search).get("run");
    if (asked) { setRunId(asked); setView("result"); }
  }, []);

  /* 그림을 그냥 저장해 가지 못하게 — 오른쪽 누르기와 끌어다 놓기.
     원본은 base.js 가 document 에 걸지만, 여기는 Lore 앱 안이라 이 화면
     안에서만 막는다(앱 전체의 오른쪽 누르기를 뺏을 자리가 아니다).
     폰의 길게 누르기와 끌기는 webtoon.css 의 img 규칙이 같이 막는다.
     ⚠ 막는 것이 아니라 문턱이다 — 주소를 알면 그대로 받을 수 있다. */
  const guardImage = (ev: React.SyntheticEvent) => {
    if ((ev.target as HTMLElement)?.tagName === "IMG") ev.preventDefault();
  };

  /* 만들기 시작. 실패는 **위자드가 그 자리에서** 보여줘야 하므로 여기서
     삼키지 않고 그대로 던진다 — 진행 화면으로 넘어가 버리면 무엇이
     잘못됐는지 볼 자리가 없다(원본 startRun 과 같은 이유). */
  const start = async (form: WizardForm) => {
    const got = await createJob({
      name: form.name.trim(),
      character: form.character.trim(),
      photo_note: "",
      fields: {},
      genre: form.genre.trim(),
      // 「어떤 이야기를 만들까요?」에 적은 것. 한동안 이 값을 안 보내서
      // 사람이 적은 이야기가 그대로 버려지고 있었다 — 물어보고 버리면
      // 사람은 자기가 적은 것이 반영된 줄 안다.
      story: form.story.trim(),
      style: form.style,
      photos_data: form.photos,
      agree_ip: form.agreeIp,
    });
    setStyleLabel(STYLE_INFO.find(([key]) => key === form.style)?.[1] || "");
    setJobId(got.id);
    setView("running");
  };

  return (
    <div
      className={`webtoon-page${view === "result" ? " is-result" : ""}`}
      onContextMenu={guardImage}
      onDragStart={guardImage}
    >
      {view === "landing" && (
        <>
          <Hero onStart={() => setView("create")} onBrowse={() => setView("works")} />
          <HowGalleryFaq onSeeFull={() => setView("result")} />
          <Foot />
        </>
      )}
      {view === "create" && <Wizard onClose={goHome} onSubmit={start} />}
      {view === "running" && jobId && (
        <Progress
          jobId={jobId}
          styleLabel={styleLabel}
          onExit={goHome}
          onDone={(id) => { setRunId(id); setView("result"); }}
        />
      )}
      {view === "result" && (
        <Result runId={runId} onExit={goHome} onEditor={() => setView("editor")} />
      )}
      {view === "works" && (
        <Works
          onOpen={(id) => { setRunId(id); setView("result"); }}
          onCreate={() => setView("create")}
          onHome={goHome}
        />
      )}
      {view === "mypage" && (
        <MyPage
          onOpenWork={() => setView("result")}
          onCreate={() => setView("create")}
          onBrowse={() => setView("works")}
          onHome={goHome}
        />
      )}
      {/* 편집실은 완성본에서 들어온다 — 그 작품 그 회차를 그대로 연다.
          runId 가 없으면 원본과 같이 샘플이 열린다. */}
      {view === "editor" && (
        <Editor
          runId={runId || undefined}
          onOpenRun={(id) => { setRunId(id); }}
        />
      )}
    </div>
  );
}
