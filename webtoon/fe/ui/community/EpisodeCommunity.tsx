"use client";
import { useT } from "../../lib/i18n";
import "./i18n";
import { useCallback, useEffect, useState } from "react";
import * as api from "../../lib/community";
import { track } from "../../lib/track";
import ReportForm from "./ReportForm";
import "./Community.css";
export default function EpisodeCommunity({
  runId,
  authenticated,
}: {
  runId: string;
  authenticated: boolean;
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
        <div>
          <h3>{t("다음 이야기도 함께해요")}</h3>
          <p className="muted">
            {t("구독하면 새 화 소식을 마이페이지 알림에서 볼 수 있어요.")}
          </p>
          {state && (
            <span className="muted">
              {t("{n}명이 이 작품을 기다려요", {
                n: state.subscribers.toLocaleString(),
              })}
            </span>
          )}
        </div>
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
              ? t("구독 취소")
              : t("작품 구독")}
        </button>
      </div>
      <h3>{t("{n}화 이야기", { n: state?.episode || 1 })}</h3>
      <p className="muted">
        {t(
          "인상 깊은 장면이나 다음 화에 대한 기대를 나눠 주세요. 스포일러가 포함될 수 있어요.",
        )}
      </p>
      {authenticated ? (
        <form onSubmit={submit}>
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
            placeholder={t("이 화는 어땠나요?")}
          />
          <div className="wt-community-actions">
            <span className="count muted">{body.length} / 1,000</span>
            <button
              className="btn btn-p btn-sm"
              disabled={busy || !body.trim() || !state}
            >
              {busy ? t("처리 중…") : t("댓글 남기기")}
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
        <p role="status">{t("대화를 불러오는 중…")}</p>
      ) : !rows.length && !error ? (
        <p className="muted">
          {t("아직 댓글이 없어요. 첫 감상을 남겨 주세요.")}
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
