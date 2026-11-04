"use client";
import { useEffect, useMemo, useState } from "react";
import { coverUrl, recentPlayRuns, type PlayRunCard } from "../../lib/api";
import { useT } from "../../lib/i18n";
import type { Go } from "../../lib/nav";
import type { PlayWorld } from "../play/playData";

/* 홈 「세계관 체험 만화」 칸(2026-11-04, 시안 C) — 세계 탭을 고르면 그 세계에서 만화가 가장 많이 나온 사건 하나와,
 * 그 사건에 서로 다른 캐릭터를 넣어 만든 만화 둘을 나란히 보여 준다. 체험 만화는 일반 「웹툰 둘러보기」에는 안 섞이고
 * 여기와 세계 안 사건별 선반에만 나온다. 공개된 체험 만화가 하나도 없으면 칸을 그리지 않는다. */
export default function PlayRunsRow({ go, worlds }: { go: Go; worlds: PlayWorld[] }) {
  const t = useT();
  const [runs, setRuns] = useState<PlayRunCard[]>([]);
  const [pick, setPick] = useState<string>("");
  useEffect(() => {
    let alive = true;
    recentPlayRuns().then((r) => { if (alive) setRuns(r.runs || []); }).catch(() => {});
    return () => { alive = false; };
  }, []);

  /* 만화가 있는 세계만, 세계 목록 순서대로 */
  const tabs = useMemo(() => worlds.filter((w) => runs.some((r) => r.world_id === w.id)), [worlds, runs]);
  const world = tabs.find((w) => w.id === pick) || tabs[0];
  const shown = useMemo(() => {
    if (!world) return null;
    const mine = runs.filter((r) => r.world_id === world.id);
    /* 사건 고르기 — 다른 캐릭터가 많이 들어간 사건이 먼저, 같으면 최근 만화가 있는 사건 */
    const byEvent = new Map<string, PlayRunCard[]>();
    for (const r of mine) byEvent.set(r.event_id, [...(byEvent.get(r.event_id) || []), r]);
    const people = (list: PlayRunCard[]) => new Set(list.map((r) => r.character)).size;
    const [eventId, list] = [...byEvent].sort((a, b) => people(b[1]) - people(a[1]))[0];
    const event = world.events.find((e) => e.id === eventId);
    /* 둘 — 캐릭터가 겹치지 않게 최근 것부터 */
    const two: PlayRunCard[] = [];
    for (const r of list) if (two.length < 2 && !two.some((x) => x.character === r.character)) two.push(r);
    for (const r of list) if (two.length < 2 && !two.includes(r)) two.push(r);
    return event ? { event, two } : null;
  }, [world, runs]);

  if (!world || !shown) return null;
  const { event, two } = shown;
  return (
    <section className="wt-play-row wt-prun">
      <div className="wt-play-row-h">
        <h2>{t("세계관 체험 만화")}</h2>
        <button type="button" onClick={() => go("play")}>{t("전부 보기")} ›</button>
      </div>
      <div className="wt-prun-tabs" role="tablist">
        {tabs.map((w) => (
          <button type="button" role="tab" key={w.id} aria-selected={w.id === world.id} className={w.id === world.id ? "on" : ""} onClick={() => setPick(w.id)}>
            {t(w.name)}
          </button>
        ))}
      </div>
      <div className="wt-prun-board">
        <button type="button" className="wt-prun-event" onClick={() => go("play", { world: world.id, ep: event.id })}>
          {/* eslint-disable-next-line @next/next/no-img-element */}
          {event.image_url ? <img src={event.image_url} alt="" /> : <span className="none"><small>{event.no}</small><b>{t(event.title)}</b></span>}
          <span className="go" onClick={(x) => { x.stopPropagation(); go("play", { world: world.id, ep: event.id, stage: "char" }); }}>{t("내 캐릭터로 만들기")}</span>
        </button>
        <div className="wt-prun-pair">
          {two.map((r) => (
            <button type="button" key={r.run_id} className="wt-prun-run" onClick={() => go("play", { world: world.id, ep: event.id, stage: "done", run: r.run_id })}>
              <span className="who"><b>{r.character}<em>{t("주인공")}</em></b>{r.page_count > 0 && <i>{t("{n}장", { n: r.page_count })}</i>}</span>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              {r.cover_page ? <img src={coverUrl(r.run_id, r.cover_page, r.cover_episode ?? 1)} alt="" loading="lazy" /> : <span className="none" />}
              <span className="ttl">{r.title}</span>
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}
