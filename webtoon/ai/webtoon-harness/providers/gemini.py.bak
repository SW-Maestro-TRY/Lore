"""Google Gemini (generateContent) 이미지 생성 provider.

레퍼런스 이미지는 inline_data 파트로 여러 장 동시 첨부한다 (조건 B/C/D).
모델 예: gemini-3-pro-image-preview, gemini-2.5-flash-image
"""

from __future__ import annotations

import base64
from typing import Any

import requests

from .base import GenRequest, GenResult, ImageProvider, ProviderError, guess_mime

ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

# 재시도해도 소용없는 상태코드
FATAL_STATUS = {400, 401, 403, 404}


class GeminiProvider(ImageProvider):
    name = "gemini"

    def _generation_config(self) -> dict[str, Any]:
        opt = self.options
        cfg: dict[str, Any] = {}
        if opt.get("response_modalities"):
            cfg["responseModalities"] = list(opt["response_modalities"])
        image_cfg: dict[str, Any] = {}
        if opt.get("aspect_ratio"):
            image_cfg["aspectRatio"] = opt["aspect_ratio"]
        if opt.get("image_size"):
            image_cfg["imageSize"] = opt["image_size"]
        if image_cfg:
            cfg["imageConfig"] = image_cfg
        return cfg

    def generate(self, req: GenRequest) -> GenResult:
        parts: list[dict[str, Any]] = [{"text": req.prompt}]
        for path in req.images:
            parts.append(
                {
                    "inline_data": {
                        "mime_type": guess_mime(path),
                        "data": base64.b64encode(path.read_bytes()).decode("ascii"),
                    }
                }
            )

        body: dict[str, Any] = {"contents": [{"role": "user", "parts": parts}]}
        gen_cfg = self._generation_config()
        if gen_cfg:
            body["generationConfig"] = gen_cfg

        url = ENDPOINT.format(model=self.model)
        timeout = float(self.options.get("timeout_sec", 300))

        try:
            resp = requests.post(
                url,
                headers={
                    "x-goog-api-key": self.api_key or "",
                    "Content-Type": "application/json",
                },
                json=body,
                timeout=timeout,
            )
        except requests.RequestException as exc:
            raise ProviderError(f"network error: {exc}", retryable=True) from exc

        if resp.status_code != 200:
            snippet = resp.text[:600].replace("\n", " ")
            raise ProviderError(
                f"HTTP {resp.status_code}: {snippet}",
                retryable=resp.status_code not in FATAL_STATUS,
            )

        try:
            data = resp.json()
        except ValueError as exc:
            raise ProviderError(f"invalid JSON response: {resp.text[:300]}") from exc

        return self._extract_image(data)

    @staticmethod
    def _extract_image(data: dict[str, Any]) -> GenResult:
        candidates = data.get("candidates") or []
        if not candidates:
            reason = (data.get("promptFeedback") or {}).get("blockReason", "no candidates")
            raise ProviderError(f"no image returned ({reason})", retryable=False)

        cand = candidates[0]
        finish = cand.get("finishReason")
        texts: list[str] = []
        for part in (cand.get("content") or {}).get("parts") or []:
            # 요청은 snake_case, 응답은 camelCase 로 오는 경우가 섞여 있어 둘 다 본다.
            inline = part.get("inlineData") or part.get("inline_data")
            if inline and inline.get("data"):
                return GenResult(
                    image_bytes=base64.b64decode(inline["data"]),
                    mime_type=inline.get("mimeType") or inline.get("mime_type") or "image/png",
                    meta={
                        "finish_reason": finish,
                        "usage": data.get("usageMetadata"),
                        "text": " ".join(texts)[:500] or None,
                    },
                )
            if part.get("text"):
                texts.append(part["text"])

        detail = " ".join(texts)[:300] or "(no text)"
        raise ProviderError(
            f"response had no image part (finishReason={finish}): {detail}",
            retryable=finish not in {"PROHIBITED_CONTENT", "SAFETY", "IMAGE_SAFETY"},
        )
