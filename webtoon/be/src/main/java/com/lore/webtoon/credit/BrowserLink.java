package com.lore.webtoon.credit;

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
 * 어느 계정이 어느 브라우저로 만들었는가.
 *
 * 웹툰은 로그인 없이도 끝까지 만들 수 있어서, 작품에는 계정 번호가 안 박혀
 * 있고 브라우저가 들고 다니는 {@code uid} 로만 묶여 있다. 「내가 만든 웹툰」을
 * 계정으로 보여 주려면 (계정 ↔ uid) 를 이어 둬야 한다 — 로그인 전에 만든
 * 것도 그대로 따라오고, 하네스는 한 줄도 안 고쳐도 된다.
 *
 * 한 계정에 uid 가 여럿일 수 있다. 로그인할 때마다 그 기기의 uid 를 더한다.
 *
 * ⚠️ 이것으로 소유를 증명하지는 못한다. uid 는 브라우저가 들고 다니는 값이라
 * 마음먹으면 남의 것을 적어 보낼 수 있다 — 이 표가 하는 일은 "내 기기들을
 * 모아 보여 주는 것" 이지 소유권 증명이 아니다. 목록에 뜨는 것 말고
 * <b>바꾸는 일</b>(공개 전환 등)은 하네스가 따로 확인한다.
 */
@Entity
@Table(
        name = "webtoon_browser_link",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_browser_link", columnNames = {"user_id", "browser_uid"}),
        indexes = @Index(name = "idx_webtoon_browser_link_user", columnList = "user_id"))
public class BrowserLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 계정. users 를 가리키지만 외래키는 안 건다(공통 모듈과 강하게 묶지 않는 이 저장소 규칙). */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 브라우저가 들고 다니는 값. 프론트의 localStorage `lore_uid` 와 같은 것이다. */
    @Column(name = "browser_uid", nullable = false, length = 64)
    private String browserUid;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected BrowserLink() {
    }

    private BrowserLink(Long userId, String browserUid, Instant linkedAt) {
        this.userId = userId;
        this.browserUid = browserUid;
        this.linkedAt = linkedAt;
    }

    public static BrowserLink of(Long userId, String browserUid, Instant now) {
        return new BrowserLink(userId, browserUid, now);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getBrowserUid() {
        return browserUid;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
