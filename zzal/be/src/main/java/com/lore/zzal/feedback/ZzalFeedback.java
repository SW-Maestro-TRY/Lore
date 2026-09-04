package com.lore.zzal.feedback;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 결과물에 대한 후기.
 *
 * ★ (사람, 펫) 유니크인 이유 — 없으면 새로고침 연타로 여러 번 들어간다.
 *   지금은 보상이 없어 티가 안 나지만, 나중에 보상을 켜는 순간 <b>같은 후기로 여러 번 받는다.</b>
 *   그때 고치려면 이미 쌓인 중복을 손으로 치워야 한다.
 *
 * ★ 이메일 칸을 두지 않는다 — 가입할 때 이미 받았다. 같은 정보를 두 곳에 두면
 *   지켜야 할 곳이 하나 더 늘고, 파기 시점도 따로 관리해야 한다.
 *
 * ★ 보상은 여기서 지급하지 않는다. 무엇을 줄지 아직 안 정했고(2026-09-03),
 *   미니게임과 <b>같은 자리</b>를 쓴다. 각자 지급 로직을 만들면 나중에 두 곳을 고쳐야 한다.
 */
@Entity
@Table(
        name = "zzal_feedback",
        uniqueConstraints = @UniqueConstraint(name = "uk_feedback_user_pet", columnNames = {"user_id", "pet_id"}),
        indexes = @Index(name = "idx_feedback_created", columnList = "created_at"))
public class ZzalFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /** 별점 1~5. */
    @Column(nullable = false)
    private int rating;

    /** 고른 칩들을 쉼표로. 미리 정해진 값만 들어온다(자유 입력이 아니다). */
    @Column(length = 200)
    private String tags;

    /** 자유롭게 쓴 말. ★이 내용은 이벤트 로그로 흘려보내지 않는다 — 거기엔 길이만 남긴다. */
    @Column(columnDefinition = "text")
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ZzalFeedback() {
    }

    public static ZzalFeedback of(Long userId, Long petId, int rating, String tags, String text, Instant now) {
        ZzalFeedback f = new ZzalFeedback();
        f.userId = userId;
        f.petId = petId;
        f.rating = rating;
        f.tags = tags;
        f.text = text;
        f.createdAt = now;
        return f;
    }

    public Long getId() {
        return id;
    }

    public int getRating() {
        return rating;
    }

    public String getTags() {
        return tags;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
