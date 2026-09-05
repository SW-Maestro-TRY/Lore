package com.lore.zzal.scene;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 혼자 논 장면 한 컷 — <b>그림이 아니라 레시피</b>다(정본 11·16장).
 *
 * <h3>★★ 왜 그림을 저장하지 않나</h3>
 * 장면은 이미 있는 재료(동작 그림·배경·소품)를 <b>조합</b>한 것일 뿐이다. 합성한 이미지를 만들어 두면
 * 사용자 한 명이 하루에 세 장씩 S3 를 먹고, 배경을 바꾸거나 소품 그림이 좋아져도 옛 장면은 낡은 채로 남는다.
 * 다섯 값만 남기면 화면이 <b>그때그때 최신 재료로</b> 조립한다.
 *
 * <h3>레시피 다섯 값(정본 11장)</h3>
 * {@code motionKey}(어떤 자세) · {@code background}(어느 방) · {@code prop}(무슨 소품) ·
 * {@code sceneAt}(몇 시 — 빛은 화면이 시각으로 계산) · {@code mood}(그때 게이지가 어땠나).
 *
 * ★ 한 줄 문구({@code line})는 <b>저장하지 않는다.</b> 문구는 톤이라 계속 다듬게 되는데, 저장해 두면
 *   옛 장면만 옛 문구를 달고 남는다. 다섯 값에서 그때그때 만든다.
 *
 * ★ 최대 3개(정본 16장 "혼자 논 장면 보관 3개"). 넘치면 가장 오래된 것을 지운다 —
 *   지우는 것이 아까워 보여도, 안 지우면 이 표가 사용자당 무한히 자란다.
 */
@Entity
@Table(name = "zzal_scene", indexes = @Index(name = "idx_zzal_scene_pet", columnList = "pet_id"))
public class ZzalScene {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /** 어떤 자세인가(카탈로그 key). 밤 연습 장면은 훈련 자세({@code practice}). */
    @Column(nullable = false, length = 40)
    private String motionKey;

    /** 그때 어느 방이었나. 배경을 바꿔도 장면은 그때 그 방을 기억한다. */
    @Column(nullable = false, length = 40)
    private String background;

    /** 그날의 소품(공·책·컵·화분). 없으면 null. */
    @Column(length = 40)
    private String prop;

    /** 그 장면의 시각(펫 시계). 화면이 이걸로 빛을 정한다(정본 11장 1). */
    @Column(nullable = false)
    private Instant sceneAt;

    /** 그때 게이지가 어땠나 — SICK · HUNGRY · SAD · DIRTY · NORMAL. 문구의 톤이 여기서 나온다. */
    @Column(nullable = false, length = 20)
    private String mood;

    /** 밤 연습 장면인가(정본 2장 "잠드는 순간 … 밤 장면"). */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean night;

    protected ZzalScene() {
    }

    public static ZzalScene of(Long petId, String motionKey, String background, String prop,
                               Instant sceneAt, String mood, boolean night) {
        ZzalScene s = new ZzalScene();
        s.petId = petId;
        s.motionKey = motionKey;
        s.background = background;
        s.prop = prop;
        s.sceneAt = sceneAt;
        s.mood = mood;
        s.night = night;
        return s;
    }

    public Long getId() {
        return id;
    }

    public Long getPetId() {
        return petId;
    }

    public String getMotionKey() {
        return motionKey;
    }

    public String getBackground() {
        return background;
    }

    public String getProp() {
        return prop;
    }

    public Instant getSceneAt() {
        return sceneAt;
    }

    public String getMood() {
        return mood;
    }

    public boolean isNight() {
        return night;
    }
}
