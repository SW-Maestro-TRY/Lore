package com.lore.webtoon.runs;

import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PageUploader;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.job.JobRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 편집실의 「다시 그리기」가 지키는 것들.
 *
 * <b>실제 하네스는 절대 돌리지 않는다</b> — 여기서 보는 것은 돈이 나가는
 * 호출(HarnessProcess.run)의 <b>앞뒤</b>다: 큐를 타는가, 판본을 남기는가,
 * 실패하면 원본을 지키는가, 성공하면 다시 올리는가.
 */
class RegenServiceTest {

    @TempDir
    Path runsDir;

    private PageRegenRepository regens;
    private PageStore pages;
    private BakeService bakery;
    private HarnessProcess harness;
    private PageUploader uploader;
    private JobRunner runner;
    private com.lore.webtoon.job.AfterRun after;
    private com.lore.webtoon.credit.CreditGate credits;
    private RegenService service;

    @BeforeEach
    void 세운다() throws IOException {
        regens = mock(PageRegenRepository.class);
        // 인메모리 표 흉내 — save 한 것을 findById 로 돌려준다.
        java.util.Map<String, PageRegen> table = new java.util.HashMap<>();
        when(regens.save(any())).thenAnswer(c -> {
            PageRegen one = c.getArgument(0);
            table.put(one.getId(), one);
            return one;
        });
        when(regens.findById(anyString())).thenAnswer(c -> {
            String id = c.getArgument(0);
            // 저장된 뒤 필드가 바뀌어도(move/fail) 같은 객체라 반영된다.
            return java.util.Optional.ofNullable(table.get(id));
        });

        pages = mock(PageStore.class);
        when(pages.pageNumbersOf("run-1")).thenReturn(List.of(1, 2, 3));

        bakery = mock(BakeService.class);
        harness = mock(HarnessProcess.class);
        uploader = mock(PageUploader.class);
        when(uploader.ready()).thenReturn(false);   // 이 검사에서는 S3 재업로드를 안 본다

        runner = mock(JobRunner.class);
        when(runner.runDir("run-1")).thenReturn(Files.createDirectories(runsDir.resolve("run-1")));

        after = mock(com.lore.webtoon.job.AfterRun.class);
        credits = mock(com.lore.webtoon.credit.CreditGate.class);
        service = new RegenService(regens, pages, bakery, harness, uploader, runner,
                mock(com.lore.webtoon.job.RunFiles.class), after, credits);
    }

    private void 페이지파일(int no, byte[] content) throws IOException {
        Path dir = Files.createDirectories(runsDir.resolve("run-1").resolve("pages"));
        Files.write(dir.resolve("page%02d.png".formatted(no)), content);
    }

    /** enqueue 로 넘어간 실제 작업을 그 자리에서 돌린다 — 하네스 큐가 없는 검사다. */
    private void 큐를_바로_돌린다() {
        ArgumentCaptor<Runnable> step = ArgumentCaptor.forClass(Runnable.class);
        verify(runner).enqueue(step.capture());
        step.getValue().run();
    }

    /** 컨트롤러와 같은 순서로 시작한다 — 기록을 만들고 줄에 넣는다(값은 이 검사에서 안 받는다). */
    private String 시작(String note, String quality, Long payer) {
        String id = service.queue("run-1", 1, note, quality, payer);
        service.go(id);
        return id;
    }

    @Test
    @DisplayName("없는 페이지는 시작도 안 한다")
    void 없는_페이지() {
        assertThatThrownBy(() -> service.queue("run-1", 9, "note", null, 7L))
                .isInstanceOf(NoSuchElementException.class);
        verify(runner, never()).enqueue(any(Runnable.class));
    }

    @Test
    @DisplayName("시작하면 하네스 줄에 태운다 — 다른 이미지 호출과 동시에 안 돈다")
    void 큐에_태운다() {
        String id = 시작("왼쪽이 주인공", null, null);

        verify(runner).enqueue(any(Runnable.class));
        Map<String, Object> status = service.statusOf(id);
        assertThat(status.get("status")).isEqualTo("queued");
        assertThat(status.get("note")).isEqualTo("왼쪽이 주인공");
        assertThat(status.get("page")).isEqualTo(1);
    }

