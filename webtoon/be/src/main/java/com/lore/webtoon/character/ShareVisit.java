package com.lore.webtoon.character;

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
 * 공유 카드 링크로 누가 들어온 것 한 줄(#332). 어떤 규칙으로 적히는지는 {@link ShareReward}.
 */
@Entity
@Table(
        name = "webtoon_share_visit",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_webtoon_share_visit_once", columnNames = {"character_id", "viewer_key"}),
        indexes = @Index(name = "idx_webtoon_share_visit_character", columnList = "character_id"))
public class ShareVisit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "character_id", nullable = false, length = 40)
    private String characterId;

    @Column(name = "viewer_key", nullable = false, length = 80)
    private String viewerKey;

    @Column(nullable = false)
    private boolean rewarded;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    protected ShareVisit() {
    }

    static ShareVisit of(String characterId, String viewerKey, boolean rewarded, Instant openedAt) {
        ShareVisit v = new ShareVisit();
        v.characterId = characterId;
        v.viewerKey = viewerKey;
        v.rewarded = rewarded;
        v.openedAt = openedAt;
        return v;
    }

    /** 주인이 이 한 번을 썼다. */
    void use(Instant now) {
        this.usedAt = now;
    }

    public String getCharacterId() {
        return characterId;
    }

    public String getViewerKey() {
        return viewerKey;
    }

    public boolean isRewarded() {
        return rewarded;
    }

    public Instant getUsedAt() {
        return usedAt;
    }
}
