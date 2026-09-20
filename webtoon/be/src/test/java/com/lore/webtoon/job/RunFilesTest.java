package com.lore.webtoon.job;

import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 작품 폴더를 치우는 자리.
 *
 * <b>이 검사가 지키는 것은 「지우면 안 되는 것을 안 지우는가」 다.</b> 그림은
 * 사람이 돈을 내고 만든 것이고, 서버에서 지우면 S3 에 없는 한 되돌릴 방법이
 * 없다. 그래서 지우는 조건 하나하나를 여기서 못 박는다.
 */
class RunFilesTest {

    @TempDir
    Path runs;

    private PageStore pages;
    private S3Storage storage;

    @BeforeEach
    void setUp() {
        pages = mock(PageStore.class);
        storage = mock(S3Storage.class);
    }

    private RunFiles files(String bucket) {
        HarnessProcess harness = mock(HarnessProcess.class);
        when(harness.runsDir()).thenReturn(runs);
        return new RunFiles(harness, pages, storage, bucket);
    }

    /** 다 그려진 작품 하나를 디스크에 만든다. */
    private Path 작품(String runId, int pageCount) throws IOException {
        Path d = Files.createDirectories(runs.resolve(runId));
        Path p = Files.createDirectories(d.resolve("pages"));
        Path c = Files.createDirectories(d.resolve("cache"));
        for (int i = 1; i <= pageCount; i++) {
            Files.writeString(p.resolve(String.format("page%02d.png", i)), "그림".repeat(100));
            Files.writeString(p.resolve(String.format("page%02d.txt", i)), "프롬프트");
            Files.writeString(c.resolve("s3_p" + i + "_w320.jpg"), "작은그림");
        }
        Files.writeString(d.resolve("episode.png"), "한편".repeat(500));
        Files.writeString(d.resolve("meta.json"), "{\"calls\":[]}");
        Files.writeString(d.resolve("story.md"), "이야기");
        return d;
    }

    /** 이 작품의 원본이 S3 에 적혀 있다고 세운다. */
    private void 올라가_있다(String runId, int pageCount) {
        Map<Integer, String> keys = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= pageCount; i++) {
            keys.put(i, "images/webtoon/key" + i);
        }
        when(pages.originalKeys(runId)).thenReturn(keys);
    }

    @Test
    @DisplayName("올린 뒤 그림은 치우고, 글과 JSON 은 남긴다")
    void 올린_뒤_그림만_치운다() throws IOException {
        Path d = 작품("run-1", 6);
        올라가_있다("run-1", 6);

        long freed = files("lore-content").sweepUploaded("run-1");

        assertThat(freed).isPositive();
        assertThat(d.resolve("pages/page01.png")).doesNotExist();
        assertThat(d.resolve("episode.png")).doesNotExist();
        assertThat(d.resolve("cache/s3_p1_w320.jpg")).doesNotExist();

        /* 원가 분석의 원천 자료다 — 어느 단계에서 얼마가 나갔는지는 여기에만
           있고, 다 합쳐도 작다. */
        assertThat(d.resolve("meta.json")).exists();
        assertThat(d.resolve("story.md")).exists();
        assertThat(d.resolve("pages/page01.txt")).exists();
    }

    @Test
    @DisplayName("S3 에 안 적혀 있으면 안 지운다 — 지우면 되돌릴 곳이 없다")
    void 안_올라갔으면_안_지운다() throws IOException {
        Path d = 작품("run-2", 6);
        when(pages.originalKeys("run-2")).thenReturn(Map.of());   // 올리기가 실패했다

        assertThat(files("lore-content").sweepUploaded("run-2")).isZero();
        assertThat(d.resolve("pages/page01.png")).exists();
        assertThat(d.resolve("episode.png")).exists();
    }

    @Test
    @DisplayName("버킷이 없으면(로컬) 아무것도 안 지운다 — 서버 사본이 유일본이다")
    void 로컬에서는_안_지운다() throws IOException {
        Path d = 작품("run-3", 6);
        올라가_있다("run-3", 6);

        assertThat(files("").sweepUploaded("run-3")).isZero();
        assertThat(d.resolve("pages/page01.png")).exists();
        assertThat(d.resolve("episode.png")).exists();
    }

    @Test
    @DisplayName("다시 그리기 전에 참조할 그림을 S3 에서 되살린다")
    void 참조할_그림을_되살린다() throws IOException {
        Files.createDirectories(runs.resolve("run-4"));
        올라가_있다("run-4", 6);

        files("lore-content").restore("run-4", 3);

        // 고칠 장과 그 앞 장들만. 뒷장은 참조에 안 쓰이므로 안 받는다.
        verify(storage).download(eq("images/webtoon/key1"), any());
        verify(storage).download(eq("images/webtoon/key2"), any());
        verify(storage).download(eq("images/webtoon/key3"), any());
        verify(storage, never()).download(eq("images/webtoon/key4"), any());
    }

    @Test
    @DisplayName("이미 디스크에 있으면 다시 안 받는다")
    void 있으면_안_받는다() throws IOException {
        작품("run-5", 6);
        올라가_있다("run-5", 6);

        files("lore-content").restore("run-5", 3);

        verify(storage, never()).download(anyString(), any());
    }

    @Test
    @DisplayName("끝까지 못 간 작품은 7일 뒤 폴더째 치운다")
    void 못_간_작품을_치운다() throws IOException {
        Path old = 작품("run-old", 2);
        when(pages.originalKeys("run-old")).thenReturn(Map.of());     // 한 장도 안 적혔다
        Files.setLastModifiedTime(old,
                FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)));

        files("lore-content").sweepStale();

        assertThat(old).doesNotExist();
    }

    @Test
    @DisplayName("아직 7일이 안 됐으면 둔다 — 무슨 일이었는지 볼 시간이 필요하다")
    void 새_것은_둔다() throws IOException {
        Path fresh = 작품("run-fresh", 2);
        when(pages.originalKeys("run-fresh")).thenReturn(Map.of());

        files("lore-content").sweepStale();

        assertThat(fresh).exists();
    }

    @Test
    @DisplayName("끝까지 간 작품은 오래돼도 안 치운다")
    void 완성본은_안_치운다() throws IOException {
        Path done = 작품("run-done", 6);
        올라가_있다("run-done", 6);
        Files.setLastModifiedTime(done,
                FileTime.from(Instant.now().minus(400, ChronoUnit.DAYS)));

        files("lore-content").sweepStale();

        assertThat(done).exists();
        assertThat(done.resolve("meta.json")).exists();
    }
}
