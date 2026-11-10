"use client";
import { useT } from "../../lib/i18n";
import "./i18n";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import * as api from "../../lib/community";
import { track } from "../../lib/track";
import ReportForm from "./ReportForm";
import "./Community.css";
export default function EpisodeCommunity({
  runId,
  authenticated, title, cover, children, total,
}: {
  runId: string;
  authenticated: boolean;
  title?: string; cover?: string; children?: ReactNode;
  /** 공개된 회차 수 — 카드에 「총 N화」로 적는다 */
  total?: number;
}) {
  const t = useT();
  const [state, setState] = useState<api.Subscription | null>(null);
  const [rows, setRows] = useState<api.Comment[]>([]);
  const [credit, setCredit] = useState<api.Attribution[]>([]);
  const [body, setBody] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const [editing, setEditing] = useState<number | null>(null);
  const [draft, setDraft] = useState("");
  const [reporting, setReporting] = useState<number | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);
  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const [s, c, a] = await Promise.all([
        api.subscription(runId),
        api.comments(runId),
        api.attribution(runId),
      ]);
      setState(s);
      setRows(c);
      setMore(c.length === 30);
      setCredit(a);
    } catch (e) {
      setError(api.errorText(e));
    } finally {
      setLoading(false);
    }
  }, [runId]);
  useEffect(() => {
    let alive = true;
    setBody("");
    setRows([]);
    setState(null);
    setCredit([]);
    setEditing(null);
    setReporting(null);
    setDeleting(null);
    setLoading(true);
    setError("");
    Promise.all([
      api.subscription(runId),
      api.comments(runId),
      api.attribution(runId),
    ])
      .then(([s, c, a]) => {
        if (alive) {
          setState(s);
          setRows(c);
          setMore(c.length === 30);
          setCredit(a);
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
  }, [runId, authenticated]);
  async function act(work: () => Promise<unknown>) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      await work();
    } catch (e) {
      setError(api.errorText(e));
    } finally {
      setBusy(false);
    }
  }
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    await act(async () => {
      await api.addComment(runId, body);
      setBody("");
      track("episode_comment", { run: runId, action: "create" });
      await load();
    });
  }
  return (
    <section className="wt-community" aria-label={t("작품 구독과 회차 댓글")}>
      {busy && <p role="status">{t("처리 중이에요…")}</p>}
      {credit.map((c) => (
        <div className="wt-community-attribution" key={c.character_id}>
          {t("캐릭터 원작:")}{" "}
          {c.available ? (
            <a
              href={`/webtoon?view=characters&id=${encodeURIComponent(c.character_id)}`}
            >
              {c.character_name} · {t(c.creator || t("원작자"))}
            </a>
          ) : (
            <span>
              {c.character_name}
              {t("· 현재 비공개")}
            </span>
          )}
        </div>
      ))}
      <div className="wt-community-head">
        {cover && <img className="wt-community-cover" src={cover} alt=""/>}
        <div className="wt-community-series"><h3>{title || t("작품 구독")}</h3><span className="muted">{t("총 {n}화",{n:total||state?.episode||1})}{state && ` · ${t("구독자 {n}명",{n:state.subscribers.toLocaleString()})}`}</span></div>
        <button
          type="button"
          className={`btn ${state?.subscribed ? "btn-w" : "btn-p"}`}
          aria-pressed={state?.subscribed || false}
          disabled={busy || loading || !state}
          onClick={() => {
            if (!authenticated) {
              setError(
                t(
                  "로그인하면 작품을 구독할 수 있어요. 화면 위 로그인 버튼을 눌러 주세요.",
                ),
              );
              return;
            }
            void act(async () => {
              const s = await api.follow(runId, !state!.subscribed);
              setState(s);
              track("work_subscribe", { run: runId, on: s.subscribed });
            });
          }}
        >
          {busy
            ? t("처리 중…")
            : state?.subscribed
              ? t("구독 중")
              : `+ ${t("구독")}`}
        </button>
      </div>
      {children}
      <h3 className="wt-comments-title">{t("댓글")} <span>{rows.length}{more?"+":""}</span></h3>
      {authenticated ? (
        <form className="wt-comment-compose" onSubmit={submit}>
          <label className="sr-only" htmlFor="episode-comment">
            {t("회차 댓글")}
          </label>
          <textarea
            id="episode-comment"
            className="field"
            value={body}
            onChange={(e) => setBody(e.target.value)}
            maxLength={1000}
            required
            placeholder={t("댓글 입력")}
          />
          <div className="wt-community-actions">
            <span className="count muted">{body.length} / 1,000</span>
            <button
              className="btn btn-p btn-sm"
              disabled={busy || !body.trim() || !state}
            >
              {busy ? t("처리 중…") : t("등록")}
            </button>
          </div>
        </form>
      ) : (
        <p>
          {t(
            "로그인하면 댓글을 남길 수 있어요. 화면 위 로그인 버튼을 눌러 주세요.",
          )}
        </p>
      )}
      {error && (
        <div role="alert" className="wt-community-error">
          {t(error)}{" "}
          <button
            className="btn-ghost"
            type="button"
            disabled={busy}
            onClick={() => void load()}
          >
            {t("다시 불러오기")}
          </button>
        </div>
      )}
      {loading ? (
        <p role="status">{t("댓글 불러오는 중…")}</p>
      ) : !rows.length && !error ? (
        <p className="muted">
          {t("아직 댓글이 없습니다.")}
        </p>
      ) : null}
      <ol className="wt-comments">
        {rows.map((c) => (
          <li className="wt-comment" key={c.id}>
            <div className="wt-comment-meta">
              <b>
                {t(c.author)}
                {c.mine ? t(" · 나") : ""}
              </b>
              <time dateTime={c.created_at}>
                {new Date(c.created_at).toLocaleDateString()}
              </time>
              {c.updated_at !== c.created_at && (
                <span className="muted">{t("수정됨")}</span>
              )}
            </div>
            {editing === c.id ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  void act(async () => {
                    await api.editComment(c.id, draft);
                    setEditing(null);
                    await load();
                  });
                }}
              >
                <label>
                  {t("댓글 수정")}
                  <textarea
                    className="field"
                    maxLength={1000}
                    required
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                  />
                </label>
                <div className="wt-community-actions">
                  <button
                    className="btn btn-p btn-sm"
                    disabled={busy || !draft.trim()}
                  >
                    {busy ? t("처리 중…") : t("저장")}
                  </button>
                  <button
                    type="button"
                    className="btn-ghost"
                    disabled={busy}
                    onClick={() => setEditing(null)}
                  >
                    {t("취소")}
                  </button>
                </div>
              </form>
            ) : (
              <p>{c.body}</p>
            )}
            {c.mine ? (
              <div className="wt-community-actions">
                <button
                  type="button"
                  className="btn-ghost"
                  disabled={busy}
                  onClick={() => {
                    setEditing(c.id);
                    setDraft(c.body);
                  }}
                >
                  {t("수정")}
                </button>
                {deleting === c.id ? (
                  <>
                    <span>{t("댓글을 지울까요?")}</span>
                    <button
                      className="btn-ghost"
                      type="button"
                      disabled={busy}
                      onClick={() =>
                        void act(async () => {
                          await api.deleteComment(c.id);
                          setDeleting(null);
                          await load();
                        })
                      }
                    >
                      {t("삭제 확인")}
                    </button>
                    <button
                      className="btn-ghost"
                      type="button"
                      onClick={() => setDeleting(null)}
                    >
                      {t("취소")}
                    </button>
                  </>
                ) : (
                  <button
                    type="button"
                    className="btn-ghost"
                    onClick={() => setDeleting(c.id)}
                  >
                    {t("삭제")}
                  </button>
                )}
              </div>
            ) : (
              authenticated && (
                <button
                  type="button"
                  className="btn-ghost"
                  onClick={() => setReporting(c.id)}
                >
                  {t("신고")}
                </button>
              )
            )}
            {reporting === c.id && (
              <ReportForm
                kind="comment"
                target={String(c.id)}
                onClose={() => setReporting(null)}
              />
            )}
          </li>
        ))}
      </ol>
      {more && (
        <button
          type="button"
          className="btn btn-w"
          disabled={busy}
          onClick={() =>
            void act(async () => {
              const next = await api.comments(runId, rows[rows.length - 1].id);
              setRows((r) => [...r, ...next]);
              setMore(next.length === 30);
            })
          }
        >
          {t("이전 댓글 더 보기")}
        </button>
      )}
    </section>
  );
}
