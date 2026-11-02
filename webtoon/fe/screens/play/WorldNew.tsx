"use client";
/* 세계관 만들기 — 세계 · 등장인물 · 사건을 적고 저장한다. */
import { useState } from "react";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconClose, IconPlus } from "../../ui/Icons";
import { saveWorld } from "./playData";
import "./i18n";
import "./Play.css";

export default function WorldNew({ go, authenticated }: { go: Go; authenticated: boolean }) {
  const t = useT();
  const [name, setName] = useState("");
  const [one, setOne] = useState("");
  const [rule, setRule] = useState("");
  const [cast, setCast] = useState([{ name: "", role: "" }]);
  const [events, setEvents] = useState([{ title: "", situation: "" }]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const ready = name.trim() && one.trim() && events.some((e) => e.title.trim() && e.situation.trim());
  const save = async () => {
    setBusy(true); setErr("");
    try {
      const r = await saveWorld({ name: name.trim(), one: one.trim(), rule: rule.trim(),
        cast: cast.filter((c) => c.name.trim()), events: events.filter((e) => e.title.trim() && e.situation.trim()) });
      go("play", { world: r.id });
    } catch { setErr(t("저장하지 못했어요. 다시 눌러 주세요.")); setBusy(false); }
  };
  return (
    <div className="pl"><div className="pl-form">
      <button type="button" className="pl-back" onClick={() => go("make")}>‹ {t("만들기")}</button>
      <h1 className="pl-h1">{t("세계관 만들기")}</h1>
      <label><span>{t("세계 이름")}</span><input value={name} maxLength={40} onChange={(e) => setName(e.target.value)} /></label>
      <label><span>{t("한 줄 소개")}</span><input value={one} maxLength={120} onChange={(e) => setOne(e.target.value)} /></label>
      <label><span>{t("세계 규칙")}</span><textarea rows={3} value={rule} maxLength={500} onChange={(e) => setRule(e.target.value)} /></label>
      <div className="blk"><span>{t("등장인물")}</span>
        {cast.map((c, i) => (
          <div className="ln" key={i}>
            <input placeholder={t("이름")} aria-label={t("이름")} value={c.name} onChange={(e) => setCast(cast.map((v, n) => n === i ? { ...v, name: e.target.value } : v))} />
            <input placeholder={t("역할")} aria-label={t("역할")} value={c.role} onChange={(e) => setCast(cast.map((v, n) => n === i ? { ...v, role: e.target.value } : v))} />
            {cast.length > 1 && <button type="button" className="rm" aria-label={t("삭제")} onClick={() => setCast(cast.filter((_, n) => n !== i))}><IconClose /></button>}
          </div>))}
        <button type="button" className="more" onClick={() => setCast([...cast, { name: "", role: "" }])}><IconPlus />{t("등장인물 추가")}</button>
      </div>
      <div className="blk"><span>{t("사건")}</span>
        {events.map((c, i) => (
          <div className="ev" key={i}>
            <div className="ln">
              <input placeholder={t("사건 제목")} aria-label={t("사건 제목")} value={c.title} onChange={(e) => setEvents(events.map((v, n) => n === i ? { ...v, title: e.target.value } : v))} />
              {events.length > 1 && <button type="button" className="rm" aria-label={t("삭제")} onClick={() => setEvents(events.filter((_, n) => n !== i))}><IconClose /></button>}
            </div>
            <textarea rows={3} placeholder={t("상황")} aria-label={t("상황")} value={c.situation} onChange={(e) => setEvents(events.map((v, n) => n === i ? { ...v, situation: e.target.value } : v))} />
          </div>))}
        <button type="button" className="more" onClick={() => setEvents([...events, { title: "", situation: "" }])}><IconPlus />{t("사건 추가")}</button>
      </div>
      {err && <p className="pl-err">{err}</p>}
      {!authenticated && <p className="pl-err">{t("로그인하면 세계관을 저장할 수 있어요")}</p>}
      <button type="button" className="pl-btn" disabled={!ready || busy || !authenticated} onClick={save}>{t("저장")}</button>
    </div></div>
  );
}
