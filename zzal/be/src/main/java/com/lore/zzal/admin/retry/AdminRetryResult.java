package com.lore.zzal.admin.retry;

/**
 * 재시도 요청의 결과. null 이면 아직 결과 없음(러너가 안 집었거나 굽는 중).
 *
 * <ul>
 *   <li>{@link #CANDIDATES} — 러너가 후보를 올렸다. 사람이 고르기 전까지는 열린 요청이다.</li>
 *   <li>{@link #NONE} — 러너가 돌았지만 쓸 후보가 없다.</li>
 *   <li>{@link #CANCELLED} — 관리자가 취소했다. 늦게 온 러너 결과는 거부한다.</li>
 *   <li>{@link #APPLIED} — 반영됨(사람이 골랐거나 자동 반영, 또는 다른 길로 복구됨).</li>
 *   <li>{@link #APPLY_FAILED} — 반영하다 실패했다(사유는 note).</li>
 * </ul>
 */
public enum AdminRetryResult {
    CANDIDATES,
    NONE,
    CANCELLED,
    APPLIED,
    APPLY_FAILED
}
