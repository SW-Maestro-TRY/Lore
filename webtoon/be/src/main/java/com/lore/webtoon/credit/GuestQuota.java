package com.lore.webtoon.credit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;

/**
    * 로그인 안 한 사람의 오늘 몫을 기록한다.
 */
@Entity
@Table(
        name = "webtoon_guest_quota",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_guest_quota_day", columnNames = {"ip_hash", "day"}))
public class GuestQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소금 섞어 해시한 접속 주소. 원래 주소는 어디에도 안 남는다. */
    @Column(name = "ip_hash", nullable = false, length = 64)
    private String ipHash;

    /** 어느 날인가. 한국 시간 기준 — 사람이 "오늘" 이라고 부르는 날과 같아야 한다. */
    @Column(name = "day", nullable = false)
    private LocalDate day;

    /** 그 날 이 주소에서 시작한 만들기 횟수. */
    @Column(name = "used", nullable = false)
    private int used;

    protected GuestQuota() {
    }

    GuestQuota(String ipHash, LocalDate day) {
        this.ipHash = ipHash;
        this.day = day;
        this.used = 0;
    }

    public Long getId() {
        return id;
    }

    public String getIpHash() {
        return ipHash;
    }

    public LocalDate getDay() {
        return day;
    }

    public int getUsed() {
        return used;
    }

    void use() {
        this.used++;
    }

    /** 시작조차 못 한 한 편을 도로 물린다. 0 밑으로는 안 내려간다. */
    void giveBack() {
        if (this.used > 0) {
            this.used--;
        }
    }
}
