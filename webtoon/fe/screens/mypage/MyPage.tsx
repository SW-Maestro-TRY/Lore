"use client";

/* 마이페이지 — 디자인 캔버스의 MyPage.dc.html / MMyPage.dc.html.
 *
 * 왼쪽 줄(계정 · 크레딧 · 메뉴)과 오른쪽(내 웹툰 그리드 + 내 캐릭터 줄)이다.
 * 작품 카드의 공개 스위치는 둘러보기(Works)와 **같은 규칙**을 쓴다 — 서버에
 * 먼저 보내고 실패하면 되돌린다. 두 화면이 달라지면 같은 작품이 화면마다 다른
 * 상태로 보인다.
 *
 * 아트보드의 「충전」·「내역」 단추는 안 붙였다. 계정 단위 충전·사용내역을 주는
 * API 가 아직 없어서, 지금 붙이면 눌러도 아무 일이 없는 단추가 된다. */
import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import {
  coverUrl, listCharacters, myAccountRuns, myBrowserRuns, readAllowance, setVisibility,
  type Allowance, type Character, type RunCard,
} from "../../lib/api";
import type { Go } from "../../lib/nav";
import { LangSwitch, registerDict, useT } from "../../lib/i18n";
import { IconUser } from "../../ui/Icons";
import "./MyPage.css";

registerDict({
  "마이페이지": { en: "My page", ja: "マイページ", zh: "我的页面" },
  "로그인됨": { en: "Signed in", ja: "ログイン中", zh: "已登录" },
  "로그인 안 함": { en: "Not signed in", ja: "未ログイン", zh: "未登录" },
  "크레딧": { en: "Credits", ja: "クレジット", zh: "点数" },
  "한 편 {n} C": { en: "{n} C per episode", ja: "1話 {n} C", zh: "每话 {n} C" },
  "만든 것": { en: "MADE", ja: "作ったもの", zh: "已创作" },
  "재료": { en: "MATERIALS", ja: "素材", zh: "素材" },
  "계정": { en: "ACCOUNT", ja: "アカウント", zh: "账号" },
  "내 웹툰": { en: "My webtoons", ja: "マイウェブトゥーン", zh: "我的漫画" },
  "내 캐릭터": { en: "My characters", ja: "マイキャラクター", zh: "我的角色" },
  "로그아웃": { en: "Sign out", ja: "ログアウト", zh: "退出登录" },
  "언어": { en: "Language", ja: "言語", zh: "语言" },
  "둘러보기": { en: "Browse", ja: "見てまわる", zh: "浏览" },
  "새 웹툰 만들기": { en: "New webtoon", ja: "新しいウェブトゥーン", zh: "新建漫画" },
  "캐릭터 탭으로": { en: "Go to characters", ja: "キャラクタータブへ", zh: "前往角色页" },
  "{n}편": { en: "{n} episodes", ja: "{n}話", zh: "{n} 话" },
  "{n}편 · 나만 보기 {m}": { en: "{n} episodes · {m} private", ja: "{n}話 · 非公開 {m}", zh: "{n} 话 · 私密 {m}" },
  "{n}화": { en: "EP.{n}", ja: "第{n}話", zh: "第{n}话" },
  "공개": { en: "Public", ja: "公開", zh: "公开" },
  "비공개": { en: "Private", ja: "非公開", zh: "私密" },
  "둘러보기에 공개": { en: "Show in Browse", ja: "見てまわるに公開", zh: "在浏览中公开" },
  "편집실": { en: "Editor", ja: "編集室", zh: "编辑室" },
  "바꾸지 못했습니다": { en: "Couldn't change it", ja: "変更できませんでした", zh: "无法更改" },
  "웹툰을 만들 때마다 다시 적지 않아도 돼요": {
    en: "So you don't have to describe them again each time",
    ja: "作るたびに書き直さなくて済みます",
    zh: "这样每次创作就不用重写了",
  },
  "만들기": { en: "Create", ja: "作る", zh: "制作" },
  "아직 만든 웹툰이 없어요": { en: "No webtoons yet", ja: "まだ作品がありません", zh: "还没有作品" },
  "제목 없음": { en: "Untitled", ja: "無題", zh: "无标题" },
});

