package com.lore.zzal.generation;

import java.time.Instant;

/**
 * 부화가 끝내 실패로 닫힌 순간을 듣는 쪽(관리자 자동 CLI 요청, 결정 8).
 *
 * ★ generation 패키지가 admin 에 기대지 않게 이음매를 여기 둔다. 빈이 없으면(관리자 스위치 꺼짐) 아무 일도 없다.
 * ★ 실패 기록이 먼저다 — 듣는 쪽은 실패가 <b>커밋된 뒤</b> 불리고, 거기서 난 예외는 부화 실패 기록을 되돌리지 않는다.
 */
public interface HatchFailureListener {

    void onHatchFinallyFailed(Long petId, Instant now);
}
