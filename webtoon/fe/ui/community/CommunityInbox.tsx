"use client";
import { useT } from "../../lib/i18n";
import "./i18n";
import { useEffect, useState } from "react";
import * as api from "../../lib/community";
import { browseRuns, type RunCard } from "../../lib/api";
import "./Community.css";
export default function CommunityInbox({ admin = false }: { admin?: boolean }) {
  const t = useT();
  const [items, setItems] = useState<api.Notice[]>([]);
  const [following, setFollowing] = useState<api.Followed[]>([]);
  const [works, setWorks] = useState<RunCard[]>([]);
  const [reports, setReports] = useState<api.Report[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [tick, setTick] = useState(0);
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
  return (
    <section className="wt-community" aria-label={t("구독과 알림")}>
      {busy && <p role="status">{t("처리 중이에요…")}</p>}
      <div className="wt-community-head">
        <h3>{t("기다리던 이야기")}</h3>
        <button
          type="button"
          className="btn btn-w btn-sm"
          disabled={loading || busy}
          onClick={() => setTick((t) => t + 1)}
        >
          {t("새로고침")}
        </button>
      </div>
      {error && (
        <p role="alert" className="wt-community-error">
          {t(error)}
        </p>
      )}
      {loading && <p role="status">{t("소식을 불러오는 중…")}</p>}
      <h4>
        {t("알림")}{" "}
        {items.filter((n) => !n.read_at).length > 0 &&
          t("· 읽지 않음 {n}", { n: items.filter((n) => !n.read_at).length })}
      </h4>
      {!loading && !items.length && !error && (
        <p className="muted">
          {t(
            "아직 새 소식이 없어요. 구독한 작품의 새 화와 내 작품 댓글, 캐릭터로 만든 작품을 여기서 알려 드려요.",
          )}
        </p>
      )}
      <ul className="wt-community-notices">
        {items.map((n) => (
          <li key={n.id}>
            <a
              className={!n.read_at ? "unread" : ""}
              href={`/webtoon?run=${encodeURIComponent(n.run_id)}`}
              onClick={() => {
                void api.readNotice(n.id).catch(() => {});
              }}
            >
              {t(n.message)}
              <time dateTime={n.created_at}>
                {new Date(n.created_at).toLocaleString()}
              </time>
            </a>
            {!n.read_at && (
              <button
                type="button"
                className="btn-ghost"
                disabled={busy}
                onClick={() => void act(() => api.readNotice(n.id))}
              >
                {t("읽음 표시")}
              </button>
            )}
          </li>
        ))}
      </ul>
      <h4>{t("구독 중인 작품")}</h4>
      {!loading && !following.length && !error && (
        <p className="muted">
          {t("마음에 드는 작품에서 ‘작품 구독’을 눌러 보세요.")}
        </p>
      )}
      <ul className="wt-community-notices">
        {following.map((s) => (
          <li key={s.root_job}>
            {s.available && s.run_id ? (
              <a href={`/webtoon?run=${encodeURIComponent(s.run_id)}`}>
                {title(s.run_id)}
              </a>
            ) : (
              <span>{t("현재 공개되지 않은 작품")}</span>
            )}
            <button
              type="button"
              className="btn-ghost"
              disabled={busy}
              onClick={() => void act(() => api.unfollowRoot(s.root_job))}
            >
              {t("구독 취소")}
            </button>
          </li>
        ))}
      </ul>
      {admin && (
        <>
          <h4>
            {t("커뮤니티 신고")}
            {reports.length > 0 && `· ${reports.length}`}
          </h4>
          {reports.map((r) => (
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
                <button
                  className="btn btn-w btn-sm"
                  type="button"
                  disabled={busy}
                  onClick={() => void act(() => api.resolveReport(r.id, true))}
                >
                  {busy ? t("처리 중…") : t("숨기고 처리 완료")}
                </button>
                <button
                  className="btn-ghost"
                  type="button"
                  disabled={busy}
                  onClick={() => void act(() => api.resolveReport(r.id, false))}
                >
                  {busy ? t("처리 중…") : t("조치 없이 처리 완료")}
                </button>
              </div>
            </div>
          ))}
        </>
      )}
    </section>
  );
}
