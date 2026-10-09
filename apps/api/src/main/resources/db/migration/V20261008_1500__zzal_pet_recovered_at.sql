-- 관리자 복구 시각(#702).
--
-- ★ 왜 — 관리자 1층 고르기(부화 실패 알 살리기)가 hatch_started_at·hatched_at 을 복구한 순간으로 덮어써
--   부화 소요 시간·부화일 통계가 복구한 날로 튀었다. 이제 두 시각은 원래 값 그대로 두고(hatched_at 이 비어 있을
--   때만 채운다) 복구한 시각은 이 칸에 따로 남긴다.
--
-- ★ 칸 추가만 한다(nullable, 기본값 없음). 기존 행은 null = 복구한 적 없음.
ALTER TABLE zzal_pet ADD COLUMN recovered_at timestamp(6) with time zone;
