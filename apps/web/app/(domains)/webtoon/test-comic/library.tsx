"use client";

import { useEffect, useMemo, useState } from "react";
import styles from "./test-comic.module.css";

export type Work = {
  id: string; title: string; createdAt: string; phase: string;
  episodes: { n: number; number: number; subtitle: string; pages: number; cover: boolean; stitched: boolean }[];
};
type Entry = { workId: string; n: number; number: number; subtitle: string; pages: number; stitched: boolean };
type View = { kind: "shelf" } | { kind: "series"; title: string } | { kind: "read"; title: string; index: number };

/** 만든 웹툰 둘러보기 — 같은 제목은 한 작품(이어 붙인 다음 화 포함). 그림은 비밀번호 머리글로 받아 blob 으로 보여 준다. */
export default function Library({ works, load }: { works: Work[]; load: (workId: string, e: number, file: string) => Promise<string> }) {
  const [view, setView] = useState<View>({ kind: "shelf" });
  const [art, setArt] = useState<Record<string, string>>({});

  const series = useMemo(() => {
    const map = new Map<string, Entry[]>();
    for (const w of works) for (const e of w.episodes) {
      if (!e.cover) continue;  // 표지도 아직 없는 화는 빼 둔다
      const list = map.get(w.title) || [];
      list.push({ workId: w.id, n: e.n, number: e.number, subtitle: e.subtitle, pages: e.pages, stitched: e.stitched });
      map.set(w.title, list);
    }
    return Array.from(map, ([title, list]) => ({ title, list: list.sort((a, b) => a.number - b.number) }))
      .sort((a, b) => b.list[b.list.length - 1].workId.localeCompare(a.list[a.list.length - 1].workId));
  }, [works]);

  const want = useMemo(() => {
    if (view.kind === "shelf") return series.map(s => [s.list[0].workId, s.list[0].n, "page01.png"] as const);
    const s = series.find(x => x.title === view.title);
    if (!s) return [];
    if (view.kind === "series") return s.list.map(e => [e.workId, e.n, "page01.png"] as const);
    const e = s.list[view.index];
    return e ? Array.from({ length: e.pages }, (_, i) => [e.workId, e.n, `page${String(i + 1).padStart(2, "0")}.png`] as const) : [];
  }, [view, series]);

  useEffect(() => {
    want.forEach(([w, e, f]) => {
      const key = `${w}|${e}|${f}`;
      if (art[key]) return;
      load(w, e, f).then(url => setArt(prev => ({ ...prev, [key]: url }))).catch(() => undefined);
    });
  }, [want, art, load]);

  useEffect(() => { window.scrollTo(0, 0); }, [view]);

  if (!series.length) return <section className={styles.panel}><h2>둘러보기</h2><p>아직 다 그린 웹툰이 없어요.</p></section>;

  if (view.kind === "shelf") return <section className={styles.panel}>
    <h2>둘러보기</h2>
    <div className={styles.shelf}>{series.map(s => {
      const c = s.list[0], src = art[`${c.workId}|${c.n}|page01.png`];
      return <button key={s.title} className={styles.card} onClick={() => setView({ kind: "series", title: s.title })}>
        <span className={styles.cover}>{src ? <img src={src} alt={`${s.title} 표지`} /> : null}</span>
        <b>{s.title}</b><span>{s.list.length}화</span>
      </button>;
    })}</div>
  </section>;

  const s = series.find(x => x.title === view.title);
  if (!s) return null;

  if (view.kind === "series") return <section className={styles.panel}>
    <button className={styles.restart} onClick={() => setView({ kind: "shelf" })}>← 둘러보기</button>
    <h2>{s.title}</h2>
    <div className={styles.eps}>{s.list.map((e, i) => {
      const src = art[`${e.workId}|${e.n}|page01.png`];
      return <button key={`${e.workId}-${e.n}`} className={styles.ep} onClick={() => setView({ kind: "read", title: s.title, index: i })}>
        <span className={styles.epCover}>{src ? <img src={src} alt="" /> : null}</span>
        <span><b>{e.number}화</b> {e.subtitle}<br /><small>{e.pages}장{e.stitched ? "" : " · 그리는 중이거나 보류된 장 있음"}</small></span>
      </button>;
    })}</div>
  </section>;

  const e = s.list[view.index];
  return <section className={styles.panel}>
    <button className={styles.restart} onClick={() => setView({ kind: "series", title: s.title })}>← {s.title}</button>
    <h2>{s.title} {e.number}화</h2>
    <div className={styles.comic}>{Array.from({ length: e.pages }, (_, i) => {
      const src = art[`${e.workId}|${e.n}|page${String(i + 1).padStart(2, "0")}.png`];
      return src ? <img key={i} src={src} alt={`${e.number}화 ${i + 1}장`} /> : <div key={i} className={styles.wait}>{i + 1}장</div>;
    })}</div>
    <div className={styles.row}>
      {view.index > 0 ? <button className={styles.ghost} onClick={() => setView({ ...view, index: view.index - 1 })}>← {s.list[view.index - 1].number}화</button> : null}
      {view.index < s.list.length - 1 ? <button onClick={() => setView({ ...view, index: view.index + 1 })}>{s.list[view.index + 1].number}화 →</button>
        : <button className={styles.ghost} onClick={() => setView({ kind: "series", title: s.title })}>목록으로</button>}
    </div>
  </section>;
}
