package com.lore.zzal.wish;

import com.lore.zzal.pet.ZzalRules;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * "이런 동작도 보고 싶어요" 한 줄.
 *
 * <h3>★ 후기와 달리 유니크가 없다 — 한 번 부를 때마다 한 줄이다</h3>
 * 후기는 "이 결과물이 어땠나" 라 사람당 한 번이 맞지만, 보고 싶은 동작은 여러 개일 수 있고
 * <b>쌓인 목록 자체</b>가 우리가 알고 싶은 것이다. 대신 무한히 쌓이지 않도록 하루 상한을
 * {@link MotionWishService} 가 센다.
 *
 * <h3>★ 글은 여기에만 남는다</h3>
 * 행동 기록(zzal_event)에는 사람이 쓴 글을 넣지 않는다 — {@code AnalyticsService} 가 허용 키
 * 밖의 값을 통째로 버리고, 그 목록에 자유 글을 <b>일부러</b> 넣지 않았다. 기록 쪽으로는
 * "남겼다" 는 사실만 고정된 이름으로 간다.
 */
@Entity
@Table(
        name = "zzal_motion_wish",
        indexes = @Index(name = "idx_zzal_motion_wish_pet_created", columnList = "pet_id, created_at"))
public class ZzalMotionWish {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /**
     * 누가 남겼나.
     *
     * ★ 펫으로도 주인을 찾을 수 있지만 칸을 따로 둔다 — 펫을 보낸 뒤에도 "누가 무엇을 바랐나" 가
     *   남아야 하고, 하루 상한을 사람 기준으로 옮길 날에 표를 안 고쳐도 된다.
     */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 사람이 쓴 글. 비어 있을 수 없다 — 공백뿐인 글은 받는 자리에서 이미 400 이다.
     *
     * ★ 길이는 {@link ZzalRules#MOTION_WISH_MAX_CHARS} 와 마이그레이션의 varchar 가 같은 숫자여야 한다.
     *   어긋나면 검증을 지나온 글이 저장에서 터져 사용자가 400 이 아니라 500 을 본다.
     */
    @Column(nullable = false, length = ZzalRules.MOTION_WISH_MAX_CHARS)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ZzalMotionWish() {
    }

    public static ZzalMotionWish of(Long userId, Long petId, String text, Instant now) {
        ZzalMotionWish w = new ZzalMotionWish();
        w.userId = userId;
        w.petId = petId;
        w.text = text;
        w.createdAt = now;
        return w;
    }

    public Long getId() {
        return id;
    }

    public Long getPetId() {
        return petId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
