package com.lore.webtoon.art;

import com.lore.common.s3.S3Storage;
import com.lore.webtoon.job.RunFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 대기열에 적힌 그림을 7일 뒤 S3 에서 지운다. 매일 05:30 KST.
 *
 * <h2>왜 필요한가</h2>
 *
 * 그림은 올릴 때마다 새 이름을 받고(키에 작품·사람이 안 들어간다), DB 에는 지금 쓰는
 * 이름만 남는다. 다시 그리기·다시 굽기가 이름을 바꾸면 옛 그림을 가리키는 곳이 사라져서,
 * 작품을 지우거나 탈퇴해 파기해도 옛 판은 S3 에 영원히 남았다 — 파기는 DB 에 적힌 이름만
 * 지우기 때문이다. 그래서 이름이 바뀌는 자리({@link PageStore#record} · {@code BakeService})가
 * 옛 이름을 {@link ArtDiscards} 에 적고, 여기서 지운다.
 *
 * <h2>지우기 직전에 한 번 더 본다</h2>
 *
 * 적은 뒤 7일 사이에 그 이름을 다시 들고 간 줄이 있으면 지우지 않고 대기열에서만 뺀다.
 * 웹툰 쪽에서 그림 주소를 들고 있는 표 전부(장 · 구운 장 · 캐릭터 · 조연 시트 · 올린 사진)를 본다.
 *
 * <h2>로컬에서는 안 지운다</h2>
 *
 * {@code RunFiles} 와 같은 규칙이다. 버킷이 없거나 창고가 이 기계(노트북의 MinIO)면 그대로 둔다.
 * 굳이 바꾸려면 {@code lore.webtoon.art-discard.keep}(true/false)를 적는다.
 *
 * <h2>서버가 여러 대여도 괜찮다</h2>
 *
 * 없는 키를 지워도 S3 는 성공으로 답하고, 대기열 줄도 이미 없으면 그만이다. 두 대가 같이
 * 돌아도 결과가 같다. 그래서 다른 파기처럼 한 대만 켜는 스위치를 두지 않았다.
 */
@Component
public class ArtDiscardSweep {

    private static final Logger log = LoggerFactory.getLogger(ArtDiscardSweep.class);

    static final String CRON = "0 30 5 * * *";
    static final String ZONE = "Asia/Seoul";

    /** 적고 나서 이만큼 지나야 지운다. */
    static final Duration GRACE = Duration.ofDays(7);

    /** 한 번에 읽어 지우는 수. S3 배치 삭제 한 번(1000개)과 맞춘다. */
    static final int BATCH = 1000;

    private final ArtDiscardRepository discards;
    private final S3Storage storage;
    private final boolean active;

    public ArtDiscardSweep(ArtDiscardRepository discards, S3Storage storage,
                           @Value("${app.s3.content-bucket:}") String bucket,
                           @Value("${app.s3.endpoint:}") String endpoint,
                           @Value("${lore.webtoon.art-discard.keep:}") String keep) {
        this.discards = discards;
        this.storage = storage;
        boolean keepFiles = keep == null || keep.isBlank()
                ? bucket == null || bucket.isBlank() || RunFiles.onThisMachine(endpoint)
                : Boolean.parseBoolean(keep.trim());
        this.active = !keepFiles;
    }

    public record Result(int deleted, int stillUsed) {
        static final Result NOTHING = new Result(0, 0);
    }

    @Scheduled(cron = CRON, zone = ZONE)
    public void scheduled() {
        runOnce(Instant.now());
    }

    /**
     * 한 회차. 시각을 밖에서 받는다 — 경계를 시험에서 바로 물을 수 있다.
     *
     * 예외를 밖으로 안 내보낸다. 시각 트리거에서 예외가 새면 그 뒤로 다시 안 돈다.
     * S3 를 못 지운 묶음은 대기열에 그대로 두어 다음 날 다시 지운다.
     */
    public Result runOnce(Instant now) {
        if (!active) {
            return Result.NOTHING;
        }
        Instant cut = now.minus(GRACE);
        int deleted = 0;
        int stillUsed = 0;
        try {
            while (true) {
                List<ArtDiscard> due = discards.findByDiscardedAtBeforeOrderByDiscardedAtAsc(
                        cut, PageRequest.of(0, BATCH));
                if (due.isEmpty()) {
                    break;
                }
                List<String> keys = due.stream().map(ArtDiscard::getS3Key).toList();
                Set<String> used = stillUsed(keys);
                List<String> gone = new ArrayList<>();
                for (String k : keys) {
                    if (!used.contains(k)) {
                        gone.add(k);
                    }
                }
                if (!gone.isEmpty()) {
                    storage.delete(gone);       // 실패하면 여기서 끊긴다 — 줄은 남아 다음 날 다시 지운다
                }
                discards.deleteAllByIdInBatch(keys);
                deleted += gone.size();
                stillUsed += used.size();
                if (due.size() < BATCH) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            log.error("버린 그림을 지우다 멈췄습니다 — 남은 것은 다음 회차에 지웁니다 (이번에 {}장 지움)", deleted, e);
            return new Result(deleted, stillUsed);
        }
        if (deleted + stillUsed > 0) {
            log.info("버린 그림 {}장을 S3 에서 지웠습니다 ({}일 지난 것, 다시 쓰이고 있어 남긴 것 {}장)",
                    deleted, GRACE.toDays(), stillUsed);
        }
        return new Result(deleted, stillUsed);
    }

    /** 이 키들 가운데 아직 어느 표가 들고 있는 것. */
    private Set<String> stillUsed(List<String> keys) {
        Set<String> used = new HashSet<>();
        used.addAll(discards.pagesUsing(keys));
        used.addAll(discards.bakedUsing(keys));
        used.addAll(discards.charactersUsing(keys));
        used.addAll(discards.castSheetsUsing(keys));
        used.addAll(discards.uploadsUsing(keys));
        return used;
    }
}
