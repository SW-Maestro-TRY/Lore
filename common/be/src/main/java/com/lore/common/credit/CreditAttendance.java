package com.lore.common.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 출석한 날 한 줄 — 「오늘 받기」를 누른 날.
 *
 * 보상은 여기 없다. 4일째·7일째 크레딧은 장부({@link CreditEvent})에 REWARD 로
 * 적힌다. 이 표는 「며칠째인가」만 센다.
 */
@Entity
@Table(
        name = "credit_attendance",
        /* 같은 날 두 번 눌러도 한 줄. 이 유일키가 「하루 한 번」의 전부다. */
        uniqueConstraints = @UniqueConstraint(
                name = "uk_credit_attendance_day",
                columnNames = {"user_id", "day"}))
public class CreditAttendance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 한국 시각 날짜. */
    @Column(nullable = false)
    private LocalDate day;

    /**
     * 그날이 카드의 몇 일째인가(1~7).
     *
     * 매번 지난 줄을 거슬러 세지 않고 찍을 때 박아 둔다 — 전날 줄 하나만 보면
     * 오늘 값이 나오고, 규칙이 나중에 바뀌어도 이미 찍힌 카드는 안 바뀐다.
     */
    @Column(nullable = false)
    private int streak;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CreditAttendance() {
    }

    private CreditAttendance(Long userId, LocalDate day, int streak, Instant createdAt) {
        this.userId = userId;
        this.day = day;
        this.streak = streak;
        this.createdAt = createdAt;
    }

    static CreditAttendance of(Long userId, LocalDate day, int streak, Instant at) {
        return new CreditAttendance(userId, day, streak, at);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public LocalDate getDay() {
        return day;
    }

    public int getStreak() {
        return streak;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
