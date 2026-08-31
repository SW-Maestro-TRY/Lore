// Webtoon 탭의 실제 화면. (담당: 하은)
//
// haeun/landing 프로토타입(index.html + app.js)을 React 로 옮기는 중이다.
// 지금까지: 홈(랜딩) + 위자드(캐릭터 만들기 5걸음) + 진행 화면(로컬로 흉내
// 낸 상태, Progress/useFakeProgress 참고) + 결과 화면(원본의 결과-목업
// 경로를 그대로 씀, Result 참고) + 둘러보기(Works, 결과 화면과 같은 목업
// 한 편을 카드로 보여준다) + 마이페이지(MyPage, 원본의 마이페이지 목업
// 경로를 그대로 씀) + 편집실(Editor, 원본의 "샘플 보기" 경로를 그대로 씀
// — lib/editorCore.ts 참고). 이걸로 원본 landing 의 화면 여섯 개를 전부
// 옮겼다.
//
// 백엔드가 아직 없어서 실제 API가 필요한 자리는 전부 mock 데이터로 채운다
// (진행 화면의 로컬 타이머, 결과·둘러보기·마이페이지·편집실 화면의 mock
// 데이터) — 화면 이식 자체를 미루지 않는다. 백엔드가 생기면 이 mock들을
// 하나씩 실제 API 호출로 바꾸면 된다.
//
// 전역 오염을 막으려고 .webtoon-page 스코프로 감싼다(webtoon.css 참고).
// 편집실만 예외다 — 원본에서 이 화면은 자기 머리(.ed-top)를 가진 독립
// 페이지라, 공용 TopBar 를 겹쳐 그리지 않는다(아래 렌더링 참고).
"use client";

import { useState } from "react";
import "./webtoon.css";

import TopBar from "./sections/TopBar";
import Hero from "./sections/Hero";
import Wizard from "./sections/Wizard/Wizard";
import Progress from "./sections/Progress/Progress";
import Result from "./sections/Result/Result";
import Works from "./sections/Works/Works";
import MyPage from "./sections/MyPage/MyPage";
import Editor from "./sections/Editor/Editor";
import { emptyWizardForm, type WizardForm } from "./lib/wizardData";

type View = "landing" | "create" | "running" | "result" | "works" | "mypage" | "editor";

export default function WebtoonPage() {
  // 원본(app.js 의 view())은 body[data-view] 로 여섯 화면을 스위치한다.
  // 이제 다 옮겨서 이 일곱(편집실 포함)을 그대로 상태로 둔다.
  const [view, setView] = useState<View>("landing");
  const [submittedForm, setSubmittedForm] = useState<WizardForm>(emptyWizardForm());
  const goHome = () => setView("landing");

  return (
    <div className={`webtoon-page${view === "result" ? " is-result" : ""}`}>
      {view !== "editor" && <TopBar onAccount={() => setView("mypage")} />}
      {view === "landing" && <Hero onStart={() => setView("create")} onBrowse={() => setView("works")} />}
      {view === "create" && (
        <Wizard
          onClose={goHome}
          onSubmit={(form) => {
            setSubmittedForm(form);
            setView("running");
          }}
        />
      )}
      {view === "running" && (
        <Progress form={submittedForm} onExit={goHome} onDone={() => setView("result")} />
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
