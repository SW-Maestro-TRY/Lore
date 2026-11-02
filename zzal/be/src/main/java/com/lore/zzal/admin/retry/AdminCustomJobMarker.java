package com.lore.zzal.admin.retry;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PENDING 맞춤 잡에 러너 요청이 열려 있는 동안 서버 디스패처가 그 잡을 집지 않게 하는 표식(수정 6).
 *
 * ★ 디스패처는 {@code state='PENDING' AND claim_token IS NULL AND error_code IS NULL} 만 집는다
 *   (CustomBundleDispatcher). 그래서 claim_token 칸에 {@code admin-retry-{요청 id}} 를 적어 두면 건너뛴다 —
 *   state 는 바꾸지 않는다(결정 1 예외 범위 안). 요청이 반영 없이 닫히면(취소 · 결과 없음 · 반영 실패) 지운다.
 * ★ 조건부라 남의 claim_token(진짜 디스패치)은 건드리지 않는다.
 */
final class AdminCustomJobMarker {

    static final String PREFIX = "admin-retry-";

    private AdminCustomJobMarker() {
    }

    static String of(long retryId) {
        return PREFIX + retryId;
    }

    static boolean isMarker(String claimToken) {
        return claimToken != null && claimToken.startsWith(PREFIX);
    }

    /** PENDING 이고 비어 있을 때만 적는다. @return 적었으면 true */
    static boolean mark(JdbcTemplate db, long jobId, long retryId) {
        return db.update("UPDATE zzal_custom_bundle_job SET claim_token = ? "
                + "WHERE id = ? AND state = 'PENDING' AND claim_token IS NULL", of(retryId), jobId) == 1;
    }

    /** 이 요청이 적은 표식만 지운다(PENDING 일 때). */
    static void release(JdbcTemplate db, long jobId, long retryId) {
        db.update("UPDATE zzal_custom_bundle_job SET claim_token = NULL "
                + "WHERE id = ? AND state = 'PENDING' AND claim_token = ?", jobId, of(retryId));
    }
}
