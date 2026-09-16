package com.lore.common.s3;

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
 * presign 으로 발급한 업로드 키의 기록.
 *
 * ★ 왜 필요한가 — 업로드는 브라우저가 S3 로 직접 하므로 **서버는 그 순간을 보지 못한다.**
 *   그래서 나중에 "이 키로 펫 만들어 주세요" 라고 왔을 때, 그 키가 진짜 그 사람이 올린
 *   것인지 알 방법이 없다. 발급 시점을 기록해 두면 세 가지가 한꺼번에 막힌다.
 *
 *     남의 키   → userId 가 다르므로 거부
 *     가짜 키   → 표에 없으므로 거부
 *     재사용    → usedAt 이 이미 차 있으므로 거부
 *
 * ★ 이 보호는 도메인 공통이다. zzal 뿐 아니라 webtoon·trailer 도 같은 presign 을 쓰므로
 *   똑같이 보호받는다.
 *
 * ★ 지금 이걸 넣는 이유 — 생성 한 번에 실제로 돈이 나간다($0.19). 검증이 없으면
 *   아무 그림 주소로나 생성을 요청할 수 있고, 그 비용은 우리가 부담한다.
 */
@Entity
@Table(
        name = "upload_ticket",
        uniqueConstraints = @UniqueConstraint(name = "uk_upload_ticket_key", columnNames = "s3_key"),
        indexes = @Index(name = "idx_upload_ticket_user", columnList = "user_id"))
public class UploadTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 발급받은 사람. users 를 참조하지만 외래키는 걸지 않는다(공통 모듈이 계정에 강하게 묶이지 않게).
     *  게스트가 받았으면 이 값은 비고 {@link #guestKey} 가 대신 찬다. */
    @Column(name = "user_id")
    private Long userId;

    /** 로그인 안 한 사람이 받았을 때만 찬다(IP 해시 등 — 발급한 쪽이 정한다).
     *  {@link #userId} 와 정확히 하나만 찬다(DB 의 ck_upload_ticket_owner 가 강제한다). */
    @Column(name = "guest_key", length = 64)
    private String guestKey;

    @Column(name = "s3_key", nullable = false, length = 300)
    private String s3Key;

    @Column(nullable = false, length = 20)
    private String domain;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private Instant issuedAt;

    /** 실제로 쓰인 시각. 비어 있으면 아직 안 쓴 키다. */
    @Column
    private Instant usedAt;

    protected UploadTicket() {
    }

    public static UploadTicket issue(Long userId, String s3Key, String domain,
                                     String contentType, Instant now) {
        UploadTicket t = new UploadTicket();
        t.userId = userId;
        t.s3Key = s3Key;
        t.domain = domain;
        t.contentType = contentType;
        t.issuedAt = now;
        return t;
    }

    /** 로그인 안 한 사람에게 발급할 때. {@code guestKey} 는 발급한 쪽(GuestGate 등)이 정한다. */
    public static UploadTicket issueForGuest(String guestKey, String s3Key, String domain,
                                             String contentType, Instant now) {
        UploadTicket t = new UploadTicket();
        t.guestKey = guestKey;
        t.s3Key = s3Key;
        t.domain = domain;
        t.contentType = contentType;
        t.issuedAt = now;
        return t;
    }

    /** 한 번 쓰면 다시 못 쓴다. 같은 그림으로 펫을 여러 마리 만드는 것을 막는다. */
    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    public boolean isOwnedBy(Long userId) {
        return userId != null && userId.equals(this.userId);
    }

    /** 게스트 티켓인지, 그리고 그 게스트가 맞는지. */
    public boolean isOwnedByGuest(String guestKey) {
        return guestKey != null && guestKey.equals(this.guestKey);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getS3Key() {
        return s3Key;
    }

    public String getDomain() {
        return domain;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
