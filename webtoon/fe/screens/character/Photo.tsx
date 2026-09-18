"use client";

/* 캐릭터 만들어보기 1 · 입력 — Photo.dc.html / MPhoto.dc.html.
 * 전부 선택이다. 「랜덤」은 칸을 채워 보여줄 뿐 바로 만들지 않는다. */
import { useEffect, useRef, useState } from "react";
import {
  listCharacters, listWorlds, randomSeed, readCharacter, WebtoonApiError, type Character, type World,
} from "../../lib/api";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { PHOTO_ACCEPT, readPhoto } from "../../lib/photoFile";
import { IconArrow, IconBack, IconClose, IconDice, IconUpload } from "../../ui/Icons";
import { MobileTop } from "../../ui/TopNav";
import { isLimitError, lastCardId, loadDraft, runTry, saveDraft } from "./draft";
import "./i18n";
import "./Photo.css";

export default function Photo({ go }: { go: Go }) {
  const t = useT();
  const [worlds, setWorlds] = useState<World[]>([]);
  const [photo, setPhoto] = useState<string | undefined>();
  const [description, setDescription] = useState("");
  const [name, setName] = useState("");
  const [worldKey, setWorldKey] = useState("");
  const [worldText, setWorldText] = useState("");
  const [busy, setBusy] = useState<"random" | "make" | null>(null);
  const [err, setErr] = useState("");
  const [limited, setLimited] = useState<string | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    listWorlds().then((r) => setWorlds(r.worlds || [])).catch(() => setWorlds([]));
    const d = loadDraft();
    if (d) {
      setName(d.name);
      setDescription(d.description);
      setPhoto(d.photo);
      setWorldText(d.world);
    }
  }, []);

  /* 초안의 세계관이 프리셋 키면 카드를 켠다 — 프리셋 목록이 온 뒤에야 알 수 있다. */
  useEffect(() => {
    if (worldText && worlds.some((w) => w.key === worldText)) {
      setWorldKey(worldText);
      setWorldText("");
    }
  }, [worlds, worldText]);

  const world = worldText.trim() || worldKey;

  const onFile = async (file: File | undefined) => {
    if (!file) return;
    setErr("");
    try {
      setPhoto(await readPhoto(file));
    } catch (e) {
      setErr(e instanceof Error ? e.message : t("사진을 읽지 못했습니다"));
    }
    if (fileRef.current) fileRef.current.value = "";
  };

  const onRandom = async () => {
    setBusy("random");
    setErr("");
    try {
      const s = await randomSeed();
      setName(s.name || "");
      setDescription(s.description || "");
      if (worlds.some((w) => w.key === s.world)) {
        setWorldKey(s.world);
        setWorldText("");
      } else {
        setWorldKey("");
        setWorldText(s.world || s.world_label || "");
      }
    } catch (e) {
      setErr(e instanceof Error ? e.message : t("랜덤을 못 받았습니다"));
    } finally {
      setBusy(null);
    }
  };

  const onMake = async () => {
    setBusy("make");
    setErr("");
    const draft = { name, description, world, photo };
    saveDraft(draft);
    try {
      const c = await runTry(draft);
      go("card", { id: c.id }, { replace: false });
    } catch (e) {
      if (isLimitError(e)) {
        setLimited(e instanceof WebtoonApiError ? e.message : "");
      } else {
        setErr(e instanceof Error ? e.message : t("캐릭터를 만들지 못했습니다"));
      }
      setBusy(null);
    }
  };

  if (limited !== null) {
    return <LimitView go={go} message={limited} />;
  }

  return (
    <>
      <MobileTop back={{ href: "/webtoon?view=entry", onClick: () => go("entry") }} title={t("캐릭터")} right="1 / 3" />
      <div className="wt-ch-steps"><span className="on" /><span /><span /></div>
      <div className="wt-wrap wt-page">
        <div className="crumb"><b>{t("캐릭터")}</b><i>›</i><span>{t("캐릭터 카드")}</span><i>›</i><span>{t("웹툰")}</span></div>
        <div className="wt-ch-photo-body">
          <div className="wt-ch-photo-left">
            <h2 style={{ fontSize: 32 }}>{t("어떤 캐릭터를 만들어볼까요?")}</h2>
            <span className="muted wt-ch-photo-sub">{t("내 사진도, 최애도, 강아지도, 아무것도 없어도 돼요.")}</span>

            {photo ? (
              <div className="wt-ch-drop has">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={photo} alt={t("넣은 사진")} />
                <b>{t("사진 1장")}</b>
                <button type="button" className="icon-btn wt-ch-drop-x" aria-label={t("사진 빼기")} onClick={() => setPhoto(undefined)}>
                  <IconClose size={16} />
                </button>
              </div>
            ) : (
              <div className="wt-ch-drop">
                <IconUpload size={30} />
                <b>{t("사진을 넣어주세요")} <span className="dim" style={{ fontWeight: 400 }}>{t("· 선택")}</span></b>
                <span className="btn btn-w wt-ch-drop-pick" style={{ height: 42 }}>{t("파일 고르기")}</span>
                <span className="dim wt-ch-drop-hint" style={{ fontSize: 11.5 }}>{t("내 사진 · 최애 사진 · 그림 · 캐릭터 이미지")}</span>
                <input ref={fileRef} type="file" accept={PHOTO_ACCEPT} aria-label={t("사진 고르기")}
                       onChange={(e) => void onFile(e.target.files?.[0])} />
              </div>
            )}

            <div className="fieldset">
              <label htmlFor="wt-ch-ds">{t("캐릭터에 대해 알려주세요")} <span className="dim" style={{ fontWeight: 400, fontSize: 12 }}>{t("선택")}</span></label>
              <input id="wt-ch-ds" className="field" value={description} placeholder={t("예) 차가운 성격의 마법사")}
                     onChange={(e) => setDescription(e.target.value)} />
            </div>
            <div className="fieldset wt-ch-name">
              <label htmlFor="wt-ch-nm">{t("이름")} <span className="dim" style={{ fontWeight: 400, fontSize: 12 }}>{t("선택 · 비우면 지어요")}</span></label>
              <input id="wt-ch-nm" className="field" value={name} placeholder={t("예: 몽이, 세라핀")}
                     onChange={(e) => setName(e.target.value)} />
            </div>
            <span className="dim" style={{ fontSize: 12.5 }}>{t("사진은 캐릭터를 그린 뒤 지워요. 남의 사진은 팬 창작 범위 안에서만.")}</span>
          </div>

          <div className="wt-ch-photo-right">
            <label style={{ fontSize: 15 }}>{t("세계관")} <span className="dim" style={{ fontWeight: 400, fontSize: 12 }}>{t("안 고르면 랜덤")}</span></label>
            <div className="wt-ch-worlds">
              {worlds.map((w) => (
                <button type="button" key={w.key} className={`opt${worldKey === w.key && !worldText.trim() ? " on" : ""}`}
                        onClick={() => { setWorldKey(worldKey === w.key ? "" : w.key); setWorldText(""); }}>
                  <b>{t(w.label)}</b>
                </button>
              ))}
            </div>
            <div className="fieldset">
              <label htmlFor="wt-ch-wd">{t("직접 쓰기")} <span className="dim" style={{ fontWeight: 400, fontSize: 12 }}>{t("선택")}</span></label>
              <input id="wt-ch-wd" className="field" value={worldText} placeholder={t("예: 무협 / 좀비 아포칼립스 / 우주 해적")}
                     onChange={(e) => { setWorldText(e.target.value); if (e.target.value.trim()) setWorldKey(""); }} />
            </div>
            {err && <span className="err">{err}</span>}
          </div>
        </div>

        <div className="wt-ch-photo-foot">
          <button type="button" className="btn-ghost" onClick={() => go("entry")}><IconBack size={16} /> {t("처음으로")}</button>
          <div className="wt-ch-acts">
            <button type="button" className="btn btn-w" disabled={busy !== null} onClick={() => void onRandom()}>
              {busy === "random" ? <span className="spin" /> : <IconDice size={20} />} {t("랜덤으로 만들어보기")}
            </button>
            <button type="button" className="btn btn-p" disabled={busy !== null} onClick={() => void onMake()}>
              {t("캐릭터 만들기")} {busy === "make" ? <span className="spin" style={{ borderTopColor: "#fff" }} /> : <IconArrow size={18} />}
            </button>
          </div>
        </div>
      </div>

      <div className="mfoot">
        <span className="dim wt-ch-photo-cost">{t("약 30초 · 무료")}</span>
        <button type="button" className="btn btn-p" style={{ height: 52 }} disabled={busy !== null} onClick={() => void onMake()}>
          {busy === "make" ? <span className="spin" style={{ borderTopColor: "#fff" }} /> : null} {t("캐릭터 만들기")}
        </button>
        <button type="button" className="btn btn-w" disabled={busy !== null} onClick={() => void onRandom()}>
          {busy === "random" ? <span className="spin" /> : <IconDice size={20} />} {t("랜덤으로 만들어보기")}
        </button>
      </div>
    </>
  );
}

