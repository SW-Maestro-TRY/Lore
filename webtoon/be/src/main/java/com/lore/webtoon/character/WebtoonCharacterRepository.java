package com.lore.webtoon.character;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WebtoonCharacterRepository extends JpaRepository<WebtoonCharacter, Long> {

    Optional<WebtoonCharacter> findByPublicId(String publicId);

    /**
     * 이 사람이 고를 수 있는 것 — <b>내가 만든 것과 기본 제공.</b>
     *
     * 로그인 안 했으면 {@code userId} 가 비어 있고, 그때는 기본 제공만 나온다.
     * 남이 만든 것은 여기 안 섞인다(#259 에서 다룬다).
     *
     * 내 것이 먼저 나온다 — 만들어 둔 사람에게는 그것이 목적이고, 기본 제공은
     * 만들 것이 없을 때의 보조다.
     */
    @Query("""
           select c from WebtoonCharacter c
            where c.source = com.lore.webtoon.character.CharacterSource.BUILTIN
               or c.ownerId = :userId
               or c.browserUid in :uids
            order by case when c.source
                     = com.lore.webtoon.character.CharacterSource.BUILTIN
                     then 1 else 0 end, c.id desc
           """)
    List<WebtoonCharacter> pickableBy(@Param("userId") Long userId,
                                      @Param("uids") Collection<String> uids);

    /**
     * 이 이름의 기본 제공 캐릭터. 그림이 비었으면 채워 넣을 때 쓴다.
     *
     * <b>심은 표시로 찾는다.</b> 주인이 비었나로 찾으면 게스트가 같은 이름을
     * 쓴 캐릭터를 집어서 우리가 그 사람 것을 고쳐 버린다.
     */
    Optional<WebtoonCharacter> findFirstBySourceAndName(CharacterSource source, String name);

    /**
     * 우리가 심은 것만.
     *
     * <b>{@code ownerIdIsNull} 로 고르면 안 된다.</b> 로그인 안 하고 만든
     * 캐릭터도 주인이 비어 있어서 같이 딸려 온다 — 그것을 지우는 자리에
     * 쓰면 남의 캐릭터를 지운다.
     */
    List<WebtoonCharacter> findBySource(CharacterSource source);

    /** 오늘 이 사람이 몇 개나 만들었나 — 하루 무료 몫을 세는 자리. */
    @Query("""
           select count(c) from WebtoonCharacter c
            where (c.ownerId = :userId or c.browserUid in :uids)
              and c.createdAt >= :since
           """)
    long madeSince(@Param("userId") Long userId,
                   @Param("uids") Collection<String> uids,
                   @Param("since") Instant since);
}
