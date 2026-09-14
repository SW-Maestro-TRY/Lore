package com.lore.zzal.archive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 보관 배치의 설정과 <b>돌아도 되는가</b>의 판정.
 *
 * <h3>★★ 기본은 꺼짐이고, 버킷이 비면 꺼짐보다 더 확실히 안 돈다</h3>
 * 보관 버킷은 아직 만들어지지 않았다(11/27 계정 삭제 때문에 새 계정에서 만든다).
 * 그 사이에 누가 스위치만 켜면 <b>버킷 이름이 빈 채로</b> 호출이 나간다. AWS SDK 는
 * "Bucket cannot be empty" 만 말하고 어디를 고칠지는 안 알려 준다. 그래서 여기서 먼저 막는다.
 *
 * <h3>★★ 그림 버킷과 같으면 안 돈다</h3>
 * 그림 버킷은 CloudFront 가 {@code /images/*} 를 공개로 내보내는 곳이다. 실수로 그 이름을
 * 적으면 <b>배포도 기동도 전부 통과하고</b> 새벽에 조용히 개인 식별자가 그리로 올라간다.
 * 접두사 검사({@link EventArchiveKeys#prefixProblem})만으로는 부족하다 — 같은 버킷 안이라도
 * 정책 한 줄이 바뀌면 공개가 될 수 있고, 애초에 버킷을 나누는 것이 기본이다.
 *
 * <h3>★ 왜 기동을 막지 않고 "안 돈다" 로 두나</h3>
 * 보관은 곁다리다. 설정이 틀렸다고 기동을 막으면 <b>부화·놀이가 통째로 멈춘다</b> —
 * 보관 하나 때문에 서비스가 서는 것은 어떤 경우에도 맞지 않는 거래다. 대신 뜰 때 한 번,
 * 돌 때마다 한 번 <b>설정 이름을 그대로 말하는 로그</b>를 남긴다.
 */
@Component
public class EventArchiveSettings {

    private final boolean enabled;
    private final String bucket;
    private final String contentBucket;
    private final String prefix;
    private final int maxRows;
    private final int chunk;
    private final int settleLagMinutes;

    public EventArchiveSettings(
            @Value("${app.zzal.archive.enabled:false}") boolean enabled,
            @Value("${app.zzal.archive.bucket:}") String bucket,
            @Value("${app.s3.content-bucket:}") String contentBucket,
            @Value("${app.zzal.archive.prefix:archive}") String prefix,
            @Value("${app.zzal.archive.max-rows:50000}") int maxRows,
            @Value("${app.zzal.archive.chunk:5000}") int chunk,
            @Value("${app.zzal.archive.settle-lag-minutes:10}") int settleLagMinutes) {
        this.enabled = enabled;
        this.bucket = bucket == null ? "" : bucket.trim();
        this.contentBucket = contentBucket == null ? "" : contentBucket.trim();
        this.prefix = prefix == null ? "" : prefix.trim();
        this.maxRows = maxRows;
        this.chunk = chunk;
        this.settleLagMinutes = settleLagMinutes;
    }

    /**
     * 돌아도 되면 {@code null}, 아니면 <b>안 도는 이유</b>(설정 이름을 포함한 한 문장).
     */
    public String blockedReason() {
        if (!enabled) {
            return "app.zzal.archive.enabled (ZZAL_ARCHIVE_ENABLED) 가 꺼져 있습니다";
        }
        if (!StringUtils.hasText(bucket)) {
            return "app.zzal.archive.bucket (ZZAL_ARCHIVE_BUCKET) 이 비어 있습니다 — "
                    + "보관 전용 버킷이 준비되기 전에는 올리지 않습니다";
        }
        if (StringUtils.hasText(contentBucket) && bucket.equals(contentBucket)) {
            return "app.zzal.archive.bucket 이 그림 버킷(app.s3.content-bucket)과 같습니다 — "
                    + "그 버킷은 CloudFront 가 공개로 내보내는 곳이라 행동 기록을 둘 수 없습니다: " + bucket;
        }
        String prefixProblem = EventArchiveKeys.prefixProblem(prefix);
        if (prefixProblem != null) {
            return prefixProblem;
        }
        if (maxRows <= 0 || chunk <= 0) {
            return "app.zzal.archive.max-rows · chunk 는 1 이상이어야 합니다 (지금: %d · %d)"
                    .formatted(maxRows, chunk);
        }
        return null;
    }

    public boolean canRun() {
        return blockedReason() == null;
    }

    /**
     * 이 시간보다 최근에 도착한 줄은 아직 안 올린다.
     *
     * <h3>★★ 왜 기다리나 — id 는 커밋 순서가 아니다</h3>
     * {@code zzal_event.id} 는 INSERT 순서로 매겨지지만 <b>커밋 순서는 다를 수 있다.</b>
     * 지금 보이는 가장 큰 id 를 기준점으로 삼으면, 그보다 작은 id 가 아직 커밋 전이라
     * 안 보였던 경우 그 줄은 <b>영영 건너뛴다</b>. 기록 INSERT 는 짧은 트랜잭션 하나라
     * 몇 밀리초면 끝나지만, 몇 분을 기다려 주면 그 틈이 사실상 사라진다.
     */
    public Duration settleLag() {
        return Duration.ofMinutes(Math.max(0, settleLagMinutes));
    }

    public String bucket() {
        return bucket;
    }

    public String prefix() {
        return prefix;
    }

    /** 한 번 도는 동안 올릴 수 있는 줄 수의 상한. */
    public int maxRows() {
        return maxRows;
    }

    /** 한 번에 메모리로 읽는 줄 수. 쌓인 것이 많아도 이 크기를 넘겨 읽지 않는다. */
    public int chunk() {
        return chunk;
    }
}
