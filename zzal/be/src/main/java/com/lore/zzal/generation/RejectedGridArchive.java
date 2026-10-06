package com.lore.zzal.generation;

import com.lore.common.s3.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 버리는 격자를 <b>버리기 직전에 따로 보존</b>한다(2026-10-07 상훈님 — "실패 격자 아예 폐기하면 안 된다, 저장은 하고 있어야").
 *
 * <h3>★ 왜 필요한가</h3>
 * 재시도는 같은 키({@code images/zzal/pets/{id}/grid.png})에 새 격자를 덮어쓴다. 보존하지 않으면 게이트가
 * 왜 거부했는지, 후처리가 왜 죽었는지를 <b>그림으로 확인할 길이 사라진다</b>(오판인지 진짜 깨진 격자인지 못 가린다).
 *
 * <h3>★ 어디로</h3>
 * {@code images/zzal/pets/{petId}/rejected/{jobId}-{grid|grid2}.png}. jobId 는 그 격자를 구운 시도다.
 *
 * <h3>★★ 실패해도 재시도를 막지 않는다</h3>
 * 보존은 부가 기록이다. S3 가 잠깐 실패했다고 사용자의 부화가 멈추면 안 되므로 WARN 만 남기고 null 을 돌려준다.
 * ({@link S3Storage} 에 서버 측 copy 가 없어 내려받아 다시 올린다 — 격자 한 장 수 MB.)
 */
@Component
public class RejectedGridArchive {

    private static final Logger log = LoggerFactory.getLogger(RejectedGridArchive.class);

    private final S3Storage storage;

    public RejectedGridArchive(S3Storage storage) {
        this.storage = storage;
    }

    /** 보존 키. */
    public static String keyOf(Long petId, Long jobId, String stepName) {
        return "images/zzal/pets/%d/rejected/%d-%s.png".formatted(petId, jobId, stepName);
    }

    /**
     * 그 단계의 산출물을 보존 키로 복사한다.
     *
     * @return 보존 키. 산출물이 없거나 복사에 실패하면 null(재시도는 그대로 진행)
     */
    public String preserve(Long petId, GenStepRecord record) {
        if (record == null || record.getOutputKey() == null) {
            return null;
        }
        String to = keyOf(petId, record.getJobId(), record.getName());
        Path tmp = null;
        try {
            tmp = Files.createTempFile("zzal-rejected-", ".png");
            storage.download(record.getOutputKey(), tmp);
            storage.upload(to, tmp, "image/png");
            log.info("폐기 격자 보존 — petId={} {} → {}", petId, record.getOutputKey(), to);
            return to;
        } catch (Exception e) {
            log.warn("폐기 격자 보존 실패(재시도는 그대로 진행) — petId={} {} → {} : {}",
                    petId, record.getOutputKey(), to, String.valueOf(e));
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                    // 임시 파일 정리 실패는 무시한다
                }
            }
        }
    }

    /** 보존하지 않는 것(시험·옛 생성자 호환용). */
    public static RejectedGridArchive none() {
        return new RejectedGridArchive(null) {
            @Override
            public String preserve(Long petId, GenStepRecord record) {
                return null;
            }
        };
    }
}
