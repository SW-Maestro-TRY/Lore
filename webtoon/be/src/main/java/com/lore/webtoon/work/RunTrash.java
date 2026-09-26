package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.art.PageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 작품 휴지통(#157).
 *
 * <h2>왜 바로 안 지우나</h2>
 *
 * 예전에는 「지우기」가 그림(S3)과 행을 그 자리에서 영구 삭제했다. 돈을 들여
 * 만든 작품을 잘못 누른 한 번에 되돌릴 길 없이 잃는다. 그래서 지우면 휴지통에
 * 넣고({@code deleted_at}), 정한 기간 안에는 되살릴 수 있게 한다.
 *
 * <h2>휴지통에 든 작품은 없는 작품처럼 군다</h2>
 *
 * 내 목록·둘러보기·찜 목록에서 빠지고, 결과·회차·장 주소도 404 다(공유 링크로
 * 들어온 사람도 못 본다). 그림은 비공개 자리로 옮겨 둔다 — CloudFront 주소를
 * 이미 아는 사람에게도 안 열리게 하려는 것이고, 공개 전환과 같은 방법이다.
 *
 * <h2>기간이 지나면 예전 영구 삭제로 지운다</h2>
 *
 * 하루에 한 번 {@link #purgeExpired} 가 기간이 지난 것을 {@link RunDeleteService#purge}
 * 로 지운다. 기간은 {@code lore.webtoon.trash.keep-days}(기본 30일)이고, 화면의
 * 「N일 안에 되살릴 수 있습니다」 문구도 이 값을 받아 쓴다.
 */
@Service
public class RunTrash {

    private static final Logger log = LoggerFactory.getLogger(RunTrash.class);

    private final WebtoonWorkRepository works;
    private final RunDeleteService deleter;
    private final WorkLedger ledger;
    private final PageStore pages;
    private final com.lore.webtoon.runs.RunService runs;
    private final int keepDays;

    public RunTrash(WebtoonWorkRepository works, RunDeleteService deleter, WorkLedger ledger,
                    PageStore pages, com.lore.webtoon.runs.RunService runs,
                    @Value("${lore.webtoon.trash.keep-days:30}") int keepDays) {
        this.works = works;
        this.deleter = deleter;
        this.ledger = ledger;
        this.pages = pages;
        this.runs = runs;
        this.keepDays = Math.max(1, keepDays);
    }

    /** 휴지통에 둔 뒤 며칠 동안 되살릴 수 있나. */
    public int keepDays() {
        return keepDays;
    }

    /** @param purgeAt 이 시각이 지나면 영구 삭제된다 */
    public record Trashed(String runId, Instant deletedAt, Instant purgeAt, int keepDays) {
    }

    /**
     * 휴지통에 넣는다. 누가 넣을 수 있는지는 예전 영구 삭제와 같다 — 내 작품만,
     * 예시 작품은 안 되고, 만드는 중이면 먼저 중단해야 한다.
     */
    @Transactional
    public Trashed trash(Long userId, String runId) {
        WebtoonWork work = deleter.checkMayDelete(userId, runId);
        if (!work.isTrashed()) {
            work.trash(Instant.now());
            works.save(work);
            hideArt(runId);
            log.info("작품을 휴지통에 넣었습니다 (run={}, user={})", runId, userId);
        }
        return new Trashed(runId, work.getDeletedAt(), purgeAt(work.getDeletedAt()), keepDays);
    }

    /** 휴지통에서 꺼낸다. 공개였던 작품은 그림도 다시 공개 자리로 옮긴다. */
    @Transactional
    public boolean restore(Long userId, String runId) {
        WebtoonWork work = works.findFirstByRunId(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 작품이 없습니다"));
        if (!ledger.mayChange(runId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품만 되살릴 수 있습니다");
        }
        if (!work.isTrashed()) {
            return false;
        }
        work.untrash();
        works.save(work);
        if (work.isPublic()) {
            try {
                pages.moveAll(runId, true);
            } catch (RuntimeException e) {
                log.error("작품은 되살렸는데 그림을 공개 자리로 못 옮겼습니다 (run={})", runId, e);
            }
        }
        log.info("작품을 휴지통에서 되살렸습니다 (run={}, user={})", runId, userId);
        return true;
    }

    /** 내 휴지통. 최근에 지운 것부터, 카드마다 {@code purge_at} 이 붙는다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> trashOf(Long userId) {
        List<Map<String, Object>> cards = runs.trashCards(works.trashOf(userId));
        for (Map<String, Object> card : cards) {
            Object at = card.get("deleted_at");
            if (at != null) {
                card.put("purge_at", purgeAt(Instant.parse(at.toString())).toString());
            }
            /* 표지 주소를 여기서 준다. 휴지통 작품은 장 주소(/runs/{id}/page/{no})가
               404 라 화면이 평소처럼 표지를 부를 수 없다. 주인 확인을 마친 목록이라
               비공개 자리의 잠깐 열리는 주소를 줘도 된다. */
            Object runId = card.get("run_id");
            Object cover = card.get("cover_page");
            if (runId != null && cover instanceof Number no) {
                card.put("cover_url", pages.urlOf(runId.toString(), no.intValue(), 320));
            }
        }
        return cards;
    }

    /**
     * 기간이 지난 것을 영구 삭제한다. 하루에 한 번.
     *
     * 한 편이 실패해도 나머지는 지운다 — 다음 날 또 부르므로 남은 것은 그때 간다.
     */
    @Scheduled(cron = "0 10 5 * * *", zone = "Asia/Seoul")
    public void purgeExpired() {
        Instant cut = Instant.now().minus(Duration.ofDays(keepDays));
        int gone = 0;
        for (WebtoonWork one : works.trashedBefore(cut)) {
            try {
                deleter.purge(one.getRunId());
                gone++;
            } catch (RuntimeException e) {
                log.error("휴지통 작품을 못 지웠습니다 (run={})", one.getRunId(), e);
            }
        }
        if (gone > 0) {
            log.info("휴지통에서 {}일이 지난 작품 {}편을 영구 삭제했습니다", keepDays, gone);
        }
    }

    private Instant purgeAt(Instant deletedAt) {
        return deletedAt.plus(Duration.ofDays(keepDays));
    }

    /** 공개 전환과 같은 이유로, 못 옮겨도 휴지통에 넣은 것은 그대로 둔다. */
    private void hideArt(String runId) {
        try {
            pages.moveAll(runId, false);
        } catch (RuntimeException e) {
            log.error("휴지통에 넣었는데 그림을 비공개 자리로 못 옮겼습니다 (run={})", runId, e);
        }
    }
}
