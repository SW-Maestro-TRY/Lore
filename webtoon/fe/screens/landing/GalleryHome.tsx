"use client";
import { useEffect, useRef, useState, type ReactNode } from "react";
import type { Go } from "../../lib/nav";
import { useT } from "../../lib/i18n";
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
/* 자주 묻는 질문 — 기능 소개 말고, 쓰기 전·결제 전에 실제로 걱정하는 것(비용·시간·실패·공개·권리).
 * 답은 이용약관 13조(크레딧)·12조(공개)·11조(이용 범위)와 지금 코드 값에 맞춘다.
 * 「주요」는 top 다섯 개, 분류를 고르면 그 분류만. */
const FAQ_CATS = ["이용 안내", "크레딧·결제", "작품 관리"] as const;
type FaqCat = (typeof FAQ_CATS)[number];
const faqs: { cat: FaqCat; q: string; a: string; top?: boolean }[] = [
  { cat: "이용 안내", top: true, q: "LORE는 무료로 이용할 수 있나요?",
    a: "네. 가입하면 웹툰 한 편을 만들 수 있는 15크레딧을 드리고, 로그인해 있으면 날마다 무료 크레딧이 자동으로 채워져요. 가입하지 않아도 하루 2편까지 만들어 볼 수 있어요." },
  { cat: "이용 안내", top: true, q: "웹툰 한 편을 만드는 데 얼마나 걸리나요?",
    a: "고른 화질에 따라 약 6~15분 걸려요(물결 6분 · 파도 8분 · 너울 15분). 앞에 기다리는 작업이 있으면 조금 더 걸리고, 진행 화면에서 지금 단계와 대기 순번을 볼 수 있어요." },
  { cat: "이용 안내", top: true, q: "웹툰을 만들다가 나가도 괜찮나요?",
    a: "괜찮아요. 만드는 일은 서버에서 계속되니까 창을 닫거나 다른 화면으로 가도 멈추지 않아요. 로그인했거나 알림 받을 이메일을 남겨 두면 다 됐을 때 메일로 알려 드려요." },
  { cat: "이용 안내", q: "결과가 마음에 들지 않으면 어떻게 하나요?",
    a: "완성한 뒤 편집실에서 마음에 안 드는 장면만 골라 다시 그릴 수 있어요. 무엇이 아쉬운지 고르고 하고 싶은 말을 덧붙이면 돼요. 한 장면에 3크레딧(너울로 만든 작품은 4크레딧)이에요." },
  { cat: "크레딧·결제", q: "크레딧은 언제 차감되나요?",
    a: "웹툰 만들기를 시작할 때 차감돼요. 한 편에 15크레딧(너울은 25크레딧), 장면 다시 그리기는 3크레딧(너울은 4크레딧)이에요. 무료로 받은 크레딧부터 먼저 쓰여요." },
  { cat: "크레딧·결제", top: true, q: "생성에 실패하면 크레딧은 어떻게 되나요?",
    a: "만들다가 취소하거나 오류로 실패하면 차감된 크레딧이 자동으로 돌아와요. 다만 정상적으로 완성된 뒤 결과가 마음에 들지 않는 경우는 돌려드리지 않아요." },
  { cat: "크레딧·결제", q: "구매한 크레딧은 환불할 수 있나요?",
    a: "결제하고 7일 안에 하나도 쓰지 않았다면 전액 환불돼요. 그 밖에는 남은 유료 크레딧만큼에서 수수료 10%를 뺀 금액을 돌려드려요. 이미 쓴 크레딧과 무료로 받은 크레딧은 환불되지 않아요." },
  { cat: "크레딧·결제", q: "구매한 크레딧에 유효기간이 있나요?",
    a: "유료 크레딧은 결제한 날부터 5년 동안 쓸 수 있어요. 무료로 받은 크레딧은 따로 유효기간이 없어요. 탈퇴하면 남은 크레딧은 모두 사라지니, 남은 유료 크레딧이 있으면 먼저 환불을 신청해 주세요." },
  { cat: "작품 관리", q: "만든 웹툰은 어디에서 볼 수 있나요?",
    a: "마이페이지의 「내 웹툰」에 그동안 만든 작품이 모두 모여요. 알림 메일을 받았다면 메일 속 링크로도 열 수 있어요. 가입하지 않고 만든 작품은 만든 브라우저에서만 다시 찾을 수 있으니, 방문 기록을 지우기 전에 가입하거나 알림 이메일을 남겨 두세요." },
  { cat: "작품 관리", q: "다른 사람도 내 작품을 볼 수 있나요?",
    a: "완성한 작품은 기본으로 둘러보기에 공개돼요. 로그인해서 만든 작품은 마이페이지에서 언제든 비공개로 바꿀 수 있어요. 로그인 없이 만든 작품은 공개 설정을 바꿀 수 없으니, 남에게 보이면 곤란한 내용은 로그인한 뒤 만들어 주세요." },
  { cat: "작품 관리", top: true, q: "만든 웹툰을 SNS에 올리거나 상업적으로 이용해도 되나요?",
    a: "개인 SNS · 커뮤니티 · 메신저에 공유하는 건 자유예요. 판매하거나 굿즈 · 광고 같은 영리 목적으로 쓰려면 미리 동의를 받아야 해요. 자세한 범위는 이용약관 제11조에 있어요." },
  { cat: "작품 관리", q: "이용하다 문제가 생기면 어디로 문의하나요?",
    a: "마이페이지의 「1:1 문의하기」에서 이메일로 보내 주시거나, lore@lorecomic.com 으로 바로 메일을 주세요. 화면을 찍은 사진을 같이 보내 주시면 더 빨리 확인할 수 있어요." },
];
export default function GalleryHome({
  go,
  children,
}: {
  go: Go;
  children?: ReactNode;
}) {
  const t = useT();
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
  const [faqCat, setFaqCat] = useState<"주요" | FaqCat>("주요");
  const faqShown = faqCat === "주요" ? faqs.filter((f) => f.top) : faqs.filter((f) => f.cat === faqCat);
  useEffect(() => {
    if (redraw !== "busy") return;
    const h = window.setTimeout(() => setRedraw("after"), 1300);
    return () => window.clearTimeout(h);
  }, [redraw]);
  const start = (where: string) => {
    track("landing_cta", { where });
    go("create", { step: 1 });
  };
  return (
    <div className="wt-gallery-home">
      <div className="wt-gallery-inner">
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
          <div className="faq-side">
            <div className="wt-gallery-heading">
              <h2>{t("자주 묻는 질문")}</h2>
            </div>
            <div className="faq-cats" role="tablist">
              {(["주요", ...FAQ_CATS] as const).map((c) => (
                <button key={c} type="button" role="tab" aria-selected={faqCat === c}
                        onClick={() => setFaqCat(c)}>
                  {t(c)}
                </button>
              ))}
            </div>
          </div>
          <div>
            <div className="faq">
              {faqShown.map((f, i) => (
                <details key={f.q} open={i === 0}>
                  <summary>{t(f.q)}</summary>
                  <p>{t(f.a)}</p>
                </details>
              ))}
            </div>
          </div>
        </section>
      </div>
      {children}
    </div>
  );
}
