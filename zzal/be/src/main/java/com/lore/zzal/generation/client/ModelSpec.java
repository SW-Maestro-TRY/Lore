package com.lore.zzal.generation.client;

/**
 * 어떤 모델을 어떤 설정으로 부를지. 버전 폴더의 models.yml 에서 읽는다.
 *
 * ★ 코드가 아니라 설정에 둔 이유 — 모델과 품질은 계속 바뀔 것이고,
 *   바꿀 때마다 코드를 고치고 배포하면 실험이 느려진다.
 *
 * @param model    모델 이름
 * @param size     이미지 크기 (텍스트면 무시)
 * @param quality  품질. 낮추면 싸고 거칠다 — 개발 중 경로 확인용으로 쓴다
 */
public record ModelSpec(String model, String size, String quality) {

    public static ModelSpec of(String model) {
        return new ModelSpec(model, null, null);
    }
}
