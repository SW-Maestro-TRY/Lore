package com.lore.zzal.generation;

/**
 * 그림이 등록됐다 — <b>캐릭터 시트만</b> 구워 달라.
 *
 * ★ 부화 전체가 아니라 첫 단계 하나다. 사용자가 이름을 짓는 동안 미리 굽는 것이라,
 *   이름이 필요한 단계(정체성 문단·격자)는 아직 돌 수 없다.
 */
public record PetSheetRequested(Long jobId, Long petId, String version) {
}
