package com.lore.zzal.motion;

/**
 * 카탈로그의 동작 하나(정본 13장 + api-v2.md 3절).
 *
 * @param seq        13장 번호. 1~16 은 기본 행동, 101·102 는 선물. 3층 심화 순서도 이 번호 순이다
 * @param key        화면·설정·S3 키에 쓰는 이름(영문). {@code images/zzal/pets/{id}/basic/{key}.webp}
 * @param label      화면에 보일 이름
 * @param layer      어느 층
 * @param unlockRule 기본 행동이 열리는 조건
 * @param legacyFile v1 부화(8상태) 파일명. v1 로 부화한 펫의 basicImageKey 를 이걸로 채운다. 없으면 null
 * @param promptFile 16프레임 지시문 파일명(한글, 띄어쓰기 없음) — {@code zzal/prompt/{버전}/motions/{promptFile}.txt}.
 *                   생성 세션이 이 이름으로 만든다(api-v2.md 해석 12)
 */
public record MotionSpec(int seq, String key, String label, MotionLayer layer,
                         UnlockRule unlockRule, String legacyFile, String promptFile) {

    public boolean isGift() {
        return layer == MotionLayer.GIFT;
    }

    /** v1 8상태 파일이 있는가(부화 파이프라인 v1 펫의 폴백). */
    public boolean hasLegacyFile() {
        return legacyFile != null;
    }
}