/* ---- 오늘 한도를 다 썼을 때 — Limit.dc.html / MLimit.dc.html ---------------- */

function untilMidnight(): string {
  const now = new Date();
  const mid = new Date(now);
  mid.setHours(24, 0, 0, 0);
  let s = Math.max(0, Math.floor((mid.getTime() - now.getTime()) / 1000));
  const h = Math.floor(s / 3600); s -= h * 3600;
  const m = Math.floor(s / 60); s -= m * 60;
  return [h, m, s].map((n) => String(n).padStart(2, "0")).join(":");
}

export function LimitView({ go, message }: { go: Go; message?: string }) {
  const t = useT();
  const [last, setLast] = useState<Character | null>(null);
  const [perDay, setPerDay] = useState<number | null>(null);
  const [left, setLeft] = useState(untilMidnight());

  useEffect(() => {
    const id = lastCardId();
    if (id) readCharacter(id).then(setLast).catch(() => setLast(null));
    listCharacters().then((l) => setPerDay(l.free_per_day)).catch(() => {});
    const t = setInterval(() => setLeft(untilMidnight()), 1000);
    return () => clearInterval(t);
  }, []);

  const title = perDay ? t("오늘 다시 뽑기 {n}번을 다 썼어요", { n: perDay }) : (message || t("오늘 다시 뽑기를 다 썼어요"));

  return (
    <>
      <MobileTop back={{ href: "/webtoon?view=try", onClick: () => go("try") }} title={t("캐릭터 카드")} right="2 / 3" />
      <div className="wt-ch-steps"><span className="on" /><span className="on" /><span /></div>
      <div className="wt-wrap wt-page">
        <div className="crumb"><span>{t("캐릭터")}</span><i>›</i><b>{t("캐릭터 카드")}</b><i>›</i><span>{t("웹툰")}</span></div>
        <div className="wt-ch-limit">
          <div className="wt-ch-limit-box">
            {last?.art_url && (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={last.art_url} alt="" />
            )}
            <h2>{title}</h2>
            <span className="muted" style={{ fontSize: 15, lineHeight: 1.6 }}>{t("자정에 다시 채워져요. 지금 카드는 그대로 공유하거나 웹툰으로 만들 수 있어요.")}</span>
            <span className="dim" style={{ fontSize: 13 }}>{t("다시 채워지기까지 {left}", { left })}</span>
            <div className="wt-ch-limit-acts">
              {last && (
                <button type="button" className="btn btn-p" onClick={() => go("card", { id: last.id })}>{t("이 캐릭터로 1화 보기")}</button>
              )}
              <span className="btn btn-w" style={{ cursor: "default" }}>{t("가입하면 하루 10번")}</span>
            </div>
          </div>
        </div>
      </div>
      <div className="mfoot">
        {last && (
          <button type="button" className="btn btn-p" style={{ height: 52 }} onClick={() => go("card", { id: last.id })}>{t("이 캐릭터로 1화 보기")}</button>
        )}
        <span className="btn btn-w" style={{ cursor: "default" }}>{t("가입하면 하루 10번")}</span>
      </div>
    </>
  );
}
