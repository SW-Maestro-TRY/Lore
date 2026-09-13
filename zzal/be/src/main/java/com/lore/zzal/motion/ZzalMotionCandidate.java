package com.lore.zzal.motion;

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
 * 한 동작을 굽다가 나온 <b>판 하나</b>. 한 모션에 최대 일곱이 달린다(API 1 + 맥미니 3 × 2라운드).
 *
 * <h3>★★ 왜 생겼나 — 덮어쓰면 고를 수가 없다</h3>
 * {@link ZzalMotion} 은 그림 키가 한 칸이라, 맥미니가 다시 만들 때마다 <b>앞의 판을 덮어썼다.</b>
 * 정본 1.9 는 <i>"나온 판을 전부 보여 주고 그중에서 고른다"</i> 이다. 덮어쓰면
 * <ul>
 *   <li>고를 것이 하나뿐이라 <b>고른다는 말이 성립하지 않고</b></li>
 *   <li>나중에 <b>어느 판이 사용자에게 나갔는지</b>도 알 수 없다</li>
 * </ul>
 *
 * <h3>★ 게이트 판정을 지우지 않는다</h3>
 * 사람이 고른 판과 게이트가 말한 것을 나란히 두어야 <b>게이트를 사람 눈에 맞춰 보정</b>할 수 있다
 * (정본 6장 "게이트는 판정에 맞춰 보정"). 그 대조표가 이 표다. 그래서 떨어진 판도 지우지 않는다 —
 * 떨어진 판이야말로 게이트가 무엇을 놓쳤는지 말해 준다.
 *
 * <h3>★ {@code chosen} 은 한 모션에 하나뿐이다</h3>
 * DB 부분 유일 인덱스로 막는다({@code uk_zzal_motion_candidate_chosen}). 주석에만 두면 언젠가 둘이 된다.
 */
@Entity
@Table(name = "zzal_motion_candidate",
        indexes = @Index(name = "idx_zzal_motion_candidate_motion", columnList = "motion_id"))
public class ZzalMotionCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "motion_id", nullable = false)
    private Long motionId;

    /** 0 = 서버 API 판, 1~2 = 맥미니 CLI 라운드. */
    @Column(nullable = false)
    private int round;

    /** 16프레임 격자 원본. 판정 화면이 이것도 같이 보여 준다. API 판은 비어 있을 수 있다. */
    @Column(name = "grid_key", length = 300)
    private String gridKey;

    /** 잘라 붙여 완성한 움짤. */
    @Column(name = "image_key", nullable = false, length = 300)
    private String imageKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MotionSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "gate_verdict", length = 20)
    private GateVerdict gateVerdict;

    @Column(name = "gate_note", length = 300)
    private String gateNote;

    @Column(name = "gate_version", length = 20)
    private String gateVersion;

    /** 줄 세우는 데만 쓴다 — 통과 여부에는 안 쓴다(게이트 정의서). 아직 안 쟀으면 null. */
    @Column(name = "gate_score")
    private Double gateScore;

    @Column(nullable = false)
    private boolean chosen;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ZzalMotionCandidate() {
    }

    public static ZzalMotionCandidate of(Long motionId, int round, String gridKey, String imageKey,
                                         MotionSource source, GateVerdict gateVerdict, String gateNote,
                                         String gateVersion, Double gateScore, Instant now) {
        ZzalMotionCandidate c = new ZzalMotionCandidate();
        c.motionId = motionId;
        c.round = round;
        c.gridKey = gridKey;
        c.imageKey = imageKey;
        c.source = source;
        c.gateVerdict = gateVerdict;
        c.gateNote = gateNote;
        c.gateVersion = gateVersion;
        c.gateScore = gateScore;
        c.chosen = false;
        c.createdAt = now;
        return c;
    }

    public void choose() {
        this.chosen = true;
    }

    public void unchoose() {
        this.chosen = false;
    }

    public Long getId() {
        return id;
    }

    public Long getMotionId() {
        return motionId;
    }

    public int getRound() {
        return round;
    }

    public String getGridKey() {
        return gridKey;
    }

    public String getImageKey() {
        return imageKey;
    }

    public MotionSource getSource() {
        return source;
    }

    public GateVerdict getGateVerdict() {
        return gateVerdict;
    }

    public String getGateNote() {
        return gateNote;
    }

    public String getGateVersion() {
        return gateVersion;
    }

    public Double getGateScore() {
        return gateScore;
    }

    public boolean isChosen() {
        return chosen;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
