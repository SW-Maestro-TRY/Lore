package com.lore.zzal.generation;

/**
 * "이 펫의 부화를 시작해 달라" 는 알림.
 *
 * 곧바로 부르지 않고 알림을 띄우는 이유 — 펫을 저장하는 트랜잭션이 아직 커밋되기 전이라,
 * 그 안에서 다른 스레드에게 일을 시키면 그 스레드가 DB 에서 펫을 못 찾을 수 있다.
 */
public record PetHatchRequested(Long jobId, Long petId, String version) {
}
