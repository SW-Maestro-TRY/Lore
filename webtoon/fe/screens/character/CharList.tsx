"use client";
import { useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import {
  listCharacters,
  type Character,
  type CharacterList,
} from "../../lib/api";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconEdit, IconPlus, IconClose } from "../../ui/Icons";
import { Dialog } from "../../ui/Dialog";
import { CharacterEditDialog } from "./OriginalCharacter";
import CharacterPublication from "../../ui/community/CharacterPublication";
import PublicCharacterGallery from "../../ui/community/PublicCharacterGallery";
import "./i18n";
import "./CharListFinal.css";
export default function CharList({ go }: { go: Go }) {
  const t = useT(),
    auth = useAuth();
  const [list, setList] = useState<CharacterList | null>(null),
    [error, setError] = useState(""),
    [attempt, setAttempt] = useState(0);
  const [selected, setSelected] = useState<Character | null>(null),
    [editing, setEditing] = useState<Character | null>(null),
    [publication, setPublication] = useState<Character | null>(null);
  useEffect(() => {
    let alive = true,
      timer: ReturnType<typeof setTimeout>;
    const load = async () => {
      try {
        const result = await listCharacters();
        if (!alive) return;
        setList(result);
        setError("");
        if (result.characters.some((c) => c.mine && c.status === "drawing"))
          timer = setTimeout(load, 4000);
      } catch (e) {
        if (alive)
          setError(
            e instanceof Error ? e.message : t("목록을 불러오지 못했습니다"),
          );
      }
    };
    void load();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [attempt]);
  const mine = (list?.characters || []).filter((c) => c.mine && !c.builtin),
    examples = (list?.characters || []).filter((c) => c.builtin),
    items = mine.length ? mine : examples;
  const patch = (c: Character) => {
    setList((v) =>
      v
        ? { ...v, characters: v.characters.map((x) => (x.id === c.id ? c : x)) }
        : v,
    );
    if (selected?.id === c.id) setSelected(c);
    if (publication?.id === c.id) setPublication(c);
  };
  return (
    <div className="wt-wrap wt-page wt-characters-final">
      <div className="wt-characters-heading">
        <h1>{mine.length ? t("내 캐릭터") : t("캐릭터 예시")}</h1>
        <button
          className="btn btn-p btn-sm"
          onClick={() => go("character-new")}
        >
          <IconPlus size={17} />
          {t("새 캐릭터")}
        </button>
      </div>
      {error ? (
        <div role="alert">
          {error}
          <button
            className="btn-ghost"
            onClick={() => setAttempt((n) => n + 1)}
          >
            {t("다시 시도")}
          </button>
        </div>
      ) : !list ? (
        <div className="wt-character-posters" aria-busy="true">
          {[0, 1, 2].map((i) => (
            <div
              className="skeleton"
              key={i}
              style={{ height: 300, borderRadius: 14 }}
            />
          ))}
        </div>
      ) : (
        <div
          className={
            mine.length ? "wt-character-posters" : "wt-character-examples"
          }
        >
          {items.map((c) => (
            <article className="wt-character-poster" key={c.id}>
              <button
                className="wt-character-open"
                onClick={() => setSelected(c)}
              >
                <div className="wt-character-portrait">
                  {c.art_url ? (
                    <img src={c.art_url} alt="" />
                  ) : (
                    <span>
                      {c.status === "drawing" ? t("만드는 중") : t("생성 실패")}
                    </span>
                  )}
                </div>
                <div className="wt-character-caption">
                  <h2>{c.builtin ? t(c.name) : c.name}</h2>
                  <p>{c.builtin ? t(c.description) : c.description}</p>
                </div>
              </button>
              {c.mine && !c.builtin && (
                <div className="wt-character-controls">
                  <button
                    className={`wt-character-visibility${c.community_public ? " public" : ""}`}
                    onClick={() => setPublication(c)}
                  >
                    {c.community_public ? t("공개") : t("비공개")}
                  </button>
                  <button
                    className="wt-character-edit"
                    aria-label={t("{name} 수정", { name: c.name })}
                    disabled={c.status === "drawing"}
                    onClick={() => setEditing(c)}
                  >
                    <IconEdit size={15} />
                  </button>
                </div>
              )}
            </article>
          ))}
        </div>
      )}
      <PublicCharacterGallery
        key={mine.map((c) => `${c.id}:${c.community_public}`).join(",")}
        go={go}
      />
      {selected && (
        <Dialog title={selected.name} onClose={() => setSelected(null)}>
          <div className="wt-character-detail">
            {selected.art_url && (
              <img src={selected.art_url} alt={selected.name} />
            )}
            <p>{selected.description}</p>
            {selected.error && <p role="alert">{selected.error}</p>}
            <button
              className="btn btn-p"
              disabled={selected.status !== "ready"}
              onClick={() => go("create", { step: 1, character: selected.id })}
            >
              {t("이 캐릭터로 웹툰 만들기")}
            </button>
            {selected.card && (
              <button
                className="btn btn-w"
                onClick={() => go("card", { id: selected.id })}
              >
                {t("카드 보기")}
              </button>
            )}
            <button className="btn-ghost" onClick={() => setSelected(null)}>
              {t("닫기")}
            </button>
          </div>
        </Dialog>
      )}
      {editing && (
        <CharacterEditDialog
          character={editing}
          authenticated={auth.status === "authenticated"}
          onClose={() => setEditing(null)}
          onSaved={(c) => {
            patch(c);
            setEditing(null);
          }}
          onDeleted={() => {
            setList((v) =>
              v
                ? {
                    ...v,
                    characters: v.characters.filter((c) => c.id !== editing.id),
                  }
                : v,
            );
            setEditing(null);
            if (selected?.id === editing.id) setSelected(null);
          }}
        />
      )}
      {publication && (
        <Dialog title={t("공개 설정")} onClose={() => setPublication(null)}>
          <CharacterPublication
            id={publication.id}
            published={!!publication.community_public}
            blocked={!!publication.community_blocked}
            ready={publication.status === "ready"}
            original={
              publication.source === "upload" ||
              publication.source === "replaced_upload"
            }
            onChange={(value) => {
              patch({ ...publication, community_public: value });
              setPublication(null);
            }}
          />
          <button className="btn-ghost" onClick={() => setPublication(null)}>
            {t("닫기")}
          </button>
        </Dialog>
      )}
    </div>
  );
}
