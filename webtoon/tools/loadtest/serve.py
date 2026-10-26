#!/usr/bin/env python3
"""부하 시험용 서버를 띄운다 — 배포와 같은 jar · 같은 힙(-Xms256m -Xmx768m) · 가짜 하네스.

    ./gradlew bootJar                              # 저장소 루트에서 먼저
    python3 webtoon/tools/loadtest/serve.py        # 앞에서 돈다. Ctrl-C 로 끈다

bootRun 이 아니라 jar 로 띄우는 이유: 배포 서버가 jar 로 돌고(하네스도 jar 에서 임시
폴더로 풀린다), 앱 JVM 에 -Xmx 를 정확히 걸 수 있다(bootRun 은 gradle 과 JVM 이
둘이라 힙을 따로 못 건다).

환경은 bootRun 과 같은 규칙으로 루트 .env 를 읽고(셸에 있는 값이 이긴다) 그 위에
부하 시험 값을 얹는다. **모델·메일·푸시 열쇠는 비운다** — 가짜 하네스가 부르지
않더라도 서버 쪽(입력 글 검사 등)이 부르지 못하게.

    LT_DB        사본 DB 이름 (기본 lore_lt). 원본 lore 는 쓰지 않는다(StaleJobs)
    LT_PORT      기본 8097
    LT_OUT       runs·jobs·로그를 쌓을 폴더 (기본 이 파일 옆 results/server)
    LT_JVM_CPUS  -XX:ActiveProcessorCount (기본 2, 0 이면 안 건다)
    LT_TASKPOLICY_BG=1  taskpolicy -b 로 띄운다(효율 코어만)
    LT_WORKERS · LT_PAGE_WORKERS   lore.webtoon.workers · page-workers (안 주면 코드 기본 2·3)
    FAKE_*       fakepy 로 그대로 넘어간다
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
JAR = ROOT / "build" / "libs" / "lore-0.0.1-SNAPSHOT.jar"


def read_dotenv(path: Path) -> dict[str, str]:
    """build.gradle 의 bootRun 과 같은 규칙."""
    out: dict[str, str] = {}
    if not path.exists():
        return out
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        k, v = k.strip(), v.strip()
        if len(v) >= 2 and v[0] == v[-1] and v[0] in "\"'":
            v = v[1:-1]
        out[k] = v
    return out


BLANK = ("WEBTOON_API_KEY", "OPENAI_KEY", "OPENAI_API_KEY", "ZZAL_OPENAI_API_KEY",
         "OPENROUTER_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY", "LOGFIRE_TOKEN",
         "MAIL_USERNAME", "MAIL_PASSWORD",
         "LORE_WEBTOON_PUSH_PUBLIC_KEY", "LORE_WEBTOON_PUSH_PRIVATE_KEY")


def main() -> None:
    if not JAR.is_file():
        raise SystemExit(f"{JAR} 가 없습니다 — 먼저 ./gradlew bootJar")
    db = os.environ.get("LT_DB", "lore_lt")
    if db == "lore":
        raise SystemExit("원본 DB(lore)로는 안 띄웁니다 — StaleJobs 가 남의 작업을 끊습니다")
    out = Path(os.environ.get("LT_OUT", HERE / "results" / "server")).resolve()
    (out / "runs").mkdir(parents=True, exist_ok=True)
    (out / "jobs").mkdir(parents=True, exist_ok=True)

    env = dict(os.environ)
    for k, v in read_dotenv(ROOT / ".env").items():
        env.setdefault(k, v)
    env.update({
        "DB_URL": f"jdbc:postgresql://localhost:5432/{db}",
        "DB_USERNAME": os.environ.get("DB_USERNAME", "mint"),
        "SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS": "*:missing,*:future",
        "SERVER_PORT": os.environ.get("LT_PORT", "8097"),
        "LORE_WEBTOON_EXAMPLE_SEED_WORKS": "false",
        "LORE_WEBTOON_PYTHON_BIN": str(HERE / "fakepy"),
        "LORE_WEBTOON_PYTHON_RUNS_DIR": str(out / "runs"),
        "LORE_WEBTOON_PYTHON_JOBS_DIR": str(out / "jobs"),
        "LORE_WEBTOON_SPEND_DAILY_RUNS": "1000000",
        "LORE_WEBTOON_SPEND_DAILY_KRW": "1000000000",
        "LORE_WEBTOON_SPEND_GUEST_FREE": "1000000",
        "LORE_WEBTOON_SAFETY_ENABLED": "false",
        "ZZAL_GENERATION_REAL": "false",
        "FAKE_LOG": os.environ.get("FAKE_LOG", str(out / "fake-steps.jsonl")),
    })
    for k in BLANK:
        env[k] = ""
    if os.environ.get("LT_WORKERS"):
        env["LORE_WEBTOON_WORKERS"] = os.environ["LT_WORKERS"]
    if os.environ.get("LT_PAGE_WORKERS"):
        env["LORE_WEBTOON_PAGE_WORKERS"] = os.environ["LT_PAGE_WORKERS"]

    java = os.environ.get("JAVA", "java")
    cmd = [java, "-Xms256m", "-Xmx768m"]
    cpus = os.environ.get("LT_JVM_CPUS", "2")       # 운영(t3.medium)은 vCPU 2 — JVM 이 그렇게 알게
    if cpus and cpus != "0":
        cmd.append(f"-XX:ActiveProcessorCount={cpus}")
    cmd += ["-jar", str(JAR)]
    if os.environ.get("LT_TASKPOLICY_BG") == "1":   # macOS: 효율 코어로만(느린 기계 흉내, 개수 제한은 아님)
        cmd = ["taskpolicy", "-b"] + cmd
        java = "taskpolicy"
    print("띄웁니다:", " ".join(cmd), f"(db={db}, port={env['SERVER_PORT']}, out={out})",
          file=sys.stderr, flush=True)
    os.chdir(ROOT)
    os.execvpe(java, cmd, env)


if __name__ == "__main__":
    main()
