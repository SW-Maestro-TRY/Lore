"use client";
/* 세계관 만들기 — 세계 · 등장인물 · 사건을 적고 저장한다. */
import { useState } from "react";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import { IconClose, IconPlus } from "../../ui/Icons";
import "./i18n";
import "./Play.css";

export default function WorldNew({ go }: { go: Go }) {
  /* 저장 API 는 다음 작업이다(DB 추가 필요) — 그때까지 저장 단추를 막아 둔다. */
  const t = useT();
  const [name, setName] = useState("");
  const [one, setOne] = useState("");
  const [rule, setRule] = useState("");
  const [cast, setCast] = useState([{ name: "", role: "" }]);
  const [events, setEvents] = useState([{ title: "", situation: "" }]);
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
      <button type="button" className="pl-btn" disabled>{t("저장")}</button>
    </div></div>
  );
}
