package com.lore.zzal.bake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 일감 하나 — 어느 펫의 어느 동작을 굽는가(2차 명세 1장).
 *
 * <h3>★ 굽는 때는 잠과 무관하다 (정본 1.8)</h3>
 * 조건을 채운 그 순간 줄이 생긴다 — 튜토리얼을 끝낸 순간, 잠들며 판정이 난 순간,
 * 네 번째 조각이 차는 순간. 그래서 하루 종일 흩어져 만들어지고, 23:00 자동 취침 뒤에는
 * 아무도 돌보기를 못 하므로 <b>그 시각에 그날 구울 양이 확정된다</b>(정본 0장 원칙 4).
 *
 * <h3>★ 날짜 단위 이월이 없다</h3>
 * 옛 규칙("오늘 못 하면 내일 밤으로")을 버렸다. 줄은 <b>판정이 끝날 때까지 그대로 있고</b>,
 * 판정된 것만 나간다. 그래서 이 표에 "몇 월 며칠 밤" 같은 칸이 없다.
 *
 * <h3>★ 실패해도 줄이 사라지지 않는다</h3>
 * 굽기 실패는 조각을 소모하지 않는다(정본 16장). 같은 동작을 계속 다시 굽는다 —
 * 사용자가 잃는 것은 없고 늦어질 뿐이다.
 *
 * <p>★ 이 단계에서는 표와 상태만 만든다. 여기에 줄을 넣고 옮기는 것은 다음 단계다.
 */
@Entity
@Table(name = "zzal_bake",
        indexes = {
                @Index(name = "idx_zzal_bake_pet", columnList = "pet_id"),
                @Index(name = "idx_zzal_bake_status", columnList = "status")})
public class ZzalBake {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    /** 카탈로그의 동작 key. 한 펫이 같은 동작을 두 번 굽지 않는다. */
    @Column(name = "motion_key", nullable = false, length = 40)
    private String motionKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "bake_group", nullable = false, length = 20)
    private BakeGroup group;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BakeStatus status;

    /**
     * 몇 라운드까지 갔나. 0 = 서버 API 1판, 1~2 = 맥미니 CLI 3판씩.
     *
     * ★ 상한이 2 인 것은 비용이 아니라 <b>사람 시간</b> 때문이다. 후보가 일곱 판을 넘으면
     *   판정 한 건에 보는 양이 너무 많아진다.
     */
    @Column(nullable = false)
    private int round;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ZzalBake() {
    }

    public static ZzalBake start(Long petId, String motionKey, BakeGroup group, Instant now) {
        ZzalBake bake = new ZzalBake();
        bake.petId = petId;
        bake.motionKey = motionKey;
        bake.group = group;
        bake.status = BakeStatus.BAKING;
        bake.round = 0;
        bake.createdAt = now;
        bake.updatedAt = now;
        return bake;
    }

    public void moveTo(BakeStatus next, Instant now) {
        this.status = next;
        this.updatedAt = now;
    }

    /** 맥미니에 한 라운드를 더 맡긴다. */
    public void nextRound(Instant now) {
        this.round += 1;
        this.status = BakeStatus.REBAKING;
        this.updatedAt = now;
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

    public BakeGroup getGroup() {
        return group;
    }

    public BakeStatus getStatus() {
        return status;
    }

    public int getRound() {
        return round;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
