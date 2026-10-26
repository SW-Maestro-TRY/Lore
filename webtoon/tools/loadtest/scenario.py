#!/usr/bin/env python3
"""하루 방문자 수 → 가장 붐비는 한 시간의 접수 · 대기 · 돈 (#646, 2026-10-26).

숫자는 운영 10/4~10/26 실측(haeun/funnel-2026-10-26):
  일반 방문 → 만들기 접수 4.9%(203명 중 10명) · 광고 방문 → 접수 0.18%(3,280명 중 6명)
  접수가 가장 많은 한 시간 = 하루 접수의 15%(23시) · 방문이 가장 많은 한 시간 = 하루의 11%(18시)
  한 편 원가: 파도 약 2,400원 · 너울 8장 기준 약 6,000원(장당 640원 × 9장 + 글)
    python3 scenario.py
"""
from sim import Sim, REAL_1026, REAL_1026_SWELL

CONV = {"일반": 0.049, "광고": 0.0018}
PEAK_CREATE, PEAK_VISIT = 0.15, 0.11
KRW = {"파도": 2400, "너울": 6000}


def peak(n_hour, w, p, prof):
    if n_hour == 0:
        return 0, 0
    res = Sim(w, p, prof, 0.0).run(n_hour, spread=3600)
    waits = sorted(j["first_run"] - j["created"] for j in res)
    tot = sorted(j["ended"] - j["created"] for j in res)
    return waits[-1] / 60, tot[-1] / 60


print("방문/일  유입  접수/일  피크1시간 접수  설정   파도 마지막 사람(분)  너울(분)  하루 원가(파도·너울)")
for v in (1000, 3000, 10000, 30000):
    for src, c in CONV.items():
        day = round(v * c)
        hr = max(1, round(day * PEAK_CREATE))
        for w, p in ((3, 4), (3, 6), (4, 8), (6, 12)):
            _, ts = peak(hr, w, p, REAL_1026)
            _, tw = peak(hr, w, p, REAL_1026_SWELL)
            print(f"{v:>6} {src}  {day:>5}  {hr:>5}         w{w}p{p:<2}  {ts:8.0f}             {tw:6.0f}   "
                  f"{day*KRW['파도']:>10,} · {day*KRW['너울']:,}")
