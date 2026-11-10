#!/usr/bin/env python3
"""results/matrix/summary.jsonl 를 표로 (마크다운)."""
import json
import sys
from pathlib import Path

path = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).parent / "results/matrix/summary.jsonl")
rows = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
f = lambda v: "-" if v is None else (f"{v:.1f}" if isinstance(v, float) else str(v))
print("| 설정 | N | 완료/실패 | 큐대기 중앙/최대(s) | 전체 중앙/최대(s) | 편/분 | JVM RSS 최대(MB) | CPU% 최대 머신/java+py | java CPU 평균(코어) | 폴링 p95(ms) |")
print("|---|---|---|---|---|---|---|---|---|---|")
for r in rows:
    print(f"| w{r['workers']} p{r['page_workers']} | {r['n']} | {r['completed']}/{r['failed']} "
          f"| {f(r['queue_total_med'])}/{f(r['queue_total_max'])} | {f(r['total_med'])}/{f(r['total_max'])} "
          f"| {f(r['jobs_per_min'])} | {f(r['peak_rss_mb'])} | {r.get('peak_machine_cpu')}/{r.get('peak_java_plus_kids_cpu')} "
          f"| {f(r.get('java_cpu_avg_cores'))} | {f(r['poll_p95_ms'])} |")
