-- 모델 호출마다 프롬프트 캐시로 처리된 입력 토큰 수(#832). 캐시된 입력은 값이 깎이므로
-- OpenAI 직접 · OpenRouter 를 바꿀 때 캐시가 유지되는지 이 칸으로 본다. 옛 줄은 0(모름).
ALTER TABLE webtoon_usage ADD COLUMN IF NOT EXISTS cached_tokens BIGINT NOT NULL DEFAULT 0;
