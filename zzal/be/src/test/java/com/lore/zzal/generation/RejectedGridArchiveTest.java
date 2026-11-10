package com.lore.zzal.generation;

import com.lore.common.s3.S3Storage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("폐기 격자 보존 — 버리기 직전에 rejected/ 로 복사")
class RejectedGridArchiveTest {

    private static final Instant T0 = Instant.parse("2026-10-07T03:00:00Z");

    private static GenStepRecord grid2(Long jobId) {
        GenStepRecord r = GenStepRecord.start(jobId, 3, "grid2", T0);
        r.succeed("images/zzal/pets/7/grid2.png", null, "gpt-image-2", BigDecimal.ZERO, T0);
        return r;
    }

    @Test
    @DisplayName("★ 내려받아 rejected/{jobId}-grid2.png 로 올리고 그 키를 돌려준다")
    void copiesToTheRejectedKey() {
        S3Storage s3 = mock(S3Storage.class);
        String key = new RejectedGridArchive(s3).preserve(7L, grid2(41L));

        assertThat(key).isEqualTo("images/zzal/pets/7/rejected/41-grid2.png");
        verify(s3).download(eq("images/zzal/pets/7/grid2.png"), any(Path.class));
        verify(s3).upload(eq("images/zzal/pets/7/rejected/41-grid2.png"), any(Path.class), eq("image/png"));
    }

    @Test
    @DisplayName("★★ S3 가 실패해도 예외를 내지 않는다 — 보존 실패가 재시도를 막으면 안 된다")
    void failureNeverThrows() {
        S3Storage s3 = mock(S3Storage.class);
        doThrow(new IllegalStateException("s3 down")).when(s3).download(any(), any());

        assertThat(new RejectedGridArchive(s3).preserve(7L, grid2(41L))).isNull();
        verify(s3, never()).upload(any(), any(), any());
    }
}
