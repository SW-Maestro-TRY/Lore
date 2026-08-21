#!/usr/bin/env python3
"""캐릭터 일관성 비교 실험 하네스.

  python run.py --condition A          # 조건 하나만
  python run.py --condition A --condition B
  python run.py --all                  # 전부
  python run.py --all --dry-run        # API 호출 없이 최종 프롬프트만 출력
  python run.py --all --skip-existing  # 이미 만들어진 파일은 건너뛰고 이어서
  python run.py --report-only          # 기존 이미지로 compare.html / score_sheet.csv 만 재생성

그림체(스타일) 비교는 변수가 반대인 별도 모드다 (config.yaml 의 style_test 섹션):

  python run.py --style-test --dry-run # 조립된 프롬프트만 출력
  python run.py --style-test           # 스타일 6종 x 2회 = 12장 생성
  python run.py --style-report         # 기존 이미지로 style_compare.html 만 재생성

실험 조건은 전부 config.yaml 에 있다. 이 파일은 고칠 일이 없어야 한다.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

import yaml

from providers import GenRequest, ProviderError, build_provider
from report import build_reports, build_style_report, load_picks, picks_shorthand, save_picks

ROOT = Path(__file__).resolve().parent
OUT_DIR = ROOT / "outputs"
LOG_PATH = OUT_DIR / "log.jsonl"
STYLE_DIR = "style"          # outputs/style/ + log.jsonl 의 condition 값


# --------------------------------------------------------------------------- #
# .env
# --------------------------------------------------------------------------- #
def load_dotenv(path: Path) -> dict[str, str]:
    """의존성 없는 최소 .env 파서. 이미 설정된 환경변수가 우선."""
    import os

    values: dict[str, str] = {}
    if path.exists():
        for raw in path.read_text(encoding="utf-8").splitlines():
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, val = line.removeprefix("export ").partition("=")
            key, val = key.strip(), val.strip().strip("#").strip()
            if len(val) >= 2 and val[0] == val[-1] and val[0] in "\"'":
                val = val[1:-1]
            values[key] = val
    merged = {**values, **{k: v for k, v in os.environ.items() if v}}
    return merged


# --------------------------------------------------------------------------- #
# config
# --------------------------------------------------------------------------- #
def die(msg: str) -> "NoReturn":  # type: ignore[valid-type]
    print(f"[중단] {msg}", file=sys.stderr)
    raise SystemExit(1)


def load_config(path: Path) -> dict[str, Any]:
    if not path.exists():
        die(f"설정 파일이 없습니다: {path}")
    cfg = yaml.safe_load(path.read_text(encoding="utf-8")) or {}

    for key in ("character_prompt", "style_suffix", "scenes", "conditions"):
        if not cfg.get(key):
            die(f"config.yaml 에 '{key}' 가 없습니다.")

    scenes = cfg["scenes"]
    ids = [s.get("id") for s in scenes]
    if not all(ids):
        die("모든 scene 에 id 가 있어야 합니다.")
    if len(set(ids)) != len(ids):
        die(f"scene id 가 중복입니다: {ids}")
    for s in scenes:
        if not s.get("prompt"):
            die(f"scene '{s['id']}' 에 prompt 가 없습니다.")

    cfg.setdefault("repeats", 2)
    cfg.setdefault("prompt_template", "{character}\n\nScene: {scene}\n\n{style}\n{extra}")
    cfg.setdefault("pricing", {}).setdefault("usd_to_krw", 1400)
    cfg.setdefault("limits", {}).setdefault("max_total_calls", 100)
    retry = cfg.setdefault("retry", {})
    retry.setdefault("max_retries", 2)
    retry.setdefault("backoff_sec", 5)
    prov = cfg.setdefault("provider", {})
    prov.setdefault("name", "mock")
    prov.setdefault("model", "mock-1")
    prov.setdefault("cost_per_image_usd", 0.0)
    prov.setdefault("options", {})
    return cfg


def build_prompt(cfg: dict[str, Any], scene: dict[str, Any], cond: dict[str, Any]) -> str:
    text = str(cfg["prompt_template"])
    for token, value in (
        ("{character}", str(cfg["character_prompt"]).strip()),
        ("{scene}", str(scene["prompt"]).strip()),
        ("{style}", str(cfg["style_suffix"]).strip()),
        ("{extra}", str(cond.get("extra") or "").strip()),
    ):
        text = text.replace(token, value)
    text = "\n".join(line.rstrip() for line in text.splitlines())
    return re.sub(r"\n{3,}", "\n\n", text).strip()


# --------------------------------------------------------------------------- #
# jobs
# --------------------------------------------------------------------------- #
@dataclass
class Job:
    condition: str
    scene_id: str
    repeat: int
    prompt: str
    refs: list[Path]
    use_previous_scene: bool
    prev_scene_id: str | None
    out_path: Path
    attachments: list[Path] = field(default_factory=list)  # 실행 직전에 확정


def build_jobs(cfg: dict[str, Any], conditions: list[str]) -> list[Job]:
    repeats = int(cfg["repeats"])
    scenes = cfg["scenes"]
    jobs: list[Job] = []
    for cname in conditions:
        cond = cfg["conditions"][cname]
        refs = [ROOT / r for r in (cond.get("refs") or [])]
        # 조건 D 의 체인이 성립하도록 반복 회차 안에서 장면을 순서대로 돈다.
        for rep in range(1, repeats + 1):
            for idx, scene in enumerate(scenes):
                jobs.append(
                    Job(
                        condition=cname,
                        scene_id=scene["id"],
                        repeat=rep,
                        prompt=build_prompt(cfg, scene, cond),
                        refs=refs,
                        use_previous_scene=bool(cond.get("use_previous_scene")),
                        prev_scene_id=scenes[idx - 1]["id"] if idx > 0 else None,
                        out_path=OUT_DIR / cname / f"{scene['id']}_r{rep}.png",
                    )
                )
    return jobs


# --------------------------------------------------------------------------- #
# 그림체 비교 (--style-test)
# --------------------------------------------------------------------------- #
def load_style_test(cfg: dict[str, Any]) -> dict[str, Any]:
    st = cfg.get("style_test")
    if not st:
        die("config.yaml 에 'style_test' 섹션이 없습니다. README 의 '그림체 비교' 항목을 참고하세요.")
    if not str(st.get("scene") or "").strip():
        die("style_test.scene 이 비어 있습니다. 모든 스타일이 공유할 장면 한 개가 필요합니다.")
    styles = st.get("styles") or []
    if not styles:
        die("style_test.styles 가 비어 있습니다.")
    ids = [s.get("id") for s in styles]
    if not all(ids):
        die("모든 style 에 id 가 있어야 합니다.")
    if len(set(ids)) != len(ids):
        die(f"style id 가 중복입니다: {ids}")
    for s in styles:
        if not s.get("prompt"):
            die(f"style '{s['id']}' 에 prompt 가 없습니다.")
    st.setdefault("repeats", int(cfg.get("repeats", 2)))
    st.setdefault("prompt_template", "{character}\n\nScene: {scene}\n\nArt style: {style}")
    return st


def build_style_prompt(cfg: dict[str, Any], st: dict[str, Any], style: dict[str, Any]) -> str:
    """style_suffix 는 일부러 넣지 않는다 — 측정하려는 변수와 섞이면 안 된다."""
    text = str(st["prompt_template"])
    for token, value in (
        ("{character}", str(cfg["character_prompt"]).strip()),
        ("{scene}", str(st["scene"]).strip()),
        ("{style}", str(style["prompt"]).strip()),
    ):
        text = text.replace(token, value)
    text = "\n".join(line.rstrip() for line in text.splitlines())
    return re.sub(r"\n{3,}", "\n\n", text).strip()


def build_style_jobs(cfg: dict[str, Any], st: dict[str, Any]) -> list[Job]:
    repeats = int(st["repeats"])
    jobs: list[Job] = []
    for style in st["styles"]:
        for rep in range(1, repeats + 1):
            jobs.append(
                Job(
                    condition=STYLE_DIR,
                    scene_id=str(style["id"]),
                    repeat=rep,
                    prompt=build_style_prompt(cfg, st, style),
                    refs=[],
                    use_previous_scene=False,
                    prev_scene_id=None,
                    out_path=OUT_DIR / STYLE_DIR / f"{style['id']}_r{rep}.png",
                )
            )
    return jobs


def apply_style_picks(
    styles: list[dict[str, Any]], picks: dict[str, dict[str, Any]], specs: list[str]
) -> dict[str, dict[str, Any]]:
    """'f1 r2 r4 r6 # 메모' 형태를 파싱해 기존 기록 위에 덮어쓴다.

    r 번호를 하나도 안 쓰면 그 스타일의 선택을 비운다 (메모는 따로 남길 수 있음).
    """
    ids = [str(s["id"]) for s in styles]
    for spec in specs:
        body, _, note = str(spec).partition("#")
        tokens = body.replace(",", " ").split()
        if not tokens:
            die(f"--style-pick '{spec}' 에 스타일 id 가 없습니다. 예: --style-pick \"f1 r2 r4\"")
        key = tokens[0].rstrip(":")
        matched = [i for i in ids if i == key] or [i for i in ids if i.startswith(key)]
        if not matched:
            die(f"'{key}' 에 맞는 style id 가 없습니다. 사용 가능: {', '.join(ids)}")
        if len(matched) > 1:
            die(f"'{key}' 가 여러 style 에 걸립니다: {', '.join(matched)}")
        sid = matched[0]

        reps: list[int] = []
        for tok in tokens[1:]:
            digits = tok.lstrip("rR")
            if not digits.isdigit():
                die(f"--style-pick '{spec}' 의 '{tok}' 을 회차로 못 읽었습니다. r2 형태로 써주세요.")
            reps.append(int(digits))

        entry = picks.setdefault(sid, {"picks": [], "note": ""})
        entry["picks"] = sorted(set(reps))
        if note.strip():
            entry["note"] = note.strip()
    return picks


def resolve_attachments(job: Job) -> list[Path]:
    paths = list(job.refs)
    if job.use_previous_scene and job.prev_scene_id:
        prev = OUT_DIR / job.condition / f"{job.prev_scene_id}_r{job.repeat}.png"
        if prev.exists():
            paths.append(prev)
    return paths


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT)).replace("\\", "/")
    except ValueError:
        return str(path)


# --------------------------------------------------------------------------- #
# 실행
# --------------------------------------------------------------------------- #
def log_call(record: dict[str, Any]) -> None:
    LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    with LOG_PATH.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(record, ensure_ascii=False) + "\n")
        fh.flush()


def run_job(job: Job, provider, cfg: dict[str, Any]) -> bool:
    max_retries = int(cfg["retry"]["max_retries"])
    backoff = float(cfg["retry"]["backoff_sec"])
    cost_usd = float(cfg["provider"]["cost_per_image_usd"])
    krw = float(cfg["pricing"]["usd_to_krw"])

    job.attachments = resolve_attachments(job)
    if job.use_previous_scene and job.prev_scene_id and len(job.attachments) == len(job.refs):
        print(f"    ! 직전 장면 이미지({job.prev_scene_id}_r{job.repeat}.png)가 없어 레퍼런스만 첨부합니다.")

    for attempt in range(1, max_retries + 2):
        started = time.time()
        ok, err, meta = False, None, {}
        try:
            result = provider.generate(GenRequest(prompt=job.prompt, images=job.attachments))
            job.out_path.parent.mkdir(parents=True, exist_ok=True)
            job.out_path.write_bytes(result.image_bytes)
            ok, meta = True, result.meta
        except ProviderError as exc:
            err = str(exc)
            retryable = exc.retryable
        except Exception as exc:  # provider 구현 밖의 예기치 못한 오류
            err = f"{type(exc).__name__}: {exc}"
            retryable = True

        elapsed = round(time.time() - started, 2)
        log_call(
            {
                "timestamp": datetime.now().isoformat(timespec="seconds"),
                "condition": job.condition,
                "scene_id": job.scene_id,
                "repeat": job.repeat,
                "attempt": attempt,
                "prompt": job.prompt,
                "attachments": [rel(p) for p in job.attachments],
                "provider": provider.name,
                "model": provider.model,
                "duration_sec": elapsed,
                "ok": ok,
                "error": err,
                "est_cost_usd": round(cost_usd, 6),
                "est_cost_krw": round(cost_usd * krw),
                "output_path": rel(job.out_path) if ok else None,
                "provider_meta": meta or None,
            }
        )

        if ok:
            print(f"    OK ({elapsed}s) -> {rel(job.out_path)}")
            return True

        print(f"    실패 (시도 {attempt}/{max_retries + 1}, {elapsed}s): {err}")
        if not retryable:
            print("    재시도해도 소용없는 오류라 건너뜁니다.")
            return False
        if attempt <= max_retries:
            wait = backoff * (2 ** (attempt - 1))
            print(f"    {wait:.0f}초 후 재시도...")
            time.sleep(wait)
    return False


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def parse_args(known_conditions: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="이미지 생성 모델 캐릭터 일관성 비교 하네스")
    p.add_argument("--condition", "-c", action="append", default=[],
                   help="실행할 조건 (여러 번 지정 가능, 쉼표 구분도 허용)")
    p.add_argument("--all", action="store_true", help="config 의 모든 조건 실행")
    p.add_argument("--dry-run", action="store_true", help="API 호출 없이 최종 프롬프트만 출력")
    p.add_argument("--skip-existing", action="store_true", help="이미 있는 출력 파일은 건너뜀")
    p.add_argument("--report-only", action="store_true",
                   help="생성 없이 compare.html / score_sheet.csv 만 다시 만듦")
    p.add_argument("--style-test", action="store_true",
                   help="그림체 비교 실행 (config.yaml 의 style_test 섹션. 조건 지정과 무관)")
    p.add_argument("--style-report", action="store_true",
                   help="생성 없이 style_compare.html 만 다시 만듦")
    p.add_argument("--style-pick", action="append", default=[], metavar="SPEC",
                   help='괜찮은 장 기록. 예: --style-pick "f1 r2 r4 r6" '
                        '--style-pick "f3 # 존나 별로임" (여러 번 지정 가능)')
    p.add_argument("--yes", "-y", action="store_true", help="확인 프롬프트 건너뛰기")
    p.add_argument("--config", default=str(ROOT / "config.yaml"), help="설정 파일 경로")
    return p.parse_args()


def select_conditions(args: argparse.Namespace, cfg: dict[str, Any]) -> list[str]:
    available = list(cfg["conditions"].keys())
    if args.all or args.report_only:
        return available
    picked: list[str] = []
    for item in args.condition:
        picked.extend(x.strip() for x in str(item).split(",") if x.strip())
    if not picked:
        die("--condition A 또는 --all 중 하나는 필요합니다. "
            f"사용 가능한 조건: {', '.join(available)}")
    unknown = [c for c in picked if c not in cfg["conditions"]]
    if unknown:
        die(f"config.yaml 에 없는 조건: {', '.join(unknown)} (사용 가능: {', '.join(available)})")
    seen: list[str] = []
    for c in picked:
        if c not in seen:
            seen.append(c)
    return seen


def check_refs(cfg: dict[str, Any], conditions: list[str], fatal: bool) -> list[str]:
    missing: list[str] = []
    for cname in conditions:
        for r in cfg["conditions"][cname].get("refs") or []:
            if not (ROOT / r).exists() and r not in missing:
                missing.append(r)
    if missing:
        lines = "\n".join(f"  - {m}" for m in missing)
        if fatal:
            die(f"레퍼런스 이미지가 없습니다. refs/ 에 넣고 다시 실행하세요:\n{lines}")
        print(f"[경고] 레퍼런스 이미지 없음 (dry-run 이라 계속 진행):\n{lines}\n")
    return missing


def print_dry_run(jobs: list[Job]) -> None:
    for i, job in enumerate(jobs, 1):
        atts = resolve_attachments(job)
        planned = ""
        if job.use_previous_scene and job.prev_scene_id and len(atts) == len(job.refs):
            planned = f"  (+ 실행 시 {job.condition}/{job.prev_scene_id}_r{job.repeat}.png 첨부 예정)"
        print("=" * 78)
        print(f"[{i}/{len(jobs)}] condition={job.condition}  scene={job.scene_id}  repeat={job.repeat}")
        print(f"출력: {rel(job.out_path)}")
        print(f"첨부: {', '.join(rel(a) for a in atts) if atts else '(없음)'}{planned}")
        print("-" * 78)
        print(job.prompt)
        print()


def cost_of(n_calls: int, cfg: dict[str, Any]) -> tuple[float, float]:
    cost_usd = n_calls * float(cfg["provider"]["cost_per_image_usd"])
    return cost_usd, cost_usd * float(cfg["pricing"]["usd_to_krw"])


def enforce_limit(jobs: list[Job], cfg: dict[str, Any]) -> None:
    limit = int(cfg["limits"]["max_total_calls"])
    if len(jobs) > limit:
        cost_usd, krw = cost_of(len(jobs), cfg)
        die(
            f"총 호출 {len(jobs)}회가 상한 {limit}회를 넘었습니다 "
            f"(예상 비용 {krw:,.0f}원 / 약 ${cost_usd:,.2f}).\n"
            f"        config.yaml 의 limits.max_total_calls 를 올리거나 "
            f"repeats / scenes / conditions 를 줄이세요."
        )


def ensure_api_key(cfg: dict[str, Any], provider) -> None:
    if not provider.requires_api_key():
        return
    env = load_dotenv(ROOT / ".env")
    key_name = str(cfg["provider"].get("api_key_env") or "")
    if not key_name:
        die("config.yaml 의 provider.api_key_env 가 비어 있습니다.")
    api_key = env.get(key_name)
    if not api_key:
        die(f"{key_name} 가 없습니다. .env 파일에 '{key_name}=...' 를 넣어주세요 (.env.example 참고).")
    provider.api_key = api_key


def execute_jobs(jobs: list[Job], provider, cfg: dict[str, Any]) -> list[str]:
    started = time.time()
    ok_count = 0
    fail: list[str] = []
    for i, job in enumerate(jobs, 1):
        print(f"[{i}/{len(jobs)}] {job.condition} / {job.scene_id} / r{job.repeat}")
        if run_job(job, provider, cfg):
            ok_count += 1
        else:
            fail.append(f"{job.condition}/{job.scene_id}_r{job.repeat}")

    total = round(time.time() - started, 1)
    print("\n" + "=" * 78)
    print(f"완료: 성공 {ok_count} / 실패 {len(fail)} (총 {total}s)")
    if fail:
        print("실패 목록: " + ", ".join(fail))
        print("→ 같은 명령에 --skip-existing 을 붙이면 실패한 건만 다시 시도합니다.")
    return fail


def confirm(n_calls: int, cfg: dict[str, Any], provider_desc: str, auto_yes: bool) -> None:
    cost_usd, krw = cost_of(n_calls, cfg)
    print(f"\n모델: {provider_desc}")
    print(f"총 {n_calls}회 호출, 예상 비용 {krw:,.0f}원 (약 ${cost_usd:,.2f}). "
          f"재시도가 발생하면 그만큼 늘어납니다.")
    if auto_yes:
        print("진행할까요? -> --yes 로 자동 승인됨\n")
        return
    if not sys.stdin.isatty():
        die("확인 입력을 받을 수 없습니다 (비대화형). 확인을 건너뛰려면 --yes 를 주세요.")
    answer = input("진행할까요? [y/N] ").strip().lower()
    if answer not in ("y", "yes"):
        print("취소했습니다.")
        raise SystemExit(0)
    print()


# --------------------------------------------------------------------------- #
def run_style_mode(args: argparse.Namespace, cfg: dict[str, Any], provider) -> int:
    """--style-test / --style-report. 레퍼런스도 조건도 쓰지 않는 별도 축."""
    st = load_style_test(cfg)
    styles = st["styles"]
    repeats = int(st["repeats"])
    scene = str(st["scene"]).strip()

    if args.style_pick:
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        picks = apply_style_picks(styles, load_picks(OUT_DIR), args.style_pick)
        picks_path = save_picks(OUT_DIR, picks)
        report_path = build_style_report(OUT_DIR, styles, repeats, scene, provider.describe())
        print(picks_shorthand(styles, picks))
        for style in styles:
            note = (picks.get(str(style["id"])) or {}).get("note")
            if note:
                print(f"  {str(style['id']).split('_')[0]}: {note}")
        print(f"\nstyle_picks.json   -> {rel(picks_path)}")
        print(f"style_compare.html -> {rel(report_path)}")
        return 0

    if args.style_report:
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        path = build_style_report(OUT_DIR, styles, repeats, scene, provider.describe())
        print(f"style_compare.html -> {rel(path)}")
        return 0

    jobs = build_style_jobs(cfg, st)

    if args.skip_existing:
        before = len(jobs)
        jobs = [j for j in jobs if not j.out_path.exists()]
        if before != len(jobs):
            print(f"[skip-existing] 이미 있는 {before - len(jobs)}건 제외\n")

    if args.dry_run:
        print_dry_run(jobs)
        cost_usd, krw = cost_of(len(jobs), cfg)
        limit = int(cfg["limits"]["max_total_calls"])
        print("=" * 78)
        print(f"[dry-run] 그림체 {len(styles)}종 / 반복 {repeats} / 레퍼런스 없음")
        print(f"[dry-run] 고정 장면: {scene}")
        print(f"[dry-run] 총 {len(jobs)}회 호출 예정, 예상 비용 {krw:,.0f}원 (약 ${cost_usd:,.2f}) "
              f"- 상한 {limit}회")
        if len(jobs) > limit:
            print("[dry-run] 상한 초과 상태입니다. 실제 실행은 시작 전에 중단됩니다.")
        print("[dry-run] API 호출은 하지 않았습니다.")
        return 0

    if not jobs:
        print("실행할 작업이 없습니다.")
        return 0

    enforce_limit(jobs, cfg)
    ensure_api_key(cfg, provider)
    confirm(len(jobs), cfg, provider.describe(), args.yes)

    fail = execute_jobs(jobs, provider, cfg)
    path = build_style_report(OUT_DIR, styles, repeats, scene, provider.describe())
    print(f"\nlog.jsonl          -> {rel(LOG_PATH)}")
    print(f"style_compare.html -> {rel(path)}")
    return 1 if fail else 0


def main() -> int:
    args = parse_args()
    cfg = load_config(Path(args.config))

    if args.style_test or args.style_report or args.style_pick:
        prov = cfg["provider"]
        provider = build_provider(
            name=str(prov["name"]), model=str(prov["model"]), api_key=None,
            options=dict(prov.get("options") or {}),
        )
        return run_style_mode(args, cfg, provider)

    conditions = select_conditions(args, cfg)
    repeats = int(cfg["repeats"])
    scenes = cfg["scenes"]
    labels = {c: str(cfg["conditions"][c].get("label") or "") for c in cfg["conditions"]}
    all_refs = [r for c in cfg["conditions"].values() for r in (c.get("refs") or [])]
    all_refs = list(dict.fromkeys(all_refs))

    provider_cfg = cfg["provider"]
    provider = build_provider(
        name=str(provider_cfg["name"]),
        model=str(provider_cfg["model"]),
        api_key=None,
        options=dict(provider_cfg.get("options") or {}),
    )

    # ---- 리포트만 다시 만들기 -------------------------------------------- #
    if args.report_only:
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        html_path, csv_path = build_reports(
            OUT_DIR, conditions, labels, scenes, repeats, provider.describe(), all_refs
        )
        print(f"compare.html    -> {rel(html_path)}")
        print(f"score_sheet.csv -> {rel(csv_path)}")
        return 0

    check_refs(cfg, conditions, fatal=not args.dry_run)
    jobs = build_jobs(cfg, conditions)

    if args.skip_existing:
        before = len(jobs)
        jobs = [j for j in jobs if not j.out_path.exists()]
        if before != len(jobs):
            print(f"[skip-existing] 이미 있는 {before - len(jobs)}건 제외\n")

    # ---- dry-run ---------------------------------------------------------- #
    if args.dry_run:
        print_dry_run(jobs)
        cost_usd, krw = cost_of(len(jobs), cfg)
        limit = int(cfg["limits"]["max_total_calls"])
        print("=" * 78)
        print(f"[dry-run] 조건 {', '.join(conditions)} / 장면 {len(scenes)} / 반복 {repeats}")
        print(f"[dry-run] 총 {len(jobs)}회 호출 예정, 예상 비용 {krw:,.0f}원 (약 ${cost_usd:,.2f}) "
              f"- 상한 {limit}회")
        if len(jobs) > limit:
            print("[dry-run] 상한 초과 상태입니다. 실제 실행은 시작 전에 중단됩니다.")
        print("[dry-run] API 호출은 하지 않았습니다.")
        return 0

    if not jobs:
        print("실행할 작업이 없습니다.")
        return 0

    enforce_limit(jobs, cfg)
    ensure_api_key(cfg, provider)
    confirm(len(jobs), cfg, provider.describe(), args.yes)

    fail = execute_jobs(jobs, provider, cfg)

    html_path, csv_path = build_reports(
        OUT_DIR, list(cfg["conditions"].keys()), labels, scenes, repeats, provider.describe(), all_refs
    )
    print(f"\nlog.jsonl       -> {rel(LOG_PATH)}")
    print(f"compare.html    -> {rel(html_path)}")
    print(f"score_sheet.csv -> {rel(csv_path)}")
    return 1 if fail else 0


if __name__ == "__main__":
    raise SystemExit(main())
