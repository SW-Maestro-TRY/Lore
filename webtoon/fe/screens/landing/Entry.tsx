"use client";

/* 입구 — 보드 Entry.dc.html(PC) · MEntry.dc.html(폰). 카드 둘 중 하나를 고른다. */
import "./i18n";
import * as api from "../../lib/api";
import { useT } from "../../lib/i18n";
import { hrefOf, type Go } from "../../lib/nav";
import { IconUser } from "../../ui/Icons";
import { usePhone } from "./usePhone";
import "./Entry.css";

/* 왼쪽 카드는 「가면 아래의 대리인」 2쪽 첫 컷을 잘라 둔 정적 그림이다. 창고의
   쪽 그림(w=320)을 쓰면 카드 폭(약 570px)에 늘어나 흐려진다. 오른쪽 카드는 고정 예시
   작품의 표지를 쓰고, 그림이 안 나오면(작품이 빠지는 등) 견본 그림으로 대체한다. */
const COVER_A = "/static/entry/webtoon-cut.jpg";
const COVER_B = api.coverUrl("20260919T153345-f366ce", 1); // 가면 아래의 대리인
const FALLBACK_A = "/static/samples/onboarding-page.jpg";
const FALLBACK_B = "/static/samples/ex-romance-2.jpg";

const IconCamera = ({ size = 20 }: { size?: number }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
       strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M4 8h3l2-3h6l2 3h3v11H4z" /><circle cx="12" cy="13" r="3.5" />
  </svg>
);

export default function Entry({ go }: { go: Go }) {
  const t = useT();
  const phone = usePhone();
  const [covers, setCovers] = useState<string[]>([]);
  useEffect(() => {
    let alive = true;
    api.browseRuns()
      .then((runs) => {
        if (!alive) return;
        setCovers(runs.slice(0, 2).map((r) => api.coverUrl(r.run_id, r.cover_page ?? 1, r.cover_episode ?? 1)));
      })
      .catch(() => { /* 견본 그림으로 둔다 */ });
    return () => { alive = false; };
  }, []);

  /* 카드마다 남은 무료 횟수 — 왼쪽 카드는 웹툰 만들기(허용량), 오른쪽 카드는
     캐릭터 만들기(캐릭터 목록)가 각자 다른 자원이라 API 도 둘로 나뉜다. */
  const [createFree, setCreateFree] = useState<number | null>(null);
  const [charFree, setCharFree] = useState<number | null>(null);
  useEffect(() => {
    let alive = true;
    api.readAllowance().then((a) => { if (alive) setCreateFree(a.free_left ?? null); }).catch(() => {});
    api.listCharacters().then((r) => { if (alive) setCharFree(r.free_left ?? null); }).catch(() => {});
    return () => { alive = false; };
  }, []);

  const to = (fn: () => void) => (ev: React.MouseEvent) => { ev.preventDefault(); fn(); };
  const onImgError = (fallback: string) => (ev: React.SyntheticEvent<HTMLImageElement>) => {
    ev.currentTarget.onerror = null;
    ev.currentTarget.src = fallback;
  };

  return (
    <div className="wt-wrap wt-page wt-entry">
      <div className="wt-entry-head">
        <h2 style={phone ? { whiteSpace: "pre-line" } : undefined}>{t(phone ? "LORE에서\n무엇을 해볼까요?" : "LORE에서 무엇을 해볼까요?")}</h2>
      </div>

      <div className="wt-entry-cards">
        <a href={hrefOf("create", { step: 1 })} className="wt-entry-card on" onClick={to(() => go("create", { step: 1 }))}>
          <div className="wt-entry-pic">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={COVER_A} alt="" className="wt-entry-pic-left" onError={onImgError(FALLBACK_A)} />
            <span className="wt-entry-tag">{t("웹툰 만들기")}</span>
            {createFree != null && <span className="wt-entry-free">{t("남은 무료 {n}", { n: createFree })}</span>}
          </div>
          <div className="wt-entry-body">
            <div className="wt-entry-title"><span className="wt-entry-ic"><IconUser size={20} /></span><b>{t("바로 웹툰을 만들고 싶어요")}</b></div>
            <span className="muted">{t("내가 가진 캐릭터, 최애, 이미지, 설정으로 바로 웹툰을 만들어요.")}</span>
          </div>
        </a>

        <a href={hrefOf("try")} className="wt-entry-card" onClick={to(() => go("try"))}>
          <div className="wt-entry-pic">
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={COVER_B} alt="" onError={onImgError(FALLBACK_B)} />
            <span className="wt-entry-tag">{t("캐릭터 만들어보기")}</span>
            {charFree != null && <span className="wt-entry-free">{t("남은 무료 {n}", { n: charFree })}</span>}
          </div>
          <div className="wt-entry-body">
            <div className="wt-entry-title"><span className="wt-entry-ic"><IconCamera /></span><b>{t("캐릭터를 만들어보고 싶어요")}</b></div>
            <span className="muted">
              {t(phone ? "사진이든 설명이든, 아무것도 없어도 돼요. 뭐든 웹툰 속 캐릭터가 돼요." : "내 사진도, 최애도, 강아지도, 아무것도 없어도 돼요. 뭐든 넣으면 웹툰 속 캐릭터가 돼요.")}
            </span>
          </div>
        </a>
      </div>
    </div>
  );
}