export default function MyPage({ go }: { go: Go }) {
  const t = useT();
  const { user, isAuthenticated, signOut } = useAuth();

  const [runs, setRuns] = useState<RunCard[]>([]);
  const [chars, setChars] = useState<Character[]>([]);
  const [allowance, setAllowance] = useState<Allowance | null>(null);

  /* 내 작품 — 로그인했으면 계정 것과 이 브라우저 것을 합친다(기기를 바꾸면
     둘이 다르다). run_id 로 겹치는 것을 걸러낸다. */
  const loadRuns = useCallback(async () => {
    const lists = await Promise.all([
      myBrowserRuns().catch(() => [] as RunCard[]),
      isAuthenticated ? myAccountRuns().catch(() => [] as RunCard[]) : Promise.resolve([] as RunCard[]),
    ]);
    const seen = new Set<string>();
    const merged: RunCard[] = [];
    for (const r of lists.flat()) {
      if (seen.has(r.run_id)) continue;
      seen.add(r.run_id);
      merged.push(r);
    }
    setRuns(merged);
  }, [isAuthenticated]);

  useEffect(() => { void loadRuns(); }, [loadRuns]);
  useEffect(() => {
    listCharacters().then((l) => setChars(l.characters.filter((c) => c.mine))).catch(() => {});
    readAllowance().then(setAllowance).catch(() => {});
  }, [isAuthenticated]);

  const hidden = runs.filter((r) => r.public === false).length;

  return (
    <div className="wt-wrap wt-page wt-my">
      <aside className="wt-my-rail">
        <div className="card wt-my-me">
          <span className="wt-my-avatar"><IconUser size={18} /></span>
          <b>{user?.email?.split("@")[0] || t("마이페이지")}</b>
          <span className="dim">{isAuthenticated ? t("로그인됨") : t("로그인 안 함")}</span>
        </div>

        {allowance?.logged_in && (
          <div className="card wt-my-credit">
            <span className="dim">{t("크레딧")}</span>
            <b>◈ {(allowance.balance ?? 0).toLocaleString()} <span>C</span></b>
            <span className="dim">{t("한 편 {n} C", { n: allowance.credit_cost })}</span>
          </div>
        )}

        <div className="card rail wt-my-menu">
          <small>{t("만든 것")}</small>
          <button type="button" className="on">{t("내 웹툰")} <span className="dim">{runs.length}</span></button>
          <small>{t("재료")}</small>
          <button type="button" onClick={() => go("characters")}>
            {t("내 캐릭터")} <span className="dim">{chars.length}</span>
          </button>
          {isAuthenticated && (
            <>
              <small>{t("계정")}</small>
              <button type="button" onClick={() => void signOut()}>{t("로그아웃")}</button>
            </>
          )}
        </div>

        <div className="card wt-my-lang">
          <label>{t("언어")}</label>
          <LangSwitch />
        </div>
      </aside>

      <div className="wt-my-main">
        <div className="wt-my-head">
          <div>
            <h2>{t("내 웹툰")}</h2>
            <span className="muted">
              {hidden > 0
                ? t("{n}편 · 나만 보기 {m}", { n: runs.length, m: hidden })
                : t("{n}편", { n: runs.length })}
            </span>
          </div>
          <div className="wt-my-headacts">
            <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("둘러보기")}</button>
            <button type="button" className="btn btn-p" onClick={() => go("entry")}>{t("새 웹툰 만들기")}</button>
          </div>
        </div>

        {runs.length === 0 ? (
          <span className="dim">{t("아직 만든 웹툰이 없어요")}</span>
        ) : (
          <div className="wt-my-grid">
            {runs.map((r) => <WorkCard key={r.run_id} run={r} go={go} />)}
          </div>
        )}

        <div className="wt-my-head wt-my-head-sub">
          <div>
            <h2>{t("내 캐릭터")}</h2>
            <span className="muted">{t("웹툰을 만들 때마다 다시 적지 않아도 돼요")}</span>
          </div>
          <button type="button" className="btn btn-w" onClick={() => go("characters")}>{t("캐릭터 탭으로")}</button>
        </div>
        <div className="wt-my-chars">
          {chars.map((c) => (
            <button type="button" key={c.id} className="wt-my-char" onClick={() => go("characters")}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={c.art_url || ""} alt="" />
              <b>{c.name}</b>
            </button>
          ))}
          <button type="button" className="wt-my-char-new" onClick={() => go("try")}>
            <b>+</b>{t("만들기")}
          </button>
        </div>
      </div>
    </div>
  );
}

/** 작품 한 칸 — 표지 · 제목 · 회차 · 공개 스위치 · 편집실. (둘러보기와 같은 규칙) */
function WorkCard({ run, go }: { run: RunCard; go: Go }) {
  const t = useT();
  const [pub, setPub] = useState(run.public !== false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  /* 서버에 먼저 보내고, 실패하면 되돌린다 — 화면만 바뀌어 있으면 다음에
     들어왔을 때 값이 달라 보인다. */
  const flip = async () => {
    const want = !pub;
    setBusy(true);
    setErr("");
    setPub(want);
    try {
      const out = await setVisibility(run.run_id, want);
      setPub(out.public ?? want);
    } catch {
      setPub(!want);
      setErr(t("바꾸지 못했습니다"));
    } finally {
      setBusy(false);
    }
  };

  const open = () => go("result", { run: run.run_id });

  return (
    <div className="card wt-my-work">
      <button type="button" className="wt-my-cover" onClick={open} aria-label={run.title || t("제목 없음")}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={coverUrl(run.run_id, run.cover_page ?? 1, run.cover_episode ?? 1, !!run.example)} alt="" />
      </button>
      <b>{run.title || t("제목 없음")}</b>
      <span className="muted">{[run.character, run.genre].filter(Boolean).join(" · ")}</span>
      <div className="wt-my-eps">
        {run.episodes.map((n) => (
          <button key={n} type="button" className="ep" onClick={open}>{t("{n}화", { n })}</button>
        ))}
      </div>
      <div className="wt-my-workfoot">
        <span className="wt-my-pub">
          <button type="button" className={`sw${pub ? "" : " off"}`} role="switch" aria-checked={pub}
                  aria-label={t("둘러보기에 공개")} disabled={busy} onClick={() => void flip()}><i /></button>
          {pub ? t("공개") : t("비공개")}
        </span>
        <button type="button" className="btn btn-w wt-my-edit" onClick={() => go("editor", { run: run.run_id })}>
          {t("편집실")}
        </button>
      </div>
      {err && <span className="wt-my-err">{err}</span>}
    </div>
  );
}
