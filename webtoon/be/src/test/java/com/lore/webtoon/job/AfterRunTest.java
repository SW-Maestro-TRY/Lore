package com.lore.webtoon.job;

import com.lore.webtoon.art.PageUploader;
import com.lore.webtoon.usage.UsageService;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 나간 값을 적는 자리.
 *
 * <b>이 검사가 지키는 것은 "언제든 부를 수 있는가" 다.</b> 값 적기가 다 만든
 * 뒤에만 되는 일이면, 끝까지 못 간 작품의 값이 하루 상한에서 통째로 0원이
 * 된다 — 죽어도 돈은 이미 나간 뒤인데. 그래서 걸음 도중에도, 실패한 뒤에도,
 * 같은 것을 두 번 불러도 탈이 없어야 한다.
 */
class AfterRunTest {

    @TempDir
    Path runs;

    private UsageService usage;
    private AfterRun after;

    @BeforeEach
    void setUp() {
        usage = mock(UsageService.class);
        PageUploader uploader = mock(PageUploader.class);
        when(uploader.ready()).thenReturn(false);        // 여기서는 그림을 안 올린다
        after = new AfterRun(usage, uploader, mock(WorkLedger.class),
                mock(HarnessProcess.class), runs.toString());
    }

    /** 하네스가 적는 모양 그대로. 값은 {@code cost.total_krw} 에 있다. */
    private void writeMeta(String runId, int... krw) throws IOException {
        StringBuilder calls = new StringBuilder();
        for (int i = 0; i < krw.length; i++) {
            calls.append(i == 0 ? "" : ",").append("""
                    {"stage":"PAGE_IMAGE","provider":"openai","model":"gpt-image-2",
                     "at":1757300000.0,
                     "usage":{"input":100,"output":20},
                     "cost":{"total":0.07,"total_krw":%d,"cost_basis":"tokens"}}
                    """.formatted(krw[i]));
        }
        Path dir = Files.createDirectories(runs.resolve(runId));
        Files.writeString(dir.resolve("meta.json"), "{\"calls\":[" + calls + "]}");
    }

    @Test
    @DisplayName("다 만들기 전에도 여기까지 나간 값을 적는다")
    void 도중에도_적는다() throws IOException {
        writeMeta("run-1", 97, 1_041);

        after.cost("run-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UsageService.Call>> got = ArgumentCaptor.forClass(List.class);
        verify(usage).ingest(eq("run-1"), got.capture());
        assertThat(got.getValue()).extracting(UsageService.Call::costKrw)
                .containsExactly(97L, 1_041L);
    }

    @Test
    @DisplayName("작품 번호를 모르면 그냥 지나간다 — 여기서 죽으면 실패를 못 적는다")
    void 번호가_없으면_지나간다() {
        after.cost(null);
        after.cost("  ");

        verify(usage, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("적을 것이 아직 없어도 죽지 않는다")
    void 기록이_없어도_안_죽는다() {
        assertThatCode(() -> after.cost("없는작품")).doesNotThrowAnyException();
        verify(usage, never()).ingest(any(), any());
    }

    @Test
    @DisplayName("두 번 불러도 두 번 다 올린다 — 겹치는 것은 서버가 거른다")
    void 여러_번_불러도_된다() throws IOException {
        writeMeta("run-2", 500);

        after.cost("run-2");
        after.cost("run-2");

        verify(usage, org.mockito.Mockito.times(2)).ingest(eq("run-2"), any());
    }
}
