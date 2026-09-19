"use client";

import { useEffect, useState } from "react";

/** 폰 폭(≤767px)인가 — M 보드의 문구·크기를 고를 때 쓴다. 첫 그리기는 PC 로 본다. */
export function usePhone(): boolean {
  const [phone, setPhone] = useState(false);
  useEffect(() => {
    const mq = window.matchMedia("(max-width: 767px)");
    const on = () => setPhone(mq.matches);
    on();
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  return phone;
}
