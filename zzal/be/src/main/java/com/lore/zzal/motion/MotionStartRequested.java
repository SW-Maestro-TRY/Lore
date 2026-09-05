package com.lore.zzal.motion;

/**
 * 재웠으니 다음 동작을 굽기 시작하라는 알림.
 *
 * ★ 재우기 트랜잭션이 <b>커밋된 뒤에</b> 굽기를 시작하기 위한 것이다. 커밋 전에 시작하면
 *   굽는 쪽이 아직 저장 안 된 모션을 찾다가 못 찾는다(부화도 같은 구조를 쓴다).
 */
public record MotionStartRequested(Long motionId) {
}
