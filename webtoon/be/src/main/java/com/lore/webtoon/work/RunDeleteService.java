package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.retention.BucketPresence;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.job.JobStatus;
import com.lore.webtoon.job.WebtoonJobRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 작품 하나를 지운다(#55).
 *
 * <h2>누가 지울 수 있나</h2>
 *
 * 공개 전환·다시 올리기와 같은 기준이다 — 내 계정에 이어진 브라우저가 만든 것
 * ({@link WorkLedger#mayChange}). 예시 작품은 주인이 없어도 못 지운다. 둘러보기의
 * 바탕이라, 지우면 부팅 때 다시 심기는 하지만 그동안 빈 화면이 된다.
 *
 * <h2>그림을 먼저 지운다</h2>
 *
 * S3 키는 행에만 적혀 있다. 행을 먼저 지우면 어느 그림을 지워야 하는지 알 길이
 * 사라져 그림이 영영 남는다({@code retention/WebtoonPurge} 와 같은 순서).
 * 그림을 못 지웠으면 행도 안 지운다 — 절반만 지워진 작품은 목록에서 사라졌는데
 * 주소로는 열리는, 설명할 수 없는 상태가 된다.
 *
 * <h2>만드는 중이면 안 지운다</h2>
 *
 * 러너가 그 작품 폴더에 쓰고 있고 끝나면 그림을 올려 행을 다시 만든다. 지워 봐야
 * 되살아나거나 반쯤 남는다. 「만들기 중단」으로 먼저 멈추고 지운다.
 */
@Service
public class RunDeleteService {

    private static final Logger log = LoggerFactory.getLogger(RunDeleteService.class);

    /** 아직 러너가 쥐고 있는 상태. */
    static final List<JobStatus> IN_PROGRESS = List.of(
            JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.AWAITING_PICK, JobStatus.AWAITING_SHEET);

    private final WebtoonWorkRepository works;
    private final WebtoonJobRepository jobs;
    private final RunDeleteRepository rows;
    private final WorkLedger ledger;
    private final S3Storage storage;
    private final boolean hasBucket;

    public RunDeleteService(WebtoonWorkRepository works, WebtoonJobRepository jobs,
                            RunDeleteRepository rows, WorkLedger ledger,
                            S3Storage storage, BucketPresence bucket) {
        this.works = works;
        this.jobs = jobs;
        this.rows = rows;
        this.ledger = ledger;
        this.storage = storage;
        this.hasBucket = bucket.exists();
    }

    /** 지운 것의 수. 그림은 S3 객체 수, 행은 DB 줄 수. */
    public record Deleted(String runId, int images, int rows) {
    }

    /**
     * @throws BusinessException 없는 작품(404) · 내 것이 아니거나 예시 작품(403) ·
     *                           만드는 중(409 대신 INVALID_INPUT 로 사유를 말한다)
     */
    @Transactional
    public Deleted delete(Long userId, String runId) {
        WebtoonWork work = works.findFirstByRunId(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 작품이 없습니다"));
        if (ExampleWorks.SEED_UID.equals(work.getBrowserUid())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "예시 작품은 지울 수 없습니다");
        }
        if (!ledger.mayChange(runId, userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "내가 만든 작품만 지울 수 있습니다");
        }
        if (jobs.existsByRunIdAndStatusIn(runId, IN_PROGRESS)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "만드는 중인 작품은 먼저 중단한 뒤 지울 수 있습니다");
        }

        // 1. 그림 먼저. 키는 행에만 있다.
        List<String> keys = new ArrayList<>(rows.pageKeys(runId));
        keys.addAll(rows.bakedKeys(runId));
        int images = 0;
        if (hasBucket && !keys.isEmpty()) {
            images = storage.delete(keys);
        }

        // 2. 딸린 것 → 작품 → 만들기 기록. 외래키가 없어서 순서를 여기서 지킨다.
        int n = 0;
        n += rows.deletePages(runId);
        n += rows.deleteBakedPages(runId);
        n += rows.deleteOverlays(runId);
        n += rows.deleteRegens(runId);
        n += rows.deleteStories(runId);
        // 비용 기록(webtoon_usage)은 남긴다. "오늘 얼마 나갔나" 와 상한은 지운 작품의
        // 값까지 더해야 맞고, 작품 id 만 남지 사람을 가리키는 값은 없다. 계정을 지울
        // 때는 WebtoonPurge 가 따로 지운다.
        n += rows.deleteWorks(runId);
        n += rows.deleteJobs(runId);

        log.info("작품을 지웠습니다 (run={}, user={}, 그림 {}장, 행 {}줄)", runId, userId, images, n);
        return new Deleted(runId, images, n);
    }
}
