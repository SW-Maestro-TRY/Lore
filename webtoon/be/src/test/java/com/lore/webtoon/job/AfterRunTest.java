package com.lore.webtoon.job;

import com.lore.webtoon.art.PageStore;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    private PageUploader uploader;
    private PageStore pages;
    private HarnessProcess harness;
    private AfterRun after;

    @BeforeEach
    void setUp() {
        usage = mock(UsageService.class);
        uploader = mock(PageUploader.class);
        when(uploader.ready()).thenReturn(false);        // 여기서는 그림을 안 올린다
        pages = mock(PageStore.class);
        /* 작품이 쌓이는 자리는 HarnessProcess 하나가 정하고, AfterRun 은 그걸
           받아 쓴다. 예전에는 여기에 따로 넘겨서 둘이 다른 자리를 볼 수
           있었고, 배포에서 실제로 그랬다 — meta.json 은 멀쩡한데 없는 자리를
           보고 "비용 기록이 없습니다" 만 찍었다. */
        harness = mock(HarnessProcess.class);
        when(harness.runsDir()).thenReturn(runs);
        after = new AfterRun(usage, uploader, pages, mock(WorkLedger.class), harness);
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

    /* ---- 스스로 낫기 --------------------------------------------------------
     *
     * 다 그려 놓고 올리는 데서 실패한 작품은 결과 화면이 404 다. 게스트는
     * 주인이 될 수 없어서(uid 는 지어낼 수 있다) 되살려 달라고 할 수도 없다.
     * 그래서 여는 것만으로 낫게 한다 — 대신 **언제 움직이는지**가 정확해야
     * 한다. 결과 화면은 0.8초마다 묻는 자리다. */

    /** 그린 그림이 디스크에 있는 상태를 만든다. */
    private void 그림이_디스크에(String runId) throws IOException {
        Files.createDirectories(runs.resolve(runId).resolve("pages"));
    }

    @Test
    @DisplayName("안 적혔는데 디스크에 그림이 있으면 그 자리에서 적는다")
    void 안_적힌_것을_되살린다() throws Exception {
        그림이_디스크에("run-9");
        when(pages.has("run-9")).thenReturn(false);
        when(uploader.ready()).thenReturn(true);
        when(harness.prepareUpload(eq("run-9"), any())).thenReturn("{}");
        when(uploader.uploadPrepared(eq("run-9"), eq("{}"), any())).thenReturn(6);

        assertThat(after.healIfMissing("run-9")).isTrue();
        verify(uploader).uploadPrepared(eq("run-9"), eq("{}"), any());
    }

    @Test
    @DisplayName("이미 적혀 있으면 아무 일도 안 한다 — 폴링마다 파이썬을 띄우면 안 된다")
    void 이미_적혀_있으면_안_움직인다() throws Exception {
        그림이_디스크에("run-9");
        when(pages.has("run-9")).thenReturn(true);

        assertThat(after.healIfMissing("run-9")).isFalse();
        verify(harness, never()).prepareUpload(any(), any());
    }

    @Test
    @DisplayName("그린 그림이 없으면 손대지 않는다 — 아직 만드는 중이거나 없는 작품이다")
    void 그림이_없으면_안_움직인다() throws Exception {
        when(pages.has("아직")).thenReturn(false);        // 폴더 자체가 없다

        assertThat(after.healIfMissing("아직")).isFalse();
        verify(harness, never()).prepareUpload(any(), any());
    }

    @Test
    @DisplayName("한 번 해 보고 안 되면 다시 안 한다 — 망가진 작품에 매번 띄우지 않는다")
    void 한_번만_해_본다() throws Exception {
        그림이_디스크에("run-9");
        when(pages.has("run-9")).thenReturn(false);
        when(uploader.ready()).thenReturn(true);
        when(harness.prepareUpload(eq("run-9"), any()))
                .thenThrow(new IllegalStateException("올릴 그림을 만들지 못했습니다 (exit=1)"));

        assertThat(after.healIfMissing("run-9")).isFalse();
        assertThat(after.healIfMissing("run-9")).isFalse();
        verify(harness, org.mockito.Mockito.times(1)).prepareUpload(eq("run-9"), any());
    }

    @Test
    @DisplayName("되살리다 죽어도 화면까지 막지는 않는다")
    void 되살리기_실패가_화면을_막지_않는다() throws Exception {
        그림이_디스크에("run-9");
        when(pages.has("run-9")).thenReturn(false);
        when(uploader.ready()).thenReturn(true);
        when(harness.prepareUpload(eq("run-9"), any())).thenThrow(new IOException("파이썬 없음"));

        assertThatCode(() -> after.healIfMissing("run-9")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 작품을 동시에 열면, 되살리기가 끝날 때까지 기다린다")
    void 동시에_열면_기다린다() throws Exception {
        그림이_디스크에("run-9");
        when(pages.has("run-9")).thenReturn(false);
        when(uploader.ready()).thenReturn(true);

        /* 되살리는 동안 두 번째 요청이 들어오게 만든다. 예전에는 이 두 번째가
           "이미 해 봤다" 로 그냥 지나가 404 를 받았고, 화면은 그것을 보고
           「작품을 열지 못했습니다」를 띄웠다 — 되살리기는 곧 성공했는데도. */
        java.util.concurrent.CountDownLatch 시작함 = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch 놓아준다 = new java.util.concurrent.CountDownLatch(1);
        when(harness.prepareUpload(eq("run-9"), any())).thenAnswer(call -> {
            시작함.countDown();
            놓아준다.await();
            return "{}";
        });
        when(uploader.uploadPrepared(eq("run-9"), eq("{}"), any())).thenReturn(18);

        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var 먼저 = pool.submit(() -> after.healIfMissing("run-9"));
            assertThat(시작함.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var 나중 = pool.submit(() -> after.healIfMissing("run-9"));

            // 두 번째는 아직 답하면 안 된다 — 첫 번째가 도는 중이다.
            assertThatThrownBy(() -> 나중.get(300, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);

            놓아준다.countDown();
            assertThat(먼저.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(나중.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            놓아준다.countDown();
            pool.shutdownNow();
        }

        // 파이썬은 한 번만 띄운다.
        verify(harness, org.mockito.Mockito.times(1)).prepareUpload(eq("run-9"), any());
    }
}
