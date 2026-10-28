package com.lore.zzal.admin.retry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 관리자 재시도 요청 한 건 = 한 행({@code zzal_admin_retry}). 설계 spec-zzal-admin-failures-retry-20261027 1절.
 *
 * <h3>DB 가 막는 것</h3>
 * 같은 대상에 열린 요청(결과 없음 · 후보 도착 후 고르기 전)은 하나만({@code ux_zzal_admin_retry_open}),
 * 자동 요청은 대상당 1회({@code ux_zzal_admin_retry_auto}), 자동이면 요청자 없음·수동이면 요청자 있음
 * ({@code ck_zzal_admin_retry_auto_requester}). 서비스가 확인을 빠뜨려도 행이 두 개 생기지 않는다.
 *
 * ★ 시각은 전부 호출자가 넘긴다 — 엔티티 안에서 {@code Instant.now()} 를 부르지 않는다(ZzalPet 과 같은 규칙).
 */
@Entity
@Table(name = "zzal_admin_retry")
public class AdminRetry {

    public static final int MEMO_MAX = 500;
    public static final int NOTE_MAX = 2000;
    public static final int CHOICE_MAX = 100;
    /** 반영이 진행 중이라는 표식(applied_choice 칸). 결과는 CANDIDATES 그대로 — 두 번째 반영 · 취소를 막는다. */
    public static final String APPLYING = "APPLYING";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private AdminRetryKind kind;

