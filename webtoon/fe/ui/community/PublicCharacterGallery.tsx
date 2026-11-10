"use client";
import { useT } from "../../lib/i18n";
import "./i18n";
import { useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import * as api from "../../lib/community";
import type { Go } from "../../lib/nav";
import { track } from "../../lib/track";
import ReportForm from "./ReportForm";
import "./Community.css";
export default function PublicCharacterGallery({ go }: { go: Go }) {
  const { isAuthenticated } = useAuth();
  const t = useT();
  const [rows, setRows] = useState<api.PublicCharacter[]>([]);
  const [page, setPage] = useState(0);
  const [more, setMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [reporting, setReporting] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError("");
    const id = new URLSearchParams(location.search).get("id");
    const request = id
      ? api.publicCharacter(id).then((c) => [c])
      : api.publicCharacters(page);
    request
      .then((got) => {
        if (alive) {
          setRows((r) => (page === 0 ? got : [...r, ...got]));
          setMore(!id && got.length === 24);
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
  }, [page, tick]);
  return (
    <section className="wt-community" id="public-characters">
      <h3>{t("공개 캐릭터로 새 이야기")}</h3>
      <p className="muted">
        {t(
          "창작자가 함께 쓰도록 공개한 캐릭터예요. 마음에 드는 캐릭터로 나만의 이야기를 시작해 보세요.",
        )}
      </p>
      {loading && <p role="status">{t("캐릭터를 불러오는 중…")}</p>}
      {error && (
        <p role="alert" className="wt-community-error">
          {t(error)}{" "}
          <button
            className="btn-ghost"
            type="button"
            onClick={() => setTick((t) => t + 1)}
          >
            {t("다시 시도")}
          </button>
        </p>
      )}
      {!loading && !error && !rows.length && (
        <p>
          {t("아직 공개된 캐릭터가 없어요. 내 캐릭터를 먼저 공개해 보세요.")}
        </p>
      )}
      <div className="wt-public-grid">
        {rows.map((c) => (
          <article className="wt-public-character" key={c.id}>
            {c.art_url && <img src={c.art_url} alt={c.name} loading="lazy" />}
            <h4>{c.name}</h4>
            <small>
              {t("원작자 ·")}
              {t(c.creator)}
            </small>
            <p>{c.description}</p>
            <button
              className="btn btn-p"
              type="button"
              onClick={() => {
                track("public_character_use", { character: c.id });
                go("create", { step: 1, character: c.id });
              }}
            >
              {t("이 캐릭터로 새 이야기")}
            </button>
            {isAuthenticated && (
              <button
                className="btn-ghost"
                type="button"
                onClick={() => setReporting(c.id)}
              >
                {t("신고")}
              </button>
            )}
            {reporting === c.id && (
              <ReportForm
                kind="character"
                target={c.id}
                onClose={() => setReporting(null)}
              />
            )}
          </article>
        ))}
      </div>
      {more && (
        <button
          type="button"
          className="btn btn-w"
          disabled={loading}
          onClick={() => setPage((p) => p + 1)}
        >
          {t("캐릭터 더 보기")}
        </button>
      )}
    </section>
  );
}
