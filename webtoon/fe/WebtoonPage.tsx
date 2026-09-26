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

import { Suspense, useCallback, useEffect, useMemo, useRef } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useAuth } from "@common/auth/useAuth";
import "./webtoon.css";

import { hrefOf, type Go, type View } from "./lib/nav";
import { linkThisBrowser } from "./lib/api";
import { setView, track } from "./lib/track";
import { LangProvider } from "./lib/i18n";
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
      <LangProvider>
        <WebtoonScreens />
      </LangProvider>
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
  tab?: "settings";
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
    tab: search.get("tab") === "settings" ? ("settings" as const) : undefined,
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
  /* 주소에서 바로 계산한다 — 상태에 넣고 effect 로 맞추면 첫 화면이 한 번
     번쩍 보였다가 바뀐다(직접 주소로 들어올 때 실제로 그랬다). */
  const route = useMemo(() => routeOf(new URLSearchParams(search.toString())), [search]);
  /* 기록에 실릴 「지금 화면」. 효과가 아니라 여기서 바로 적는다 — 자식 화면의 효과가
     부모 효과보다 먼저 돌아서, 효과에서 적으면 자식이 남긴 첫 줄(login_prompt 등)의
     화면 칸이 비어 있었다(#413). */
  setView(route.view);

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
  /* 기록은 로그인 확인이 끝난 뒤부터. ★ 아래 효과들은 이 값(한 번만 바뀜)에만 기대고
     authStatus 자체에는 안 기댄다 — 로그아웃·로그인할 때마다 page_view 와 page_leave 가
     한 번 더 찍혔다(로컬에서 실제로 그랬다). 지금 로그인 상태는 ref 로 읽는다. */
  const authReady = authStatus !== "loading";
  const authenticatedRef = useRef(authenticated);
  authenticatedRef.current = authenticated;

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

  /* 화면마다 얼마나 머물렀나 — 화면이 바뀔 때와 페이지를 떠날 때 앞 화면의 시간을 남긴다.
     ★ 아래 page_view 효과보다 앞에 둔다. 효과는 적힌 순서로 돌아서, 뒤에 두면 setView 가
       먼저 불려 떠난 줄의 view 칸에 새 화면이 적힌다(로컬에서 실제로 그랬다).
     탭이 뒤로 가 있던 시간도 포함된다(그걸 빼면 「기다리는 동안 딴 짓」이 사라진다). */
  const enteredAt = useRef(0);
  const prevView = useRef<View | null>(null);
  useEffect(() => {
    if (!authReady) return;
    const leave = () => {
      if (prevView.current && enteredAt.current) {
        track("page_leave", { target: prevView.current, ms: Date.now() - enteredAt.current });
      }
    };
    leave();
    prevView.current = route.view;
    enteredAt.current = Date.now();
    window.addEventListener("pagehide", leave);
    return () => window.removeEventListener("pagehide", leave);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [route.view, authReady]);

  /* 화면 이동은 전부 여기를 지난다 — 화면마다 따로 달지 않는다(#413).
     로그인 확인이 끝나기 전(loading)에는 기다린다. 안 기다리면 로그인한 사람의
     첫 화면이 전부 로그인 안 한 것으로 찍힌다. */
  useEffect(() => {
    if (!authReady) return;
    track("page_view", {
      step: route.view === "create" ? route.step : undefined,  // 걸음은 만들기 화면에만 뜻이 있다
      run: route.run, logged_in: authenticatedRef.current,
    });
  }, [route.view, route.step, route.run, authReady]);

  /* 방문의 첫 줄 — 깔때기의 분모다. "100명이 들어와서" 의 100 을 여기서 센다.
     한 탭에서 한 번(sessionStorage), 처음 온 브라우저인지는 localStorage 로 안다. */
  useEffect(() => {
    if (!authReady) return;
    try {
      if (sessionStorage.getItem("lore_wt_session")) return;
      sessionStorage.setItem("lore_wt_session", "1");
      const seen = localStorage.getItem("lore_wt_seen");
      localStorage.setItem("lore_wt_seen", "1");
      track("session_start", { kind: seen ? "returning" : "new", logged_in: authenticatedRef.current,
        target: route.view });   // 어느 화면으로 들어왔나(공유 링크면 result · card)
    } catch { /* 저장소가 막혀 있으면 세지 않는다 */ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authReady]);

  /* 이 방문 중에 로그인(또는 가입)했다 — 로그인 창은 공용 헤더 것이라 거기에 기록을
     달지 않고, 웹툰 화면이 본 상태 변화로 센다. 가입인지 로그인인지는 SQL 에서
     users.created_at 과 견주어 가른다(analytics.md). */
  const wasAnonymous = useRef(false);
  useEffect(() => {
    if (authStatus === "anonymous") wasAnonymous.current = true;
    if (authStatus === "authenticated" && wasAnonymous.current) {
      wasAnonymous.current = false;
      track("auth_done", { target: route.view });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [authStatus]);


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
      {route.view === "result" && route.run && <Result runId={route.run} go={go} authenticated={authenticated} />}
      {route.view === "editor" && route.run && <Editor runId={route.run} go={go} authStatus={authStatus} />}
      {route.view === "works" && <Works go={go} authenticated={authenticated} />}
      {route.view === "characters" && <CharList go={go} />}
      {route.view === "try" && <Photo go={go} authenticated={authenticated} />}
      {route.view === "card" && route.id && (
        <PhotoResult id={route.id} shared={false} go={go} authenticated={authenticated} />
      )}
      {route.view === "sharedCard" && route.id && (
        <PhotoResult id={route.id} shared go={go} authenticated={authenticated} />
      )}
      {route.view === "mypage" && <MyPage go={go} initialTab={route.tab} />}
    </div>
  );
}
