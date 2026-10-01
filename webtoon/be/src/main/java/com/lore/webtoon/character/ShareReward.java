package com.lore.webtoon.character;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공유 카드 방문 보상(#332) — 내 카드 링크로 <b>남이</b> 들어오면 「캐릭터 만들어보기」
 * 무료 횟수를 한 번 돌려준다.
 *
 * <h2>규칙</h2>
 * <ul>
 *   <li>주인이 자기 링크를 여는 것은 안 센다 — 계정이 같거나, 브라우저 번호가 주인 것이거나</li>
 *   <li>같은 사람이 여러 번 열어도 한 번만 센다 — (카드, 본 사람) 유일키</li>
 *   <li>카드 하나로 {@link #CAP_PER_CARD} 번까지만 준다. 그 뒤 방문은 세기만 한다</li>
 *   <li>돌려준 횟수는 하루 몫과 별개로 쌓이고, 하루 몫을 다 쓴 뒤에 하나씩 쓴다({@code CharacterService})</li>
 * </ul>
 *
 * <h2>본 사람을 무엇으로 가르나</h2>
 * 브라우저 번호({@code X-Lore-Uid})가 있으면 그것, 없으면 IP 해시다. 주소 원문은 저장하지 않는다.
 * 해시 소금은 게스트 한도({@code GuestGate})와 같은 설정을 쓴다.
 */
@Service
public class ShareReward {

    private static final Logger log = LoggerFactory.getLogger(ShareReward.class);

    /** 카드 하나로 돌려줄 수 있는 최대 횟수. 한 카드로 무한히 받지 못하게. */
    static final int CAP_PER_CARD = 3;

    private final ShareVisitRepository visits;
    private final String salt;
    private final Clock clock;

    @Autowired
    public ShareReward(ShareVisitRepository visits,
                       @Value("${lore.webtoon.spend.ip-salt:}") String salt) {
        this(visits, salt, Clock.systemUTC());
    }

    ShareReward(ShareVisitRepository visits, String salt, Clock clock) {
        this.visits = visits;
        this.salt = salt == null ? "" : salt;
        this.clock = clock;
    }

    /** 무엇이 됐나. 화면에는 안 나가고 기록·테스트용이다. */
    public enum Outcome { OWNER, SEEN_BEFORE, REWARDED, COUNTED_ONLY }

    /**
     * 공유 카드가 열렸다.
     *
     * @param card       열린 카드
     * @param viewerUid  본 사람의 브라우저 번호. 없을 수 있다
     * @param viewerUids 본 사람이 로그인했으면 그 계정에 이어진 브라우저 번호들까지(주인 판정용)
     * @param viewerUser 본 사람의 계정 번호. 없을 수 있다
     * @param viewerIp   본 사람의 주소. 해시로만 쓴다
     */
    @Transactional
    public Outcome opened(WebtoonCharacter card, String viewerUid, Collection<String> viewerUids,
                          Long viewerUser, String viewerIp) {
        if (card.madeBy(viewerUser, viewerUids)) {
            return Outcome.OWNER;
        }
        String key = viewerUid != null && !viewerUid.isBlank()
                ? "uid:" + viewerUid.trim()
                : "ip:" + hash(viewerIp == null ? "" : viewerIp);
        if (visits.existsByCharacterIdAndViewerKey(card.getPublicId(), key)) {
            return Outcome.SEEN_BEFORE;
        }
        boolean reward = visits.countByCharacterIdAndRewardedTrue(card.getPublicId()) < CAP_PER_CARD;
        try {
            visits.save(ShareVisit.of(card.getPublicId(), key, reward, Instant.now(clock)));
        } catch (DataIntegrityViolationException e) {
            // 같은 사람이 동시에 두 번 열었다 — 유일키가 막았으니 한 번으로 친다.
            return Outcome.SEEN_BEFORE;
        }
        if (reward) {
            log.info("공유 카드 방문 보상 — card={} 주인에게 무료 횟수 1", card.getPublicId());
        }
        return reward ? Outcome.REWARDED : Outcome.COUNTED_ONLY;
    }

    /** 이 사람이 아직 안 쓴, 돌려받은 무료 횟수. */
    @Transactional(readOnly = true)
    public int unused(Long userId, Collection<String> uids) {
        return (int) visits.unusedBonus(userId, uids);
    }

    /** 돌려받은 횟수 하나를 쓴다. 없으면 false. */
    @Transactional
    public boolean useOne(Long userId, Collection<String> uids) {
        return visits.nextUnused(userId, uids).map(v -> {
            v.use(Instant.now(clock));
            return true;
        }).orElse(false);
    }

    /** 카드 화면에 보여 줄 숫자 — 몇 명이 봤고, 몇 번을 돌려받았나. */
    @Transactional(readOnly = true)
    public Stats statsOf(String characterId) {
        return new Stats(visits.countByCharacterId(characterId), visits.countByCharacterIdAndRewardedTrue(characterId));
    }

    public record Stats(long visits, long rewarded) {
    }

    private String hash(String ip) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] out = sha.digest((salt + "|" + ip).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<String> none() {
        return List.of();
    }
}