    /** HATCH = zzal_pet.id, CUSTOM = zzal_custom_bundle_job.id. */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "auto", nullable = false)
    private boolean auto;

    @Column(name = "memo", length = MEMO_MAX)
    private String memo;

    @Column(name = "requested_by")
    private Long requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "picked_at")
    private Instant pickedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", length = 12)
    private AdminRetryResult result;

    @Column(name = "note", length = NOTE_MAX)
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "candidate_keys", columnDefinition = "jsonb")
    private Map<String, Object> candidateKeys;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "applied_by")
    private Long appliedBy;

    @Column(name = "applied_choice", length = CHOICE_MAX)
    private String appliedChoice;

    protected AdminRetry() {
    }

    private AdminRetry(AdminRetryKind kind, Long targetId, Long petId, boolean auto,
                       String memo, Long requestedBy, Instant now) {
        if (kind == null || targetId == null || petId == null || now == null) {
            throw new IllegalArgumentException("kind·targetId·petId·now 는 비울 수 없습니다");
        }
        this.kind = kind;
        this.targetId = targetId;
        this.petId = petId;
        this.auto = auto;
        this.memo = memo;
        this.requestedBy = requestedBy;
        this.requestedAt = now;
    }

    /** 관리자가 낸 요청. 메모는 앞뒤 공백·제어문자(줄바꿈 제외)를 지우고, 비면 null. */
    public static AdminRetry manual(AdminRetryKind kind, Long targetId, Long petId,
                                    String memo, Long adminId, Instant now) {
        if (adminId == null) {
            throw new IllegalArgumentException("수동 요청에는 요청한 관리자가 있어야 합니다");
        }
        return new AdminRetry(kind, targetId, petId, false, cleanMemo(memo), adminId, now);
    }

    /** 서버가 만든 자동 CLI 요청 — 메모·요청자 없음. */
    public static AdminRetry auto(AdminRetryKind kind, Long targetId, Long petId, Instant now) {
        return new AdminRetry(kind, targetId, petId, true, null, null, now);
    }

    static String cleanMemo(String memo) {
        if (memo == null) {
            return null;
        }
        String cleaned = memo.replaceAll("[\\p{Cc}&&[^\\n]]", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > MEMO_MAX) {
            throw new IllegalArgumentException("메모는 " + MEMO_MAX + "자 이하입니다");
        }
        return cleaned;
    }

    // ── 상태 ──────────────────────────────────────────────────────────────

    /** 결과 없음 또는 후보 도착 후 고르기 전. */
    public boolean isOpen() {
        return result == null || result == AdminRetryResult.CANDIDATES;
    }

    /** 러너가 집었다. 이미 집혔거나 결과가 있으면 거부. */
    public void claim(Instant now) {
        if (pickedAt != null || result != null) {
            throw new IllegalStateException("이미 집혔거나 끝난 요청입니다: " + id);
        }
        this.pickedAt = now;
    }

    /** 러너 결과 도착(CANDIDATES 또는 NONE). 결과 없는 요청에만. */
    public void deliver(AdminRetryResult r, String note, Map<String, Object> candidates, Instant now) {
        if (r != AdminRetryResult.CANDIDATES && r != AdminRetryResult.NONE) {
            throw new IllegalArgumentException("러너 결과는 CANDIDATES 또는 NONE 입니다: " + r);
        }
        if (result != null) {
            throw new IllegalStateException("이미 결과가 있는 요청입니다: " + id + " (" + result + ")");
        }
        this.result = r;
        this.note = clip(note, NOTE_MAX);
        this.candidateKeys = candidates == null ? null : new LinkedHashMap<>(candidates);
        this.finishedAt = now;
    }

    /** 관리자 취소. 열린 요청에만. */
    public void cancel(Instant now) {
        requireOpen();
        this.result = AdminRetryResult.CANCELLED;
        this.finishedAt = now;
    }

    /** 반영됨. by 는 사람이 고르면 users.id, 자동 반영·다른 길 복구면 null. */
    public void applied(Long by, String choice, Instant now) {
        requireOpen();
        this.result = AdminRetryResult.APPLIED;
        this.appliedAt = now;
        this.appliedBy = by;
        this.appliedChoice = clip(choice, CHOICE_MAX);
        if (finishedAt == null) {
            this.finishedAt = now;
        }
    }

    /** 반영됨 + 사유 한 줄(다른 길로 복구됨 · 서버에서 이어서 굽기 시작 등). */
    public void applied(Long by, String choice, String note, Instant now) {
        applied(by, choice, now);
        this.note = clip(note, NOTE_MAX);
    }

    /** 반영 실패 — 사유는 note 에 남긴다. */
    public void applyFailed(String note, Instant now) {
        requireOpen();
        this.result = AdminRetryResult.APPLY_FAILED;
        this.note = clip(note, NOTE_MAX);
        if (APPLYING.equals(appliedChoice)) {
            this.appliedChoice = null;
        }
        if (finishedAt == null) {
            this.finishedAt = now;
        }
    }

    /** 반영 시작 — 후보 도착 상태이고 아직 아무도 반영 중이 아닐 때만. */
    public void markApplying() {
        if (result != AdminRetryResult.CANDIDATES || appliedChoice != null) {
            throw new IllegalStateException("반영할 수 없는 요청입니다: " + id + " (" + result + "/" + appliedChoice + ")");
        }
        this.appliedChoice = APPLYING;
    }

    public boolean isApplying() {
        return APPLYING.equals(appliedChoice) && result == AdminRetryResult.CANDIDATES;
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("닫힌 요청입니다: " + id + " (" + result + ")");
        }
    }

    private static String clip(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    // ── 읽기 ──────────────────────────────────────────────────────────────

    public Long getId() { return id; }
    public AdminRetryKind getKind() { return kind; }
    public Long getTargetId() { return targetId; }
    public Long getPetId() { return petId; }
    public boolean isAuto() { return auto; }
    public String getMemo() { return memo; }
    public Long getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getPickedAt() { return pickedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public AdminRetryResult getResult() { return result; }
    public String getNote() { return note; }
    public Map<String, Object> getCandidateKeys() { return candidateKeys; }
    public Instant getAppliedAt() { return appliedAt; }
    public Long getAppliedBy() { return appliedBy; }
    public String getAppliedChoice() { return appliedChoice; }
}
