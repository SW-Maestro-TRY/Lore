package com.lore.webtoon.art;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * 더 안 쓰는 그림 한 장 — S3 에서 지우기 전까지 적어 둔다.
 *
 * 키를 그대로 id 로 쓴다. 같은 그림을 두 번 적을 일이 없고, 지울 때도 키로 찾는다.
 *
 * {@link Persistable} 을 구현하는 이유 — id 를 직접 정하는 엔티티는 스프링 데이터가 새 것인지
 * 몰라서 {@code save} 마다 먼저 한 번 읽어 본다(merge). 고아 점검이 한 번에 수천 장을 적을 때
 * 그 읽기가 장마다 붙지 않게, 새로 만든 것은 새 것이라고 알려 준다.
 */
@Entity
@Table(name = "webtoon_art_discard")
public class ArtDiscard implements Persistable<String> {

    @Id
    @Column(name = "s3_key", length = 300)
    private String s3Key;

    /** 왜 버렸나 — {@link ArtDiscards#REPLACED} · {@link ArtDiscards#DROPPED} · {@link ArtDiscards#ORPHAN}. */
    @Column(nullable = false, length = 20)
    private String reason;

    @Column(name = "discarded_at", nullable = false)
    private Instant discardedAt;

    @Transient
    private boolean fresh = true;

    protected ArtDiscard() {
    }

    ArtDiscard(String s3Key, String reason, Instant discardedAt) {
        this.s3Key = s3Key;
        this.reason = reason;
        this.discardedAt = discardedAt;
    }

    @PostLoad
    @PostPersist
    void stored() {
        this.fresh = false;
    }

    @Override
    public String getId() {
        return s3Key;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }

    public String getS3Key() {
        return s3Key;
    }

    public String getReason() {
        return reason;
    }

    public Instant getDiscardedAt() {
        return discardedAt;
    }
}
