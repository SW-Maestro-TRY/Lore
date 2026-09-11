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
 * 나온 판 하나. 한 일감에 <b>최대 일곱 판</b>이 달린다(서버 1 + 맥미니 3 × 2라운드).
 *
 * <h3>★ 왜 판마다 줄을 따로 두나</h3>
 * 판정할 때 <b>나온 판을 전부 보여 주고 그중에서 고른다</b>(정본 6장). 게이트가 미리 하나만
 * 남기는 것은 결국 게이트를 믿는 것인데, 지금은 믿지 않기로 했다. 그리고 여러 판을 나란히 놓으면
 * 한 장만 놓고 "이 정도면 괜찮은가" 를 고민하는 것보다 고르기가 빠르다.
 *
 * <h3>★ 게이트 판정을 지우지 않고 남긴다</h3>
 * 사람이 고른 것과 게이트가 말한 것을 나중에 대조해야 <b>게이트 기준을 사람 눈에 맞춰 보정</b>할 수
 * 있다(정본 6장 "게이트는 판정에 맞춰 보정"). 그 대조표가 이 두 칸이다.
 */
@Entity
@Table(name = "zzal_bake_candidate",
        indexes = @Index(name = "idx_zzal_bake_candidate_bake", columnList = "bake_id"))
public class ZzalBakeCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bake_id", nullable = false)
    private Long bakeId;

    /** 0 = 서버 API 판, 1~2 = 맥미니 CLI 라운드. */
    @Column(nullable = false)
    private int round;

    /** 16프레임 격자 원본. 판정 화면이 이것도 같이 보여 준다. */
    @Column(name = "grid_key", nullable = false, length = 300)
    private String gridKey;

    /** 잘라 붙여 완성한 움짤. */
    @Column(name = "webp_key", nullable = false, length = 300)
    private String webpKey;

    /** 게이트 점수 — 높은 것부터 위에 놓는 데 쓴다. 아직 안 쟀으면 null. */
    @Column(name = "gate_score")
    private Double gateScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_verdict", nullable = false, length = 20)
    private BakeGateVerdict gateVerdict;

    /** 사람이 이 판을 골랐나. 한 일감에 최대 하나. */
    @Column(nullable = false)
    private boolean chosen;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ZzalBakeCandidate() {
    }

    public static ZzalBakeCandidate of(Long bakeId, int round, String gridKey, String webpKey,
                                       Double gateScore, BakeGateVerdict gateVerdict, Instant now) {
        ZzalBakeCandidate row = new ZzalBakeCandidate();
        row.bakeId = bakeId;
        row.round = round;
        row.gridKey = gridKey;
        row.webpKey = webpKey;
        row.gateScore = gateScore;
        row.gateVerdict = gateVerdict;
        row.chosen = false;
        row.createdAt = now;
        return row;
    }

    public void choose() {
        this.chosen = true;
    }

    public Long getId() {
        return id;
    }

    public Long getBakeId() {
        return bakeId;
    }

    public int getRound() {
        return round;
    }

    public String getGridKey() {
        return gridKey;
    }

    public String getWebpKey() {
        return webpKey;
    }

    public Double getGateScore() {
        return gateScore;
    }

    public BakeGateVerdict getGateVerdict() {
        return gateVerdict;
    }

    public boolean isChosen() {
        return chosen;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
