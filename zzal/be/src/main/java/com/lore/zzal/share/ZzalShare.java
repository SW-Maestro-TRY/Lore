package com.lore.zzal.share;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * 공유 링크 한 줄.
 *
 * <h3>★ 왜 링크인가 — 파일이 아니라</h3>
 * X·인스타그램의 인앱 브라우저는 <b>파일 다운로드를 막는다.</b> 거기서 "저장" 버튼은 아무 일도
 * 일어나지 않는 버튼이 된다. 링크는 그 브라우저에서도 열리므로 사실상 유일한 확산 경로다.
 *
 * <h3>★ 토큰은 순번이 아니다</h3>
 * 1·2·3 이면 남의 아이를 순서대로 훑을 수 있다. 128비트 난수를 쓴다 — 찍어서 맞힐 수 없다.
 *
 * <h3>★ 같은 동작을 다시 공유하면 있던 링크를 준다</h3>
 * 누를 때마다 새 주소가 나오면 어제 올린 트윗의 링크가 오늘 것과 달라져, 무엇이 얼마나 퍼졌는지
 * 셀 수 없게 된다. (petId, motionKey) 한 쌍에 링크 하나다.
 */
@Entity
@Table(name = "zzal_shares",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_zzal_shares_token", columnNames = "token"),
                @UniqueConstraint(name = "uk_zzal_shares_pet_motion", columnNames = {"pet_id", "motion_key"})})
public class ZzalShare {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주소에 실리는 값. 22 글자(128비트)라 훑어서 찾을 수 없다. */
    @Column(nullable = false, length = 32)
    private String token;

    @Column(name = "pet_id", nullable = false)
    private Long petId;

    @Column(name = "motion_key", nullable = false, length = 40)
    private String motionKey;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /** 몇 번 열렸나. 무엇이 실제로 퍼졌는지 보는 유일한 숫자다. */
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long views;

    protected ZzalShare() {
    }

    private ZzalShare(String token, Long petId, String motionKey, Instant now) {
        this.token = token;
        this.petId = petId;
        this.motionKey = motionKey;
        this.createdAt = now;
    }

    public static ZzalShare issue(Long petId, String motionKey, Instant now) {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new ZzalShare(token, petId, motionKey, now);
    }

    public void viewed() {
        views += 1;
    }

    public String getToken() {
        return token;
    }

    public Long getPetId() {
        return petId;
    }

    public String getMotionKey() {
        return motionKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getViews() {
        return views;
    }
}
