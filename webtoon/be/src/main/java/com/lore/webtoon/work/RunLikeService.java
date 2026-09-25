package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.runs.RunService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 작품 찜(#247). 로그인한 사람만 부른다 — 주소가 {@code /my/...} 아래라 보안 설정이 막는다.
 *
 * <h2>두 번 눌러도 한 번이다</h2>
 * 찜은 (작품, 계정) 유일키라 같은 사람이 다시 눌러도 줄이 늘지 않고, 이미 있으면
 * 그대로 둔다. 화면이 재시도해도 안전하다. 찜 취소도 없는 것을 취소하면 조용히 넘어간다.
 */
@Service
public class RunLikeService {

    private final RunLikeRepository likes;
    private final WebtoonWorkRepository works;
    private final RunService runs;
    private final Clock clock;

    @Autowired
    public RunLikeService(RunLikeRepository likes, WebtoonWorkRepository works, RunService runs) {
        this(likes, works, runs, Clock.systemUTC());
    }

    RunLikeService(RunLikeRepository likes, WebtoonWorkRepository works, RunService runs, Clock clock) {
        this.likes = likes;
        this.works = works;
        this.runs = runs;
        this.clock = clock;
    }

    /** @return 찜한 뒤의 찜 수 */
    @Transactional
    public long like(Long userId, String runId) {
        if (works.findFirstByRunId(runId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "그런 작품이 없습니다");
        }
        if (likes.findByRunIdAndUserId(runId, userId).isEmpty()) {
            likes.save(new RunLike(runId, userId, Instant.now(clock)));
        }
        return likes.countByRunId(runId);
    }

    /** @return 취소한 뒤의 찜 수 */
    @Transactional
    public long unlike(Long userId, String runId) {
        likes.findByRunIdAndUserId(runId, userId).ifPresent(likes::delete);
        return likes.countByRunId(runId);
    }

    /** 이 사람이 찜한 작품들의 카드 — 최근에 찜한 것부터. 그림이 없거나 사라진 작품은 뺀다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> likedCards(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String runId : likes.runIdsLikedBy(userId)) {
            Map<String, Object> card = runs.cardOf(runId);
            if (card != null) {
                card.put("liked", true);
                out.add(card);
            }
        }
        return out;
    }

    /** 이 목록 중 이 사람이 찜한 작품 번호들. 로그인 안 했으면 빈 목록. */
    @Transactional(readOnly = true)
    public List<String> likedAmong(Long userId, List<String> runIds) {
        if (userId == null || runIds.isEmpty()) {
            return List.of();
        }
        return likes.likedAmong(userId, runIds);
    }
}
