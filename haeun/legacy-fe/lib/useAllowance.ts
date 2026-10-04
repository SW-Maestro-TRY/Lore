"use client";

import { useEffect, useState } from "react";

/* 지금 이 사람이 무엇으로 만드는가 — 무료 몇 편이 남았는지, 크레딧이 얼마인지.
 *
 * **로그인 안 한 사람에게 화면이 「−12크레딧」이라고 적고 있었다.** 그 사람에게는
 * 크레딧이 아예 없다(게스트는 하루 무료 편수로 센다). 없는 값을 낸다고 적어 두고,
 * 정작 몇 편이 남았는지는 어디에도 없어서 다 쓰고 나서야 "오늘 2편 다 쓰셨어요" 를
 * 처음 본다 — 그때는 이미 사진을 올리고 다섯 걸음을 걸어온 뒤다.
 *
 * 그래서 **시작하기 전에** 알려 준다. 못 받아 오면 아무 말도 안 한다(null) —
 * 틀린 숫자를 적느니 안 적는 게 낫다. */

export interface Allowance {
  logged_in: boolean;
  credit_cost: number;
  /** 게스트만. 오늘 남은 무료 편수. */
  free_left?: number | null;
  free_per_day?: number;
  /** 로그인한 사람만. */
  balance?: number;
  /** 오늘 전체 몫이 찼으면 그 이유. 이건 로그인해도 안 풀린다. */
  blocked?: string | null;
  /** 화질 셋과 각각의 크레딧. **서버가 정한다** — 화면에 따로 적어 두면
   *  적힌 값과 실제로 빠지는 값이 어긋난다. 못 받아 오면 값을 안 그린다. */
  qualities?: { key: string; label: string; credits: number }[];
  quality_default?: string;
}

const BASE = process.env.NEXT_PUBLIC_WEBTOON_API || "/api/webtoon/v1";

export function useAllowance(): Allowance | null {
  const [got, setGot] = useState<Allowance | null>(null);
  useEffect(() => {
    let alive = true;
    fetch(`${BASE}/nh/allowance`)
      .then((r) => (r.ok ? r.json() : null))
      .then((v) => { if (alive && v) setGot(v as Allowance); })
      .catch(() => { /* 못 받아 오면 아무 말도 안 한다 */ });
    return () => { alive = false; };
  }, []);
  return got;
}

/** 한 줄로 적으면 무엇이 되나. 적을 것이 없으면 빈 문자열. */
export function allowanceLine(a: Allowance | null): string {
  if (!a) return "";
  if (a.blocked) return a.blocked;
  /* **문장으로 쓰지 않는다.** 값을 확인하러 흘깃 보는 자리라, 읽어야 하는
     문장보다 눈에 걸리는 딱지가 맞다. */
  if (!a.logged_in) {
    if (a.free_left == null) return "";
    return a.free_left > 0
      ? `오늘 무료 ${a.free_left}편`
      : "오늘 무료 소진 · 로그인하면 이어서";
  }
  return `한 편 ${a.credit_cost}크레딧 · 보유 ${a.balance ?? 0}C`;
}
