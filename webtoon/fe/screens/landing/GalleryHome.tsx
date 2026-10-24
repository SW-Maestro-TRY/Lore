"use client";
import { useEffect, useRef, useState, type ReactNode } from "react";
import { myActiveJobs, type NhJob } from "../../lib/api";
import type { Go } from "../../lib/nav";
import { useT } from "../../lib/i18n";
import { activeJobLabel } from "../../lib/jobLabel";
import { track } from "../../lib/track";
import "./GalleryHome.css";
import IntroReel from "./IntroReel";
import CtaBand from "./CtaBand";
const asset = (path: string) => `/static/gallery/${path}`;
/* 「다른 장르의 웹툰」 — 예시 작품에서 장르가 겹치지 않게 여덟 편. 표지는 각 작품 1쪽 */
const works = [
  { id: "20261003T213320-5297d4", title: "가면을 쓰고 온 옛 약혼자", genre: "로맨스 판타지", cover: "covers/ex-20261003T213320-5297d4.jpg" },
  { id: "20260926T132032-278bf7", title: "이름을 빌린 검", genre: "무협", cover: "covers/ex-20260926T132032-278bf7.jpg" },
  { id: "20260930T223006-456ff0", title: "마왕성 공략 라이브, 한 번뿐인 시청자", genre: "게임 판타지", cover: "covers/ex-20260930T223006-456ff0.jpg" },
  { id: "20260930T202722-95952b", title: "사라진 배달통과 마왕성 순간이동", genre: "판타지 코미디", cover: "covers/ex-20260930T202722-95952b.jpg" },
  { id: "20260919T125314-423cf5", title: "대기실 밖에서는", genre: "아이돌", cover: "covers/ex-20260919T125314-423cf5.jpg" },
  { id: "20261001T220531-92a2c1", title: "코드로 푼 던전, 몬스터가 답장을 했다", genre: "헌터 · 액션", cover: "covers/ex-20261001T220531-92a2c1.jpg" },
  { id: "20261003T133334-b5ccce", title: "카페 사장에게는 비밀이 많다", genre: "현대 느와르", cover: "covers/ex-20261003T133334-b5ccce.jpg" },
  { id: "20261012T065511-71abb3", title: "용사 갱신 시험에 끼어든 마왕", genre: "판타지", cover: "covers/ex-20261012T065511-71abb3.jpg" },
];
/* 첫 칸 옆 세 칸 — 위 넓은 칸 하나, 아래 세로 칸 둘 */
const heroTiles = [
  { img: "hero/masked-face.jpg", pos: "center top", run: works[0].id, title: works[0].title, genre: works[0].genre, alt: "가면을 쓴 옛 약혼자의 얼굴 컷" },
  /* 아래 두 칸은 예시 작품에서 — 하나는 예쁜 그림, 하나는 전투씬 */
  { img: "hero/rose-moon.jpg", pos: "30% center", run: "20260919T153128-5f3882", title: "[그림자 위의 장미]", genre: "로맨스 판타지", alt: "달빛 아래 은발 영애가 편지를 든 컷" },
  { img: "hero/mage-battle.jpg", pos: "30% center", run: "20260922T175955-6d4a46", title: "마법사의 하루는 반복된다", genre: "판타지 · 전투", alt: "마법진 위에서 마법을 쏘는 마법사 컷" },
];
/* 「그리기 전에 장면부터 확인」 — 줄을 누르면 왼쪽 그림이 그 장면으로 바뀐다(세이엘 예시) */
const storyRows = [
  { img: "seiel/ball1.jpg", where: "무도회장 입구", what: "문이 열리고 두 사람이 들어선다", line: "무도회장의 문이 열리고," },
  { img: "seiel/ball2.jpg", where: "홀 중앙", what: "황태자와 약혼자가 입장한다", line: "모두의 시선이 그들을 향했다." },
  { img: "seiel/ball3.jpg", where: "세이엘 클로즈업", what: "숙련된 미소를 짓는다", line: "완벽한 약혼녀로 서 있었다." },
  { img: "seiel/ball4.jpg", where: "두 사람의 옆얼굴", what: "속마음이 드러난다", line: "모두 계획된 역할이었다." },
];
const REDRAW_REASONS = ["캐릭터가 이상해요", "표정이 안 맞아요", "포즈가 어색해요", "배경이 이상해요"];
const faqs = [
  ["LORE는 어떤 서비스인가요?", "내 캐릭터를 넣으면, 그 캐릭터가 주인공인 웹툰이 나와요. 이야기를 장면으로 나누고 컷으로 그려요."],
  ["장면이 바뀌어도 같은 캐릭터인가요?", "캐릭터를 먼저 정해 두니까, 장면이 바뀌어도 내 캐릭터 그대로 이어져요."],
  ["그리기 전에 장면과 대사를 고칠 수 있나요?", "이야기를 적으면 장면과 대사로 나눠 보여 줘요. 직접 고친 뒤 웹툰으로 만들 수 있어요."],
  ["마음에 안 드는 장면만 다시 그릴 수 있나요?", "그 장면만 다시 그려요. 무엇이 아쉬운지 고르고 하고 싶은 말을 덧붙일 수 있어요."],
  ["캐릭터가 없어도 시작할 수 있나요?", "사진을 넣어도, 이야기를 적어도, 아무것도 없이 시작해도 돼요. 고른 세계관에 맞춰 캐릭터를 만들어 드려요."],
];
export default function GalleryHome({
  go,
  children,
}: {
  go: Go;
  children?: ReactNode;
}) {
  const t = useT();
  const [job, setJob] = useState<NhJob | null>(null);
  const [sceneAt, setSceneAt] = useState(0);
  /* 「다른 장르의 웹툰」 — 여덟 편 중 네 편만 보이고, 몇 초마다 네 편이 한꺼번에 옆으로 넘어간다.
   * 넘어갈 때는 지금 안 보이던 네 편을 섞어서 보여 준다(하나씩 바뀌면 정신없다는 피드백). */
  const SHOWN = 4;
  const [shown, setShown] = useState<number[]>(() => [0, 1, 2, 3]);
  const [turn, setTurn] = useState(0);
  const holdRef = useRef(false);
  const shownRef = useRef<number[]>([0, 1, 2, 3]);
  useEffect(() => {
    const mix = (a: number[]) => [...a].sort(() => Math.random() - 0.5);
    // 처음 네 편도 섞는다(서버 렌더와 어긋나지 않게 화면에 붙은 뒤에)
    shownRef.current = mix(works.map((_, i) => i)).slice(0, SHOWN);
    setShown(shownRef.current);
    if (window.matchMedia?.("(prefers-reduced-motion: reduce)").matches) return;
    const h = window.setInterval(() => {
      if (holdRef.current || document.hidden) return;
      const rest = works.map((_, i) => i).filter((i) => !shownRef.current.includes(i));
      shownRef.current = mix(rest.length >= SHOWN ? rest : works.map((_, i) => i)).slice(0, SHOWN);
      setShown(shownRef.current);
      setTurn((n) => n + 1);
    }, 6000);
    return () => window.clearInterval(h);
  }, []);
  // 다시 그리기 예시 — 실제로 그리지 않고, 시안처럼 「그리는 중」 뒤 예시 컷으로 바꿔 보여 준다
  const [reasons, setReasons] = useState<string[]>(["표정이 안 맞아요"]);
  const [note, setNote] = useState("무대 문 앞이라 조금 더 긴장한 표정으로");
  const [redraw, setRedraw] = useState<"before" | "busy" | "after">("before");
  useEffect(() => {
    if (redraw !== "busy") return;
    const h = window.setTimeout(() => setRedraw("after"), 1300);
    return () => window.clearTimeout(h);
  }, [redraw]);
  useEffect(() => {
    let alive = true;
    myActiveJobs()
      .then((r) => {
        if (alive) setJob(r.jobs?.[0] || null);
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);
  const start = (where: string) => {
    track("landing_cta", { where });
    go("create", { step: 1 });
  };
  return (
    <div className="wt-gallery-home">
      <div className="wt-gallery-inner">
        {job && (
          <button
            className="wt-gallery-resume"
            onClick={() => go("running", { job: job.id })}
          >
            {t("만들던 웹툰")}
            <span>{activeJobLabel(job, t)}</span>
          </button>
        )}
        <section className="wt-gallery-hero">
          <div className="wt-gallery-main">
            {/* 확정 시안(남색·코랄)의 첫 칸 — 그림체 「게임 원화」 예시 그림 */}
            <img
              src="/static/samples/ex-game-1.jpg"
              alt={t("웹툰 속 캐릭터")}
            />
            <div className="wt-gallery-copy">
              <span>{t("AI 웹툰 스튜디오")}</span>
              <h1>
                {t("내 캐릭터로")}
                <br />
                {t("웹툰 만들기")}
              </h1>
              <p className="wt-gallery-sub">{t("캐릭터를 넣으면, 그 캐릭터가 주인공인 웹툰이 나와요.")}</p>
              <div className="wt-gallery-cta">
                <button className="btn btn-p" onClick={() => start("hero")}>
                  {t("웹툰 만들기")}
                </button>
                <button className="btn btn-w" onClick={() => go("works")}>
                  {t("웹툰 둘러보기")}
                </button>
              </div>
            </div>
          </div>
          {/* 확정 시안 — 옆 세 칸은 표지가 아니라 실제 웹툰 장면 */}
          {heroTiles.map((tile, i) => (
            <button
              key={tile.img}
              className={i === 0 ? "wt-gallery-panel" : "wt-gallery-cover"}
              onClick={() => go("result", { run: tile.run })}
            >
              <img src={asset(tile.img)} alt={t(tile.alt)} style={{ objectPosition: tile.pos }} />
              <span>
                {tile.title}
                <small>{t(tile.genre)}</small>
              </span>
            </button>
          ))}
        </section>
        <IntroReel />
        <section className="wt-gallery-section">
          <div className="wt-gallery-heading">
            <span>{t("캐릭터 일관성")}</span>
            <h2>
              {t("같은 얼굴,")}
              <br />
              {t("마지막 컷까지")}
            </h2>
          </div>
          <div className="wt-gallery-consistency">
            <figure className="wt-gallery-sheet">
              <img
                src={asset("masked-fiance/sheet.png")}
                alt={t("로젤 캐릭터 시트")}
              />
              <figcaption>{t("캐릭터 시트 · 로젤")}</figcaption>
            </figure>
            <div className="wt-gallery-faces">
              {[
                [3, "reunion", "무도회장"],
                [7, "confront", "재회"],
                [4, "memory", "회상"],
                [6, "carriage", "빗속 마차"],
              ].map(([page, cl, label]) => (
                <figure key={page} className={`face-${cl}`}>
                  <img
                    src={asset(`masked-fiance/page0${page}.png`)}
                    alt={t(String(label))}
                  />
                  <figcaption>{t(String(label))}</figcaption>
                </figure>
              ))}
            </div>
          </div>
        </section>
        <section className="wt-gallery-section">
          <div className="wt-gallery-heading">
            <span>{t("장면과 대사")}</span>
            <h2>
              {t("그리기 전에")}
              <br />
              {t("장면부터 확인")}
            </h2>
          </div>
          <div className="wt-gallery-storyboard">
            <figure className="wt-gallery-prev">
              <img key={storyRows[sceneAt].img} src={asset(storyRows[sceneAt].img)} alt={t("선택한 장면")} />
              <figcaption>{t("장면 {n}", { n: sceneAt + 1 })}</figcaption>
            </figure>
            <div className="wt-gallery-script">
              {storyRows.map((row, i) => (
                <button key={row.img} aria-current={sceneAt === i} onClick={() => setSceneAt(i)}>
                  <span className="n">{String(i + 1).padStart(2, "0")}</span>
                  <b>{t(row.where)} · {t(row.what)}</b>
                  <q>{t(row.line)}</q>
                </button>
              ))}
            </div>
          </div>
        </section>
        <section className="wt-gallery-section">
          <div className="wt-gallery-heading">
            <span>{t("다시 그리기")}</span>
            <h2>
              {t("아쉬운 장면만")}
              <br />
              {t("다시 그려요")}
            </h2>
          </div>
          <div className="wt-gallery-redraw">
            <figure className={`wt-gallery-redo-pic${redraw === "busy" ? " busy" : ""}`}>
              <img src={asset(redraw === "after" ? "seiel/ball3.jpg" : "seiel/idol2.jpg")} alt={t("다시 그릴 컷")} />
              <figcaption>{redraw === "after" ? t("다시 그린 컷 · 예시") : t("2번째 장")}</figcaption>
            </figure>
            <div className="wt-gallery-redo-form">
              <h3>{t("무엇이 마음에 안 드나요?")}</h3>
              <div className="chips">
                {REDRAW_REASONS.map((r) => (
                  <button key={r} type="button" className="chip" aria-pressed={reasons.includes(r)}
                          onClick={() => setReasons((o) => (o.includes(r) ? o.filter((x) => x !== r) : [...o, r]))}>
                    {t(r)}
                  </button>
                ))}
              </div>
              <label>
                <span>{t("더 하고 싶은 말")}</span>
                <textarea className="field" value={note} onChange={(e) => setNote(e.target.value)} />
              </label>
              <div className="acts">
                <button type="button" className="btn btn-p" disabled={redraw === "busy"}
                        onClick={() => setRedraw(redraw === "after" ? "before" : "busy")}>
                  {redraw === "after" ? t("원래 컷 보기") : t("다시 그리기")}
                </button>
              </div>
            </div>
          </div>
        </section>
        <section className="wt-gallery-section">
          <div className="wt-gallery-heading">
            <h2>{t("다른 장르의 웹툰")}</h2>
          </div>
          <div className={`wt-gallery-works${turn ? " turn" : ""}`} key={turn}
               onMouseEnter={() => { holdRef.current = true; }} onMouseLeave={() => { holdRef.current = false; }}>
            {shown.map((i) => works[i]).map((w) => (
              <button key={w.id} onClick={() => go("result", { run: w.id })}>
                <img src={asset(w.cover)} alt="" />
                <b>{w.title}</b>
                <span>{w.genre}</span>
              </button>
            ))}
          </div>
        </section>
        <CtaBand onStart={() => start("bottom")} />
        <section className="wt-gallery-faq" id="wt-faq">
          <div className="wt-gallery-heading">
            <span>{t("자주 묻는 것")}</span>
            <h2>{t("시작 전에")}</h2>
          </div>
          <div className="faq">
            {faqs.map(([q, a], i) => (
              <details key={q} open={i === 0}>
                <summary>{t(q)}</summary>
                <p>{t(a)}</p>
              </details>
            ))}
          </div>
        </section>
      </div>
      {children}
    </div>
  );
}
