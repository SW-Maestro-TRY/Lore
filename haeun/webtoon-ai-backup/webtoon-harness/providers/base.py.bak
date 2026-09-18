"""이미지 생성 provider 공통 인터페이스.

새 provider 를 붙이려면:
  1) 이 파일의 ImageProvider 를 상속해 generate() 를 구현
  2) providers/__init__.py 의 REGISTRY 에 이름을 등록
  3) config.yaml 의 provider.name 을 그 이름으로 변경
run.py 는 이 인터페이스 밖의 것을 알지 못한다.
"""

from __future__ import annotations

import mimetypes
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


class ProviderError(RuntimeError):
    """생성 실패. 재시도 가능한 실패는 retryable=True."""

    def __init__(self, message: str, retryable: bool = True):
        super().__init__(message)
        self.retryable = retryable


@dataclass
class GenRequest:
    prompt: str
    images: list[Path] = field(default_factory=list)  # 첨부 레퍼런스 (순서 유지)


@dataclass
class GenResult:
    image_bytes: bytes
    mime_type: str = "image/png"
    meta: dict[str, Any] = field(default_factory=dict)  # 로그에 남길 부가 정보


def guess_mime(path: Path) -> str:
    mime, _ = mimetypes.guess_type(str(path))
    return mime or "image/png"


class ImageProvider(ABC):
    """이미지 생성 API 어댑터."""

    name: str = "base"

    def __init__(self, model: str, api_key: str | None, options: dict[str, Any] | None = None):
        self.model = model
        self.api_key = api_key
        self.options = options or {}

    @abstractmethod
    def generate(self, req: GenRequest) -> GenResult:
        """이미지 1장 생성. 실패 시 ProviderError."""

    def requires_api_key(self) -> bool:
        return True

    def describe(self) -> str:
        return f"{self.name}:{self.model}"
