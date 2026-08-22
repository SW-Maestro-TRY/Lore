"""랜딩페이지 서버. 표준 라이브러리만 쓴다 (설치할 것 없음).

    python serve.py            # http://127.0.0.1:8800
    python serve.py --port 9000 --open

브라우저는 상태를 **폴링**한다. SSE 나 웹소켓이 아니라 0.8초마다 상태 JSON 을
받아 간다. 한 편 뽑는 데 10분이 걸리는 일이라 0.8초 지연은 보이지 않고,
연결이 끊겨도(노트북을 덮었다 열어도) 다음 폴링에서 그대로 이어진다 —
스트리밍이었다면 끊긴 자리를 복구해야 한다.
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import mimetypes
import re
import sys
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs, quote

import pipeline

HERE = Path(__file__).resolve().parent
WEB = HERE / "web"
MAX_PHOTO_BYTES = 6 * 1024 * 1024

runner = pipeline.Runner()
_thumb_lock = threading.Lock()


def thumbnail(src: Path, dest: Path, width: int) -> Path:
    """웹으로 내려보낼 크기로 줄여 둔다.

    원본 컷은 2752x1536 짜리 PNG 다. 12장이면 30MB 가 넘어서 그대로 내려보내면
    결과 화면이 열리는 데만 한참 걸린다. 줄인 것은 job 폴더에 캐시한다.
    """
    if dest.exists() and dest.stat().st_mtime >= src.stat().st_mtime:
        return dest
    with _thumb_lock:
        from PIL import Image
        im = Image.open(src)
        im.load()
        if im.width > width:
            h = round(im.height * width / im.width)
            im = im.resize((width, h), Image.LANCZOS)
        dest.parent.mkdir(parents=True, exist_ok=True)
        im.convert("RGB").save(dest, "JPEG", quality=88, optimize=True)
    return dest


class Handler(BaseHTTPRequestHandler):
    server_version = "lore-landing"

    # 요청 한 줄씩 찍히면 폴링 때문에 콘솔이 못 쓰게 된다.
    def log_message(self, fmt, *args):  # noqa: A003
        pass

    # ---- 보내기 ------------------------------------------------------------ #

    def _send(self, code: int, body: bytes, ctype: str,
              headers: dict[str, str] | None = None) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionAbortedError):
            pass

    def _json(self, obj, code: int = 200) -> None:
        self._send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                   "application/json; charset=utf-8")

    def _error(self, code: int, message: str) -> None:
        self._json({"error": message}, code)

    def _file(self, path: Path, download: str = "") -> None:
        if not path.exists() or not path.is_file():
            return self._error(404, "없습니다")
        ctype = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype.endswith("javascript"):
            ctype += "; charset=utf-8"
        headers = {}
        if download:
            # HTTP 헤더는 latin-1 만 담을 수 있어서 파일 이름에 한글이 들어가면
            # send_header 가 터진다. 옛 브라우저용 ASCII 이름과 RFC 5987 의
            # filename* 을 같이 보낸다 — 요즘 브라우저는 뒤엣것을 쓴다.
            plain = re.sub(r"[^A-Za-z0-9._-]+", "_", download).strip("_") or "episode.png"
            headers["Content-Disposition"] = (
                f'attachment; filename="{plain}"; '
                f"filename*=UTF-8''{quote(download, safe='')}")
        self._send(200, path.read_bytes(), ctype, headers)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    # ---- 라우팅 ------------------------------------------------------------ #

    # 요청 하나가 터져도 서버는 계속 돌아야 한다. 브라우저는 0.8초마다
    # 물어보므로, 조용히 끊기면 화면이 멈춘 것처럼 보인다.
    def handle_one_request(self) -> None:
        try:
            super().handle_one_request()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            self.close_connection = True
        except Exception as exc:                                # noqa: BLE001
            print(f"[요청 오류] {self.path} — {type(exc).__name__}: {exc}")
            try:
                self._error(500, f"{type(exc).__name__}: {exc}")
            except Exception:                                   # noqa: BLE001
                pass
            self.close_connection = True

    def do_GET(self) -> None:                                   # noqa: N802
        url = urlparse(self.path)
        path, query = url.path, parse_qs(url.query)

        if path in ("/", "/index.html"):
            return self._file(WEB / "index.html")
        # 편집실 — **목업**. 서버 상태가 필요 없고 web/samples/mock.json 만 읽는다.
        # 한 번도 안 돌려 본 사람도 결과물 화면을 그대로 볼 수 있어야 한다.
        if path in ("/editor", "/editor/", "/editor.html"):
            return self._file(WEB / "editor.html")
        # 이미 만들어 둔 결과물을 바로 여는 자리. 같은 index.html 인데,
        # app.js 가 주소를 보고 폼 대신 결과 화면부터 띄운다.
        if path in ("/result", "/result/"):
            return self._file(WEB / "index.html")
        if path.startswith("/static/"):
            rel = path[len("/static/"):]
            target = (WEB / rel).resolve()
            if WEB.resolve() not in target.parents:
                return self._error(403, "밖입니다")
            return self._file(target)
        if path == "/api/config":
            return self._json({
                "styles": [{"key": k, "label": v} for k, v in pipeline.STYLES.items()],
                "fields": list(pipeline.FIELD_KEYS),
                "condition": pipeline.CONDITION,
                "cuts_per_sheet": pipeline.CUTS_PER_SHEET,
                "worlds": pipeline.world_presets(),
            })

        # 편집기가 아무 run 이나 열 수 있게 하는 두 자리.
        # 작업(Job)을 거치지 않는다 — 하네스를 직접 돌린 run 도 똑같이 열린다.
        if path == "/api/runs":
            return self._json({"runs": pipeline.list_runs()})

        m = re.fullmatch(r"/api/runs/([\w.-]+)/episode", path)
        if m:
            data = pipeline.editor_data(m.group(1))
            if not data:
                return self._error(404, "그 run 의 1화 컷을 찾지 못했습니다")
            return self._json(data)

        m = re.fullmatch(r"/api/runs/([\w.-]+)/cost", path)
        if m:
            return self._json(pipeline.run_cost(m.group(1)))

        # 다시 그리기 — 지난 판 목록과 그 그림.
        m = re.fullmatch(r"/api/runs/([\w.-]+)/scenes/(\d+)/versions", path)
        if m:
            return self._json({"versions": pipeline.scene_versions(
                m.group(1), int(m.group(2)))})

        m = re.fullmatch(r"/api/runs/([\w.-]+)/scenes/(\d+)/versions/(\d+)", path)
        if m:
            src = pipeline.version_path(m.group(1), int(m.group(2)), int(m.group(3)))
            if not src:
                return self._error(404, "그 판본이 없습니다")
            width = max(160, min(1400, int((query.get("w") or ["1080"])[0])))
            dest = (pipeline.episode_dir(m.group(1)) / "cache"
                    / f"v{m.group(2)}_{m.group(3)}_w{width}.jpg")
            try:
                return self._file(thumbnail(src, dest, width))
            except Exception:                                   # noqa: BLE001
                return self._file(src)

        m = re.fullmatch(r"/api/regens/([\w.-]+)", path)
        if m:
            job = pipeline.regens.get(m.group(1))
            if not job:
                return self._error(404, "그런 작업이 없습니다")
            return self._json(job.snapshot())

        m = re.fullmatch(r"/api/runs/([\w.-]+)/page/(\d+)", path)
        if m:
            src = pipeline.unit_image(m.group(1), int(m.group(2)))
            if not src:
                return self._error(404, "그 장의 그림이 없습니다")
            width = max(160, min(1400, int((query.get("w") or ["1080"])[0])))
            dest = (pipeline.WEBTOON / "outputs" / m.group(1) / "ep1" / "cache"
                    / f"page{m.group(2)}_w{width}.jpg")
            try:
                return self._file(thumbnail(src, dest, width))
            except Exception:                                   # noqa: BLE001
                return self._file(src)

        if path == "/api/latest":
            # 가장 최근에 **끝난** 작업. /result 가 이걸 보고 결과를 띄운다.
            done = [j for j in runner.jobs.values() if j.status == "done" and j.run_id]
            if not done:
                return self._error(404, "아직 완성된 작품이 없습니다")
            newest = max(done, key=lambda j: j.finished_at or 0)
            return self._json({"id": newest.id, "run_id": newest.run_id})

        m = re.fullmatch(r"/api/jobs/([\w.-]+)", path)
        if m:
            job = runner.get(m.group(1))
            if not job:
                return self._error(404, "그런 작업이 없습니다")
            state = job.snapshot()
            state["queue_position"] = runner.position(job.id)
            return self._json(state)

        m = re.fullmatch(r"/api/jobs/([\w.-]+)/result", path)
        if m:
            job = runner.get(m.group(1))
            if not job:
                return self._error(404, "그런 작업이 없습니다")
            return self._json(pipeline.result(job))

        # 장(Scene) 하나 = 이미지 하나. 한 장에 컷이 3개 들어 있다.
        m = re.fullmatch(r"/api/jobs/([\w.-]+)/page/(\d+)", path)
        if m:
            job = runner.get(m.group(1))
            if not job or not job.run_id:
                return self._error(404, "아직입니다")
            src = pipeline.unit_image(job.run_id, int(m.group(2)))
            if not src:
                return self._error(404, "아직입니다")
            width = max(160, min(1400, int((query.get("w") or ["1080"])[0])))
            dest = job.dir / "cache" / f"page{m.group(2)}_w{width}.jpg"
            try:
                return self._file(thumbnail(src, dest, width))
            except Exception:                                   # noqa: BLE001
                return self._file(src)

        m = re.fullmatch(r"/api/jobs/([\w.-]+)/sheet", path)
        if m:
            job = runner.get(m.group(1))
            if not job or not job.run_id:
                return self._error(404, "아직입니다")
            src = pipeline.STORY / "runs" / job.run_id / "charsheet" / "sheet_c1.png"
            if not src.exists():
                return self._error(404, "아직입니다")
            dest = job.dir / "cache" / "sheet_w720.jpg"
            try:
                return self._file(thumbnail(src, dest, 720))
            except Exception:                                   # noqa: BLE001
                return self._file(src)

        # 시트 승인 화면에서 원본 참조 사진과 나란히 보여줄 자리.
        m = re.fullmatch(r"/api/jobs/([\w.-]+)/photo", path)
        if m:
            job = runner.get(m.group(1))
            if not job or not job.has_photo:
                return self._error(404, "업로드한 사진이 없습니다")
            return self._file(job.dir / "photo.png")

        m = re.fullmatch(r"/api/jobs/([\w.-]+)/episode\.png", path)
        if m:
            job = runner.get(m.group(1))
            if not job or not job.run_id:
                return self._error(404, "아직입니다")
            src = pipeline.episode_dir(job.run_id) / "episode.png"
            return self._file(src, download=f"webtoon_{job.run_id}_1화.png")

        return self._error(404, "없는 주소입니다")

    def do_POST(self) -> None:                                  # noqa: N802
        url = urlparse(self.path)

        if url.path == "/api/create":
            try:
                form = self._body()
            except (json.JSONDecodeError, UnicodeDecodeError):
                return self._error(400, "입력을 읽지 못했습니다")

            photo = None
            data_url = str(form.pop("photo_data", "") or "")
            if data_url.startswith("data:"):
                try:
                    raw = base64.b64decode(data_url.split(",", 1)[1])
                except (ValueError, IndexError):
                    return self._error(400, "사진을 읽지 못했습니다")
                if len(raw) > MAX_PHOTO_BYTES:
                    return self._error(400, "사진이 너무 큽니다 (6MB 까지)")
                try:
                    from PIL import Image
                    im = Image.open(io.BytesIO(raw))
                    im.load()
                    if im.width > 1400:
                        h = round(im.height * 1400 / im.width)
                        im = im.resize((1400, h), Image.LANCZOS)
                    buf = io.BytesIO()
                    im.convert("RGB").save(buf, "PNG")
                    photo = buf.getvalue()
                except Exception:                               # noqa: BLE001
                    return self._error(400, "사진 형식을 알 수 없습니다")

            # 캐릭터를 알 수 있는 것이 하나는 있어야 한다 — story.py 가 그렇게
            # 요구하고, 그 이유가 맞다. 아무것도 없으면 모델이 인물을 통째로
            # 지어내고 그건 사용자의 캐릭터가 아니다.
            known = any(str(form.get(k) or "").strip()
                        for k in ("name", "character")) or \
                any(str(v or "").strip() for v in (form.get("fields") or {}).values()) \
                or photo is not None
            if not known:
                return self._error(400, "캐릭터를 알 수 있는 것이 하나는 필요합니다 — "
                                        "이름 · 설명 · 항목 · 사진 중 아무거나요.")

            job = runner.create(form, photo)
            return self._json({"id": job.id, "queue_position": runner.position(job.id)})

        # ---- 장(Scene) 다시 그리기 ------------------------------------- #
        #
        # 크레딧 차감은 없다 (#16 이 백로그라 붙일 곳이 없다). 실제 API 비용은
        # 나가므로, 화면이 "몇 크레딧" 이라고 적어 두더라도 그건 목업이다.
        m = re.fullmatch(r"/api/runs/([\w.-]+)/scenes/(\d+)/regen", url.path)
        if m:
            run_id, scene_no = m.group(1), int(m.group(2))
            try:
                body = self._body()
            except (json.JSONDecodeError, UnicodeDecodeError):
                body = {}
            if not pipeline.scene_cut_range(run_id, scene_no):
                return self._error(404, "그 장을 찾지 못했습니다")
            if not pipeline.unit_image(run_id, scene_no):
                return self._error(409, "아직 그려지지 않은 장입니다")
            feedback = str(body.get("feedback") or "").strip()
            if len(feedback) > 500:
                return self._error(400, "요청은 500자까지 적어 주세요")
            job = pipeline.regens.start(run_id, scene_no, feedback,
                                        str(body.get("style") or ""))
            return self._json(job.snapshot())

        m = re.fullmatch(r"/api/regens/([\w.-]+)/cancel", url.path)
        if m:
            job = pipeline.regens.get(m.group(1))
            if not job:
                return self._error(404, "그런 작업이 없습니다")
            job.cancel()
            return self._json({"ok": True})

        # 지난 판으로 되돌리기. 되돌리기 직전 그림도 판본으로 남으므로
        # "되돌린 것을 다시 되돌리기" 도 된다.
        m = re.fullmatch(r"/api/runs/([\w.-]+)/scenes/(\d+)/revert", url.path)
        if m:
            run_id, scene_no = m.group(1), int(m.group(2))
            try:
                body = self._body()
            except (json.JSONDecodeError, UnicodeDecodeError):
                return self._error(400, "입력을 읽지 못했습니다")
            try:
                version = int(body.get("version"))
            except (TypeError, ValueError):
                return self._error(400, "version 이 필요합니다")
            if not pipeline.revert_scene(run_id, scene_no, version):
                return self._error(404, "그 판본으로 되돌리지 못했습니다")
            return self._json({"ok": True,
                               "versions": pipeline.scene_versions(run_id, scene_no)})

        m = re.fullmatch(r"/api/jobs/([\w.-]+)/cancel", url.path)
        if m:
            job = runner.get(m.group(1))
            if not job:
                return self._error(404, "그런 작업이 없습니다")
            job.cancel()
            return self._json({"ok": True})

        # 시트 승인 화면의 "이대로 진행" / "다시 만들기" 버튼.
        m = re.fullmatch(r"/api/jobs/([\w.-]+)/sheet-decision", url.path)
        if m:
            job = runner.get(m.group(1))
            if not job:
                return self._error(404, "그런 작업이 없습니다")
            if job.status != "awaiting_sheet_approval":
                return self._error(409, "지금은 시트 확인 단계가 아닙니다")
            try:
                body = self._body()
            except (json.JSONDecodeError, UnicodeDecodeError):
                return self._error(400, "입력을 읽지 못했습니다")
            decision = str(body.get("decision") or "")
            if decision not in ("approve", "retry"):
                return self._error(400, "decision 은 approve 또는 retry 여야 합니다")
            job.decide_sheet(decision)
            return self._json({"ok": True})

        # 스토리 확인 화면의 "이대로 진행" / "다시 만들기" 버튼 — 스토리 단계
        # 게이트 재시도가 소진돼(STATUS_HUMAN) 사람 확인이 필요할 때만 뜬다.
        m = re.fullmatch(r"/api/jobs/([\w.-]+)/story-decision", url.path)
        if m:
            job = runner.get(m.group(1))
            if not job:
                return self._error(404, "그런 작업이 없습니다")
            if job.status != "awaiting_story_approval":
                return self._error(409, "지금은 스토리 확인 단계가 아닙니다")
            try:
                body = self._body()
            except (json.JSONDecodeError, UnicodeDecodeError):
                return self._error(400, "입력을 읽지 못했습니다")
            decision = str(body.get("decision") or "")
            if decision not in ("approve", "retry"):
                return self._error(400, "decision 은 approve 또는 retry 여야 합니다")
            job.decide_story(decision)
            return self._json({"ok": True})

        # 콘티 확인 화면의 "이대로 진행" / "다시 만들기" 버튼 — 콘티 단계
        # 게이트 재시도가 소진돼(STATUS_HUMAN) 사람 확인이 필요할 때만 뜬다.
        m = re.fullmatch(r"/api/jobs/([\w.-]+)/board-decision", url.path)
        if m:
            job = runner.get(m.group(1))
            if not job:
                return self._error(404, "그런 작업이 없습니다")
            if job.status != "awaiting_board_approval":
                return self._error(409, "지금은 콘티 확인 단계가 아닙니다")
            try:
                body = self._body()
            except (json.JSONDecodeError, UnicodeDecodeError):
                return self._error(400, "입력을 읽지 못했습니다")
            decision = str(body.get("decision") or "")
            if decision not in ("approve", "retry"):
                return self._error(400, "decision 은 approve 또는 retry 여야 합니다")
            job.decide_board(decision)
            return self._json({"ok": True})

        return self._error(404, "없는 주소입니다")


def main() -> int:
    ap = argparse.ArgumentParser(description="웹툰 생성 랜딩페이지 서버")
    ap.add_argument("--port", type=int, default=8800)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--open", action="store_true", help="브라우저를 함께 엽니다")
    args = ap.parse_args()

    pipeline.JOBS_DIR.mkdir(parents=True, exist_ok=True)
    for name, path in (("story-harness", pipeline.STORY),
                       ("webtoon-harness", pipeline.WEBTOON)):
        if not path.exists():
            print(f"[중단] {name} 을 찾지 못했습니다: {path}")
            return 1

    restored = runner.restore()
    if restored:
        print(f"지난 작업 {restored}건을 다시 읽었습니다 (결과 화면은 그대로 열립니다).")

    url = f"http://{args.host}:{args.port}/"
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"랜딩페이지:  {url}")
    print(f"결과물 바로:  {url}result      (이미 만들어 둔 마지막 1화)")
    print(f"편집실(목업): {url}editor      (아무것도 안 돌려도 열립니다)")
    print(f"  그림 조건 {pipeline.CONDITION} · 한 장에 {pipeline.CUTS_PER_SHEET}컷 · "
          f"말풍선과 대사는 그림 안에")
    print("  Ctrl+C 로 종료\n")
    if args.open:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n종료합니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
