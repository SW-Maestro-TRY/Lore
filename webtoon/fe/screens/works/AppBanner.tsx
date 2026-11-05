"use client";

/* 둘러보기 맨 위 띠의 두 번째 장 — LORE 앱 설치하기(2026-11-06).
 * 앱은 아직 없다. 띠를 누르면 출시 알림(50크레딧) + 지금 쓸 수 있는 홈 화면 추가 안내 창이 열린다.
 * PC: 왼쪽 글 + 오른쪽 폰 두 대(문피아 「크리에이터 작가앱 오픈」 배너 배치 — 통째로 보이는 폰 두 대가
 * 아래쪽이 모이는 ∧ 모양, 앞 웹툰 홈 · 뒤 웹툰 보기, 앞 폰 오른쪽 가장자리에 앱 아이콘). 바탕·글은 LORE 어두운 띠.
 * 폰 너비: 가운데 정렬(아이콘 · 제목 · 50크레딧 · 단추), 폰 그림 없음.
 *
 * 조정 모드(?appedit=1): 폰·아이콘을 끌어 옮기고 막대로 기울기·크기를 바꾼 뒤 「값 복사」로 값을 넘긴다.
 * 넘겨받은 값은 아래 POSE 에 그대로 옮긴다. */
import { useEffect, useRef, useState, type CSSProperties, type PointerEvent as RPointerEvent } from "react";
import { createPortal } from "react-dom";
import { useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { AndroidMark, AppleMark, guessDevice, type AppDevice } from "../../ui/AppInstallDialog";
import "./AppBanner.css";

type Part = "home" | "read" | "app";
interface Pose { x: number; y: number; rx: number; ry: number; rz: number; s: number; p: number }
/** x 는 폰 칸 가운데에서 왼쪽 끝까지(px), y 는 위에서(px). 폰은 216×448 로 그려 s 로 줄인다. p 는 원근 거리. */
const POSE: Record<Part, Pose> = {
  home: { x: -175, y: -76, rx: 10, ry: 24, rz: -18, s: 0.6, p: 900 },
  read: { x: -40, y: -62, rx: 10, ry: -28, rz: 16, s: 0.56, p: 900 },
  app: { x: -30, y: 112, rx: 0, ry: 18, rz: -8, s: 1, p: 700 },
};
const styleOf = (q: Pose): CSSProperties => ({
  left: `calc(50% + ${q.x}px)`, top: q.y,
  transform: `perspective(${q.p}px) rotateX(${q.rx}deg) rotateY(${q.ry}deg) rotateZ(${q.rz}deg) scale(${q.s})`,
});
const SLIDERS: [keyof Pose, string, number, number, number][] = [
  ["rx", "앞뒤로 눕히기", -60, 60, 1], ["ry", "좌우로 돌리기", -60, 60, 1], ["rz", "기울기", -45, 45, 1],
  ["s", "크기", 0.3, 1.2, 0.01], ["p", "원근(작을수록 강함)", 400, 2000, 10],
];
const PART_NAME: Record<Part, string> = { home: "앞 폰(홈)", read: "뒤 폰(보기)", app: "아이콘" };

/** 실제 폰처럼 — 금속 테두리 · 옆 단추 · 상태 줄(시각 · 다이내믹 아일랜드 · 배터리) */
function Phone({ className, src, style, onPointerDown }: { className: string; src: string; style: CSSProperties; onPointerDown: (e: RPointerEvent) => void }) {
  return (
    <div className={`phone ${className}`} style={style} onPointerDown={onPointerDown}>
      <div className="screen">
        <span className="status"><b>9:41</b><i className="island" /><i className="batt" /></span>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={src} alt="" draggable={false} />
      </div>
    </div>
  );
}

export default function AppBanner({ onOpen }: { onOpen: (d: AppDevice) => void }) {
  const t = useT();
  const [edit, setEdit] = useState(false);
  const [pose, setPose] = useState(POSE);
  const [sel, setSel] = useState<Part>("home");
  const [copied, setCopied] = useState(false);
  const drag = useRef<{ part: Part; x0: number; y0: number; px: number; py: number } | null>(null);
  useEffect(() => { try { setEdit(new URLSearchParams(location.search).get("appedit") === "1"); } catch { /* 없음 */ } }, []);

  const open = (d: AppDevice, where = "button") => { if (edit) return; track("app_banner_click", { device: d, where }); onOpen(d); };
  /* 띠 어디를 눌러도 창이 열린다 — 단추가 아닌 곳은 지금 기기를 짐작해 그 안내부터 연다 */
  const openAny = () => open(guessDevice(), "banner");

  const grab = (part: Part) => (e: RPointerEvent) => {
    if (!edit) return;
    e.preventDefault(); e.stopPropagation();
    setSel(part);
    drag.current = { part, x0: e.clientX, y0: e.clientY, px: pose[part].x, py: pose[part].y };
  };
  useEffect(() => {
    if (!edit) return;
    const mv = (e: PointerEvent) => {
      const d = drag.current; if (!d) return;
      setPose((p) => ({ ...p, [d.part]: { ...p[d.part], x: Math.round(d.px + e.clientX - d.x0), y: Math.round(d.py + e.clientY - d.y0) } }));
    };
    const up = () => { drag.current = null; };
    window.addEventListener("pointermove", mv); window.addEventListener("pointerup", up);
    return () => { window.removeEventListener("pointermove", mv); window.removeEventListener("pointerup", up); };
  }, [edit]);
  const set = (k: keyof Pose, v: number) => setPose((p) => ({ ...p, [sel]: { ...p[sel], [k]: v } }));
  const copy = () => {
    const txt = JSON.stringify(pose);
    console.log("[appbanner pose]", txt);
    void navigator.clipboard?.writeText(txt).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); }).catch(() => {});
  };

  return (
    <div className={`wt-appb${edit ? " editing" : ""}`} role="button" tabIndex={0} aria-label={t("LORE 앱 설치하기")} onClick={openAny}
         onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); openAny(); } }}>
      <div className="copy">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className="icon" src="/icons/icon-192.png" alt="" />
        <span className="kicker"><i aria-hidden="true" />LORE APP</span>
        <h2>{t("LORE 앱 설치하기")}</h2>
        <p><b>50C</b>{t("출시 알림 신청하면 50크레딧")}</p>
        <div className="btns">
          <button type="button" onClick={(e) => { e.stopPropagation(); open("android"); }}><AndroidMark />Android</button>
          <button type="button" onClick={(e) => { e.stopPropagation(); open("ios"); }}><AppleMark />iPhone</button>
        </div>
      </div>
      <div className="phones" aria-hidden="true">
        <Phone className={`read${edit && sel === "read" ? " sel" : ""}`} src="/static/gallery/app-phone-read.jpg" style={styleOf(pose.read)} onPointerDown={grab("read")} />
        <Phone className={`home${edit && sel === "home" ? " sel" : ""}`} src="/static/gallery/app-phone-home.jpg" style={styleOf(pose.home)} onPointerDown={grab("home")} />
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img className={`app${edit && sel === "app" ? " sel" : ""}`} src="/icons/icon-192.png" alt="" draggable={false} style={styleOf(pose.app)} onPointerDown={grab("app")} />
      </div>
      {edit && typeof document !== "undefined" && createPortal(
        <div className="wt-appb-tune" onClick={(e) => e.stopPropagation()} onPointerDown={(e) => e.stopPropagation()}>
          <div className="tabs">
            {(Object.keys(PART_NAME) as Part[]).map((k) => (
              <button key={k} type="button" className={sel === k ? "on" : ""} onClick={() => setSel(k)}>{PART_NAME[k]}</button>
            ))}
          </div>
          {SLIDERS.map(([k, label, min, max, step]) => (
            <label key={k}><span>{label}<b>{pose[sel][k]}</b></span>
              <input type="range" min={min} max={max} step={step} value={pose[sel][k]} onChange={(e) => set(k, Number(e.target.value))} />
            </label>
          ))}
          <small>폰·아이콘을 끌어 옮기세요 · x {pose[sel].x} · y {pose[sel].y}</small>
          <div className="acts">
            <button type="button" onClick={() => setPose(POSE)}>처음으로</button>
            <button type="button" className="p" onClick={copy}>{copied ? "복사됨" : "값 복사"}</button>
          </div>
        </div>, document.body,
      )}
    </div>
  );
}
