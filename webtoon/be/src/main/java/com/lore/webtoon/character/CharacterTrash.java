package com.lore.webtoon.character;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 캐릭터 휴지통(2026-10-26) — 웹툰 휴지통({@code RunTrash})과 같은 모양이고 보관 기간만 다르다(15일).
 *
 * 지우기({@link CharacterService#remove})는 휴지통에 넣기만 한다. 휴지통에 든 캐릭터는 목록·웹툰 만들기·
 * 공개 캐릭터에서 빠지고, 보관 기간 안에는 되살릴 수 있다. 기간이 지나면 새벽에 영구 삭제한다.
 */
@Service
public class CharacterTrash {

    private static final Logger log = LoggerFactory.getLogger(CharacterTrash.class);

    private final WebtoonCharacterRepository characters;
    private final CharacterService service;
    private final int keepDays;

    public CharacterTrash(WebtoonCharacterRepository characters, CharacterService service,
                          @Value("${lore.webtoon.character-trash.keep-days:15}") int keepDays) {
        this.characters = characters;
        this.service = service;
        this.keepDays = Math.max(1, keepDays);
    }

    public int keepDays() {
        return keepDays;
    }

    public Instant purgeAt(Instant deletedAt) {
        return deletedAt.plus(Duration.ofDays(keepDays));
    }

    @Transactional(readOnly = true)
    public List<WebtoonCharacter> trashOf(Long userId, Collection<String> uids) {
        return characters.trashOf(userId, uids == null || uids.isEmpty() ? List.of("") : uids);
    }

    /** @return 되살렸으면 true, 이미 살아 있었으면 false */
    @Transactional
    public boolean restore(String publicId, Long userId, Collection<String> uids) {
        WebtoonCharacter one = characters.findByPublicId(publicId)
                .filter(c -> c.madeBy(userId, uids))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 캐릭터가 없습니다"));
        if (!one.isTrashed()) {
            return false;
        }
        one.untrash(Instant.now());
        characters.save(one);
        log.info("캐릭터를 휴지통에서 되살렸습니다 (character={})", publicId);
        return true;
    }

    @Scheduled(cron = "0 20 5 * * *", zone = "Asia/Seoul")
    public void purgeExpired() {
        Instant cut = Instant.now().minus(Duration.ofDays(keepDays));
        int gone = 0;
        for (WebtoonCharacter one : characters.trashedBefore(cut)) {
            try {
                service.purge(one);
                gone++;
            } catch (RuntimeException e) {
                log.error("휴지통 캐릭터를 못 지웠습니다 (character={})", one.getPublicId(), e);
            }
        }
        if (gone > 0) {
            log.info("휴지통에서 {}일이 지난 캐릭터 {}개를 영구 삭제했습니다", keepDays, gone);
        }
    }
}
