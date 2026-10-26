"use client";
import { useT } from "../../lib/i18n";
import "./i18n";
import { useEffect, useState } from "react";
import * as api from "../../lib/community";
import { browseRuns, coverUrl, type RunCard } from "../../lib/api";
import "./Community.css";

/* 소식 — 내 작업실의 종 단추(CommunityBell)를 누르면 뜨는 작은 창의 내용.
 * 예전에는 「기다리던 이야기」라는 큰 칸으로 맨 위에 늘 펼쳐져 있었는데, 소식이 없을 때도
 * 자리를 크게 차지해서 뜬금없다는 피드백(2026-10-24)으로 탭 셋(알림 · 구독 중 · 신고)으로 접었다.
 * 읽음 처리 · 구독 취소 · 신고 처리는 예전 그대로다. */
type Tab = "notices" | "following" | "reports";

/** 표지 — 못 불러오면 자리만 남긴다 */
function Cover({ run }: { run: string | null }) {
  const [broken, setBroken] = useState(false);
  if (!run || broken) return <span className="wt-community-cover" aria-hidden="true" />;
  // eslint-disable-next-line @next/next/no-img-element
  return <img className="wt-community-cover" src={coverUrl(run, 1)} alt="" loading="lazy" onError={() => setBroken(true)} />;
}

export default function CommunityInbox({ admin = false, onUnread }: { admin?: boolean; onUnread?: (n: number) => void }) {
  const t = useT();
  const [items, setItems] = useState<api.Notice[]>([]);
  const [following, setFollowing] = useState<api.Followed[]>([]);
  const [works, setWorks] = useState<RunCard[]>([]);
  const [reports, setReports] = useState<api.Report[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [tick, setTick] = useState(0);
  const [tab, setTab] = useState<Tab>("notices");
  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.all([
      api.notices(),
      api.subscriptions(),
      browseRuns(),
      admin ? api.reports() : Promise.resolve([]),
    ])
      .then(([n, s, w, r]) => {
        if (alive) {
          setItems(n);
          setFollowing(s);
          setWorks(w);
          setReports(r);
          setError("");
          onUnread?.(n.filter((x) => !x.read_at).length);
        }
      })
      .catch((e) => {
        if (alive) setError(api.errorText(e));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick, admin]);
  async function act(f: () => Promise<unknown>) {
    setBusy(true);
    setError("");
    try {
      await f();
      setTick((t) => t + 1);
    } catch (e) {
      setError(api.errorText(e));
    } finally {
      setBusy(false);
    }
  }
  const title = (run: string | null) =>
    works.find((w) => w.run_id === run)?.title || t("구독한 작품");
  const unread = items.filter((n) => !n.read_at);
  const when = (iso: string) => new Date(iso).toLocaleString(undefined, { month: "numeric", day: "numeric", hour: "numeric", minute: "2-digit" });
  return (
    <section className="wt-community" aria-label={t("소식")}>
      <div className="wt-community-head">
        <b>{t("소식")}</b>
        {tab === "notices" && unread.length > 0 && (
          <button type="button" className="wt-community-link" disabled={busy}
                  onClick={() => void act(() => Promise.all(unread.map((n) => api.readNotice(n.id))))}>
            {t("모두 읽음")}
          </button>
        )}
      </div>
      <div className="wt-community-tabs" role="tablist" aria-label={t("소식")}>
        <button type="button" role="tab" aria-selected={tab === "notices"} className={tab === "notices" ? "on" : ""} onClick={() => setTab("notices")}>
          {t("알림")}{unread.length > 0 && ` ${unread.length}`}
        </button>
        <button type="button" role="tab" aria-selected={tab === "following"} className={tab === "following" ? "on" : ""} onClick={() => setTab("following")}>
          {t("구독 중")}{following.length > 0 && ` ${following.length}`}
        </button>
        {admin && (
          <button type="button" role="tab" aria-selected={tab === "reports"} className={tab === "reports" ? "on" : ""} onClick={() => setTab("reports")}>
            {t("신고")}{reports.length > 0 && ` ${reports.length}`}
          </button>
        )}
      </div>
      {error && (
        <p role="alert" className="wt-community-error">
          {t(error)}{" "}
          <button type="button" className="wt-community-link" onClick={() => setTick((x) => x + 1)}>{t("다시 시도")}</button>
        </p>
      )}
      {loading && <p role="status" className="muted">{t("소식을 불러오는 중…")}</p>}
      {busy && <p role="status" className="muted">{t("처리 중이에요…")}</p>}

      {!loading && tab === "notices" && (
        !items.length && !error ? (
          <p className="muted wt-community-empty">
            {t("아직 새 소식이 없어요. 구독한 작품의 새 화와 내 작품 댓글, 캐릭터로 만든 작품을 여기서 알려 드려요.")}
          </p>
        ) : (
          <ul className="wt-community-notices">
            {items.map((n) => (
              <li key={n.id} className={!n.read_at ? "unread" : ""}>
                <a href={`/webtoon?run=${encodeURIComponent(n.run_id)}`}
                   onClick={() => { void api.readNotice(n.id).catch(() => {}); }}>
                  <Cover run={n.run_id} />
                  <span>
                    <b>{t(n.message)}</b>
                    <time dateTime={n.created_at}>{when(n.created_at)}</time>
                  </span>
                </a>
                {!n.read_at && (
                  <button type="button" className="wt-community-link" disabled={busy}
                          onClick={() => void act(() => api.readNotice(n.id))}>
                    {t("읽음 표시")}
                  </button>
                )}
              </li>
            ))}
          </ul>
        )
      )}

      {!loading && tab === "following" && (
        !following.length && !error ? (
          <p className="muted wt-community-empty">{t("마음에 드는 작품에서 ‘작품 구독’을 눌러 보세요.")}</p>
        ) : (
          <ul className="wt-community-notices">
            {following.map((s) => (
              <li key={s.root_job}>
                {s.available && s.run_id ? (
                  <a href={`/webtoon?run=${encodeURIComponent(s.run_id)}`}>
                    <Cover run={s.run_id} />
                    <span><b>{title(s.run_id)}</b></span>
                  </a>
                ) : (
                  <span className="wt-community-gone"><Cover run={null} /><span>{t("현재 공개되지 않은 작품")}</span></span>
                )}
                <button type="button" className="wt-community-link" disabled={busy}
                        onClick={() => void act(() => api.unfollowRoot(s.root_job))}>
                  {t("구독 취소")}
                </button>
              </li>
            ))}
          </ul>
        )
      )}

      {!loading && admin && tab === "reports" && (
        !reports.length ? (
          <p className="muted wt-community-empty">{t("처리할 신고가 없어요.")}</p>
        ) : (
          reports.map((r) => (
            <div className="wt-comment" key={r.id}>
              <b>
                {r.kind === "comment" ? t("댓글") : t("캐릭터")}
                {t("신고")}
              </b>
              <p>{r.content || t("삭제된 내용")}</p>
              <p>
                {t("사유:")}
                {r.reason}
              </p>
              <div className="wt-community-actions">
                <button className="btn btn-w btn-sm" type="button" disabled={busy}
                        onClick={() => void act(() => api.resolveReport(r.id, true))}>
                  {busy ? t("처리 중…") : t("숨기고 처리 완료")}
                </button>
                <button className="btn-ghost" type="button" disabled={busy}
                        onClick={() => void act(() => api.resolveReport(r.id, false))}>
                  {busy ? t("처리 중…") : t("조치 없이 처리 완료")}
                </button>
              </div>
            </div>
          ))
        )
      )}
    </section>
  );
}
