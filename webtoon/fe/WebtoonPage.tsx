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
// 아직 mock 인 것: 결과 · 둘러보기 · 마이페이지 · 편집실. 이 넷은 다음
// 차례다 — 그때까지는 원본의 목업 경로를 그대로 쓴다.
//
// **자기 머리는 안 그린다.** 원본은 혼자 뜨는 페이지라 자기 머리(.topbar)가
// 필요했지만, 여기서는 Lore 앱 헤더가 이미 위에 있다 — 둘 다 그리면 머리가
// 두 개가 되고 "LORE" 가 두 번 나온다.
//
// 전역 오염을 막으려고 .webtoon-page 스코프로 감싼다(webtoon.css 참고).
"use client";

import { useState } from "react";
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
  const [styleLabel, setStyleLabel] = useState("");
  const goHome = () => setView("landing");

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
    <div className={`webtoon-page${view === "result" ? " is-result" : ""}`}>
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
          onDone={() => setView("result")}
        />
      )}
      {view === "result" && <Result onExit={goHome} onEditor={() => setView("editor")} />}
      {view === "works" && (
        <Works onOpen={() => setView("result")} onCreate={() => setView("create")} onHome={goHome} />
      )}
      {view === "mypage" && (
        <MyPage
          onOpenWork={() => setView("result")}
          onCreate={() => setView("create")}
          onBrowse={() => setView("works")}
          onHome={goHome}
        />
      )}
      {view === "editor" && <Editor onHome={goHome} />}
    </div>
  );
}
