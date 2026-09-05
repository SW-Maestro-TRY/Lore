package com.lore.webtoon;

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
 * <h2>왜 이런 표가 필요한가</h2>
 *
 * 웹툰은 <b>로그인 없이도 끝까지 만들 수 있다.</b> 그래서 작품에는 계정 번호가
 * 안 박혀 있고, 대신 브라우저가 들고 다니는 {@code uid} 로만 묶여 있다
 * (하네스의 크레딧도 같은 uid 로 센다).
 *
 * 그 상태에서 "내가 만든 웹툰" 을 계정으로 보여 주려면 둘 중 하나다.
 *
 * <ul>
 *   <li>작품마다 계정 번호를 박는다 — 정확하지만 <b>로그인 전에 만든 작품은
 *       영영 안 따라온다.</b> 이 제품에서는 그게 대부분이다(로그인이 나중이다)</li>
 *   <li><b>(계정 ↔ uid) 만 이어 둔다</b> — 로그인 전에 만든 것도 그대로 따라오고,
 *       하네스는 한 줄도 안 고쳐도 된다. 이쪽을 골랐다</li>
 * </ul>
 *
 * 한 계정에 uid 가 여럿일 수 있다. 사람은 기기를 여러 개 쓰고, 그때마다 새
 * 브라우저 uid 가 생긴다 — 로그인할 때마다 그 기기의 uid 를 이 표에 더한다.
 *
 * <h2>⚠️ 이것으로 소유를 증명하지는 못한다</h2>
 *
 * uid 는 브라우저가 만들어 들고 다니는 값이라 <b>마음먹으면 남의 것을 적어
 * 보낼 수 있다.</b> 그러면 남의 작품이 내 목록에 뜬다. 지금 이 표가 하는 일은
 * "내 기기들을 모아 보여 주는 것" 이지 소유권 증명이 아니다.
 *
 * 제대로 막으려면 만들 때 서버가 주인을 적어야 하는데, 그건 로그인을 필수로
 * 만들거나 게스트에게도 계정 비슷한 것을 발급한다는 뜻이라 지금 구조에서는
 * 할 수 없다(하네스 {@code ownership.py} 머리 주석에 같은 한계가 적혀 있다).
 * 목록에 뜨는 것 말고 <b>바꾸는 일</b>(공개 전환·다시 그리기)은 하네스가 따로
 * 확인한다.
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
