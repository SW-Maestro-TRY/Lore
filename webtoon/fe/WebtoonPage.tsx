"use client";

// Webtoon 탭의 화면. (담당: 하은)
//
// 디자인 캔버스(https://claude.ai/artifact/1LtLUmeSzSbgDUx28xhkKb)를 그대로
// 옮긴 화면들이다. 화면마다 screens/ 아래 한 폴더, 서버에 말 거는 것은
// lib/api.ts 한 곳, 화면 사이 이동 규칙은 lib/nav.ts 한 곳이다.
//
// **자기 머리는 안 그린다.** Lore 앱 헤더(@common/SiteHeader)가 이미 위에
// 있어서, 여기서 또 그리면 "LORE" 가 두 번 나온다 — 보조 헤더도 없다.
//
// 이어받은 것(haeun/legacy-fe/BACKUP.md):
//   - `lore_uid`(localStorage) 키 이름 그대로. 바꾸면 기존 사용자가 만든
//     작품이 전부 남의 것이 된다.
//   - 로그인해 있으면 들어올 때마다 `POST /my/link` — 기기를 바꾸면 uid 가
//     새로 생겨서 한 번만 잇는 것으로는 두 번째 기기가 안 붙는다.
//   - 「내 작품」·공개여부는 자바(`/my/...`)를 거친다.

import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@common/auth/useAuth";
import "./webtoon.css";

import { hrefOf, type Go, type View } from "./lib/nav";
import { linkThisBrowser } from "./lib/api";
import Landing from "./screens/landing/Landing";
import Entry from "./screens/landing/Entry";
import Wizard from "./screens/wizard/Wizard";
import Progress from "./screens/progress/Progress";
import Result from "./screens/result/Result";
import Editor from "./screens/editor/Editor";
import Works from "./screens/works/Works";
import Photo from "./screens/character/Photo";
import PhotoResult from "./screens/character/PhotoResult";
import CharList from "./screens/character/CharList";
import MyPage from "./screens/mypage/MyPage";

export default function WebtoonPage() {
  return (
    <Suspense fallback={null}>
      <WebtoonScreens />
    </Suspense>
  );
}

interface Route {
  view: View;
  step: number;
  character?: string;
  job?: string;
  run?: string;
  id?: string;
}

/* 주소 → 화면. `?run=` 만 있으면 완성본(공유 링크), `?card=` 만 있으면 공유된 카드. */
function routeOf(search: URLSearchParams): Route {
  const view = search.get("view");
  const run = search.get("run") || undefined;
  const card = search.get("card") || undefined;
  const step = Math.min(4, Math.max(1, Number(search.get("step") || 1) || 1));
  const base = {
    step,
    character: search.get("character") || undefined,
    job: search.get("job") || undefined,
    run,
    id: search.get("id") || undefined,
  };
  if (view === "running" && base.job) return { view: "running", ...base };
  if (view === "editor" && run) return { view: "editor", ...base };
  if (view === "card" && base.id) return { view: "card", ...base };
  if (view && ["entry", "create", "works", "characters", "try", "mypage"].includes(view)) {
    return { view: view as View, ...base };
  }
  if (run) return { view: "result", ...base };
  if (card) return { view: "sharedCard", ...base, id: card };
  return { view: "landing", ...base };
}

function WebtoonScreens() {
  const router = useRouter();
  const search = useSearchParams();
  const [route, setRoute] = useState<Route>({ view: "landing", step: 1 });

  useEffect(() => {
    setRoute(routeOf(new URLSearchParams(search.toString())));
  }, [search]);

  const go: Go = useCallback((view, p, opts) => {
    const href = hrefOf(view, p);
    if (opts?.replace) router.replace(href);
    else router.push(href);
  }, [router]);

  /* Lore 앱 헤더는 화면 위에 붙어 따라온다. 높이를 재서 변수로 넘긴다 —
     코드에 적어 두면 헤더가 바뀔 때 조용히 어긋난다. */
  const rootRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const measure = () => {
      const head = [...document.querySelectorAll("header")].find(
        (h) => !h.closest(".wt") && getComputedStyle(h).position === "sticky",
      );
      el.style.setProperty("--lore-header-h", `${Math.round(head?.getBoundingClientRect().height || 0)}px`);
    };
    measure();
    window.addEventListener("resize", measure);
    return () => window.removeEventListener("resize", measure);
  }, []);

  const { status: authStatus } = useAuth();
  useEffect(() => {
    if (authStatus === "authenticated") void linkThisBrowser().catch(() => {});
  }, [authStatus]);
  const authenticated = authStatus === "authenticated";

  /* 그림을 그냥 저장해 가지 못하게 — 오른쪽 누르기와 끌어다 놓기. 글 쓰는
     칸만 비워 둔다(복사·붙여넣기 메뉴는 있어야 한다). 막는 것이 아니라 문턱이다. */
  const guardImage = (ev: React.SyntheticEvent) => {
    const el = ev.target as HTMLElement;
    if (el?.closest?.('input, textarea, [contenteditable="true"], [contenteditable=""]')) return;
    ev.preventDefault();
  };

  useEffect(() => {
    window.scrollTo({ top: 0 });
  }, [route.view, route.step, route.id, route.run, route.job]);

  return (
    <div ref={rootRef} className="wt" onContextMenu={guardImage} onDragStart={guardImage}>
      {route.view === "landing" && <Landing go={go} />}
      {route.view === "entry" && <Entry go={go} />}
      {route.view === "create" && (
        <Wizard step={route.step} presetCharacterId={route.character} go={go} authenticated={authenticated} />
      )}
      {route.view === "running" && route.job && (
        <Progress jobId={route.job} go={go} />
      )}
      {route.view === "result" && route.run && <Result runId={route.run} go={go} />}
      {route.view === "editor" && route.run && <Editor runId={route.run} go={go} />}
      {route.view === "works" && <Works go={go} authenticated={authenticated} />}
      {route.view === "characters" && <CharList go={go} />}
      {route.view === "try" && <Photo go={go} />}
      {route.view === "card" && route.id && (
        <PhotoResult id={route.id} shared={false} go={go} authenticated={authenticated} />
      )}
      {route.view === "sharedCard" && route.id && (
        <PhotoResult id={route.id} shared go={go} authenticated={authenticated} />
      )}
      {route.view === "mypage" && <MyPage go={go} />}
    </div>
  );
}
