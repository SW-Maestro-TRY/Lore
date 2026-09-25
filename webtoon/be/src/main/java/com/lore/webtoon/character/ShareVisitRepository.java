package com.lore.webtoon.character;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShareVisitRepository extends JpaRepository<ShareVisit, Long> {

    boolean existsByCharacterIdAndViewerKey(String characterId, String viewerKey);

    long countByCharacterId(String characterId);

    long countByCharacterIdAndRewardedTrue(String characterId);

    /**
     * 이 사람의 카드들에 쌓인, 아직 안 쓴 무료 횟수. 카드 주인은 계정({@code ownerId})이거나
     * 브라우저({@code browserUid})다 — {@code freeLeft} 가 사람을 세는 것과 같은 묶음이다.
     */
    @Query("""
            select count(v) from ShareVisit v, WebtoonCharacter c
            where v.characterId = c.publicId and v.rewarded = true and v.usedAt is null
              and (c.ownerId = :userId or c.browserUid in :uids)
            """)
    long unusedBonus(@Param("userId") Long userId, @Param("uids") Collection<String> uids);

    /** 쓸 차례인 것 하나 — 먼저 받은 것부터. */
    @Query("""
            select v from ShareVisit v, WebtoonCharacter c
            where v.characterId = c.publicId and v.rewarded = true and v.usedAt is null
              and (c.ownerId = :userId or c.browserUid in :uids)
            order by v.id asc
            """)
    List<ShareVisit> unusedOldestFirst(@Param("userId") Long userId, @Param("uids") Collection<String> uids);

    default Optional<ShareVisit> nextUnused(Long userId, Collection<String> uids) {
        return unusedOldestFirst(userId, uids).stream().findFirst();
    }
}