    @Test
    @DisplayName("기록만 만들어서는 줄에 안 들어간다 — 값을 받은 뒤에 넣어야 받기 전에 실패하는 일이 없다(#864)")
    void 기록만으로는_안_그린다() {
        String id = service.queue("run-1", 1, "", null, 7L);

        verify(runner, never()).enqueue(any(Runnable.class));
        PageRegen one = regens.findById(id).orElseThrow();
        assertThat(one.getPaidBy()).isEqualTo(7L);
        assertThat(one.getPaidRef()).isEqualTo("regen:run-1:1:" + id);
    }

    @Test
    @DisplayName("무료(빈 장) 다시 그리기는 낸 사람을 안 적는다")
    void 무료는_안_적는다() {
        String id = service.queue("run-1", 1, "", null, null);

        PageRegen one = regens.findById(id).orElseThrow();
        assertThat(one.getPaidBy()).isNull();
        assertThat(one.getPaidRef()).isNull();
    }

    @Test
    @DisplayName("이어 그리기도 낸 사람과 그 장 장부 번호를 적는다")
    void 이어_그리기도_적는다() {
        String id = service.queuePending("run-1", 4, List.of(4, 5), 7L);

        PageRegen one = regens.findById(id).orElseThrow();
        assertThat(one.getPaidBy()).isEqualTo(7L);
        assertThat(one.getPaidRef()).isEqualTo("pages:run-1:4:" + id);
        verify(runner, never()).enqueue(any(Runnable.class));
    }

    @Test
    @DisplayName("화질을 고르면 그 화질로 그리고, 안 고르면 작품 화질 그대로다")
    void 고른_화질로_그린다() throws Exception {
        Files.writeString(runsDir.resolve("run-1").resolve("quality.txt"), "swell");
        페이지파일(1, "옛 그림".getBytes());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> env = ArgumentCaptor.forClass(Map.class);
        when(harness.run(any(), any(), env.capture(), any())).thenReturn(0);

        시작("", "surf", null);
        큐를_바로_돌린다();
        assertThat(env.getValue().get("OPENAI_IMAGE_QUALITY")).isEqualTo("medium");

        org.mockito.Mockito.clearInvocations(runner);
        시작("", null, null);
        큐를_바로_돌린다();
        assertThat(env.getValue().get("OPENAI_IMAGE_QUALITY")).isEqualTo("high");
    }

    @Test
    @DisplayName("성공하면 지금 그림을 판본으로 남기고, 다시 올리고, 구운 것을 지운다")
    void 성공하면_판본을_남긴다() throws Exception {
        페이지파일(1, "옛 그림".getBytes());
        when(harness.run(any(), any(), anyMap(), any())).thenAnswer(inv -> {
            // run.py 가 새 그림을 떨어뜨리는 것을 흉내 낸다.
            페이지파일(1, "새 그림".getBytes());
            return 0;
        });

        String id = 시작("", null, 7L);
        큐를_바로_돌린다();

        assertThat(service.statusOf(id).get("status")).isEqualTo("done");
        verify(credits, never()).refund(any(), anyString());   // 잘 그렸으면 받은 값은 그대로다
        assertThat(service.versionsOf("run-1", 1)).hasSize(1);
        // 지운 파일이 옛 그림이었는지 — 판본으로 옮겨졌는지 내용으로 확인한다.
        Path v1 = runsDir.resolve("run-1/pages/versions/page01.v1.png");
        assertThat(Files.readString(v1)).isEqualTo("옛 그림");
        verify(bakery).invalidate("run-1", 1);
        verify(after).cost("run-1");            // 다시 그린 값도 장부에(#444)
    }

