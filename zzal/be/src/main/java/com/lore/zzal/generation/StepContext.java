package com.lore.zzal.generation;

import java.util.HashMap;
import java.util.Map;

/**
 * 단계 사이를 오가는 것들.
 *
 * ★ 고정 필드(sheetKey · identityText …)로 두지 않은 이유 —
 *   단계가 새 산출물을 만들 때마다 이 클래스를 고쳐야 하기 때문이다.
 *   이름표 방식이면 새 단계가 새 이름으로 넣기만 하면 되고, 뒤 단계는 이름으로 꺼내 쓴다.
 *   (파이프라인이 계속 바뀔 예정이라 이 유연함이 필요하다)
 */
public class StepContext {

    private final Long petId;
    private final String petName;
    private final String note;
    private final String version;
    private final String outputPrefix;
    private final Map<String, String> images = new HashMap<>();
    private final Map<String, String> texts = new HashMap<>();

    public StepContext(Long petId, String petName, String note, String version) {
        this(petId, petName, note, version, "images/zzal/pets/%d".formatted(petId));
    }

    public StepContext(Long petId, String petName, String note, String version, String outputPrefix) {
        this.petId = petId;
        this.petName = petName;
        this.note = note;
        this.version = version;
        this.outputPrefix = outputPrefix;
    }

    /**
     * 이번에 굽는 것의 산출물이 쌓일 자리.
     *
     * ★ 단계가 경로를 직접 조립하지 않게 한 이유 — 부화는 펫당 한 벌이지만 모션은
     *   <b>한 펫에 열두 벌</b>이다. 펫 번호로만 경로를 만들면 두 번째 모션이 첫 번째를
     *   덮어써서, 배운 동작이 다른데 그림은 같아진다(예외도 안 난다).
     */
    public String outputPrefix() {
        return outputPrefix;
    }

    public void putImage(String name, String s3Key) {
        images.put(name, s3Key);
    }

    public void putText(String name, String text) {
        texts.put(name, text);
    }

    /** 앞 단계가 만든 이미지의 S3 키. 없으면 null — 그 단계가 건너뛰어졌다는 뜻일 수 있다. */
    public String image(String name) {
        return images.get(name);
    }

    public String text(String name) {
        return texts.get(name);
    }

    public Long petId() {
        return petId;
    }

    public String petName() {
        return petName;
    }

    /** 업로드 때 받은 자유 서술. 프롬프트에 섞어 쓴다(비어 있을 수 있다). */
    public String note() {
        return note;
    }

    public String version() {
        return version;
    }
}