    @Test
    @DisplayName("실패하면 원본을 지키고 사유를 남긴다 — 이미 낸 그림값은 안 돌려준다는 말과는 별개다")
    void 실패하면_원본을_지킨다() throws Exception {
        페이지파일(1, "지켜야 할 그림".getBytes());
        when(harness.run(any(), any(), anyMap(), any())).thenReturn(1);   // exit != 0

        String id = 시작("", null, 7L);
        큐를_바로_돌린다();

        Map<String, Object> status = service.statusOf(id);
        assertThat(status.get("status")).isEqualTo("error");
        verify(credits).refund(7L, "regen:run-1:1:" + id);   // 실패하면 받은 크레딧을 돌려준다(#626)
        assertThat(status.get("error")).isEqualTo("다시 그리지 못했습니다 — 원래 그림은 그대로입니다");
        // 실패해도 지금 그림은 판본으로 이미 떠 뒀다 — 그 자체는 해롭지 않다.
        assertThat(service.versionsOf("run-1", 1)).hasSize(1);
        verify(bakery, never()).invalidate(anyString(), eq(1));
        verify(after).cost("run-1");            // 실패해도 나간 값은 적는다(#444)
    }

    @Test
    @DisplayName("콘티(pages.json)가 있는 옛 작품은 --detail-pages 를 안 붙인다")
    void 옛_작품은_옛_방식으로() throws Exception {
        Files.writeString(runsDir.resolve("run-1/pages.json"), "[]");
        when(harness.run(any(), any(), anyMap(), any())).thenReturn(1);

        시작("", null, null);
        큐를_바로_돌린다();

        ArgumentCaptor<List<String>> args = ArgumentCaptor.forClass(List.class);
        verify(harness).run(any(), args.capture(), anyMap(), any());
        assertThat(args.getValue()).doesNotContain("--detail-pages");
    }

    @Test
    @DisplayName("서버가 뜰 때 줄에 있거나 그리던 것은 끊긴 것으로 적고, 낸 값이 있으면 돌려준다(#864)")
    void 기동_때_끊긴_것을_치운다() {
        String paid = service.queue("run-1", 1, "", null, 7L);
        String free = service.queue("run-1", 2, "", null, null);
        PageRegen running = regens.findById(paid).orElseThrow();
        running.move(RegenStatus.RUNNING, java.time.Instant.now());
        PageRegen queued = regens.findById(free).orElseThrow();
        when(regens.findByStatusIn(any())).thenReturn(List.of(running, queued));
        when(credits.refund(7L, "regen:run-1:1:" + paid)).thenReturn(3);

        service.buryInterrupted();

        verify(credits).refund(7L, "regen:run-1:1:" + paid);
        assertThat(service.statusOf(paid).get("status")).isEqualTo("error");
        assertThat(service.statusOf(paid).get("error")).isEqualTo(RegenService.INTERRUPTED + " — 크레딧은 돌려드렸어요");
        assertThat(service.statusOf(free).get("status")).isEqualTo("error");
        assertThat(service.statusOf(free).get("error")).isEqualTo(RegenService.INTERRUPTED + " — 다시 눌러 주세요");
        verify(runner, never()).enqueue(any(Runnable.class));   // 뒤늦게 그리지 않는다 — 그 사이 고친 장을 덮는다
    }

    @Test
    @DisplayName("되돌리기 — 판본 파일을 지금 자리로 복사하고, 되돌리기 전 것도 남긴다")
    void 되돌리기() throws IOException {
        페이지파일(1, "v2 (지금)".getBytes());
        Path vdir = Files.createDirectories(runsDir.resolve("run-1/pages/versions"));
        Files.writeString(vdir.resolve("page01.v1.png"), "v1 (옛날)");

        List<Map<String, Object>> after = service.revert("run-1", 1, 1);

        assertThat(Files.readString(runsDir.resolve("run-1/pages/page01.png")))
                .isEqualTo("v1 (옛날)");
        // 되돌리기 전 그림(v2)도 판본으로 남아서, 되돌린 것을 다시 되돌릴 수 있다.
        assertThat(after).extracting(row -> row.get("version")).contains(1, 2);
        verify(bakery).invalidate("run-1", 1);
    }

    @Test
    @DisplayName("없는 판본으로는 못 되돌린다")
    void 없는_판본() throws IOException {
        페이지파일(1, "지금".getBytes());
        assertThatThrownBy(() -> service.revert("run-1", 1, 9))
                .isInstanceOf(NoSuchElementException.class);
    }
}
