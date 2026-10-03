package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.job.WebtoonQuality;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 작품을 번들로 내보낸다(#614). 다른 환경에 심으려는 것이라 <b>키가 아니라 내용</b>을 담고, 사진·원가 기록·
 * 서버 경로 같은 것은 안 담는다. 내보낸 것을 {@link ExampleBundles} 가 그대로 읽는지(왕복)도 본다.
 */
class ExampleBundleExporterTest {

    static final String RUN = ExampleBundlesTest.RUN;

    @TempDir
    Path tmp;

    private WebtoonWorkRepository works;
    private ExampleBundleExporter exporter;
    private PageStore pages;

    @BeforeEach
    void 세운다() throws Exception {
        works = mock(WebtoonWorkRepository.class);
        WebtoonJobRepository jobs = mock(WebtoonJobRepository.class);
        StoryStore stories = mock(StoryStore.class);
        pages = mock(PageStore.class);
        PrivateArt art = mock(PrivateArt.class);
        HarnessProcess harness = mock(HarnessProcess.class);
        when(harness.runsDir()).thenReturn(tmp.resolve("runs"));

        WebtoonWork work = mock(WebtoonWork.class);
        when(work.getJobId()).thenReturn("job-1");
        when(work.isTrashed()).thenReturn(false);
        when(works.findFirstByRunId(RUN)).thenReturn(Optional.of(work));

        // 빠르게 만든 작품이라 줄거리(plot)가 비어 있다 — 소개글(intro)로 대신해야 한다.
        WebtoonStory story = WebtoonStory.of(RUN, 3, "카페 사장에게는 비밀이 많다", "느와르", "", "[\"둘째 장\"]", "[]", Instant.now());
        when(stories.chosenOf(RUN)).thenReturn(Optional.of(story));
        when(stories.scenesOf(RUN)).thenReturn(List.of("둘째 장"));

        WebtoonJob job = WebtoonJob.queued("job-1", null, "uid", null, "frost", WebtoonQuality.DEFAULT_QUALITY, "ko",
                false, "{\"name\":\"레나\",\"character\":\"조폭 보스\",\"photos\":2}", Instant.now());
        when(jobs.findByPublicId("job-1")).thenReturn(Optional.of(job));

        when(pages.entriesOf(RUN)).thenReturn(List.of(
                new PageStore.Entry(1, 0, "k0", 3_000_000), new PageStore.Entry(1, 320, "k320", 50_000),
                new PageStore.Entry(1, 1080, "k1080", 450_000), new PageStore.Entry(2, 1080, "k2-1080", 450_000)));
        when(art.read(anyString())).thenAnswer(i -> ExampleBundlesTest.jpeg());

        Path run = Files.createDirectories(tmp.resolve("runs").resolve(RUN));
        Files.writeString(run.resolve("scenes.json"), "{\"scenes\":[]}");
        Files.writeString(run.resolve("directions.json"),
                "[{\"n\":1,\"intro\":\"다른 후보\"},{\"n\":3,\"intro\":\"조폭   보스 레나는\\n카페에 들른다\"}]");
        Files.writeString(run.resolve("pick.json"), "{\"n\":3}");
        Files.writeString(run.resolve("input.json"), "{\"name\":\"레나\",\"photos\":[\"/opt/lore/work/jobs/x/photo1.png\"]}");
        Files.writeString(run.resolve("meta.json"), "{\"/opt/lore/webtoon\":1}");           // 원가 기록 — 안 담는다
        Files.write(run.resolve("sheet.png"), new byte[]{1, 2, 3});
        Files.writeString(run.resolve("run.sh"), "echo");                                     // 허용 안 되는 종류
        Files.writeString(run.resolve("한글이름.txt"), "안전하지 않은 이름");
        Files.createDirectories(run.resolve("pages"));
        Files.write(run.resolve("pages").resolve("p01.png"), new byte[]{1});
        Files.createDirectories(run.resolve("cache"));
        Files.writeString(run.resolve("cache").resolve("c.json"), "{}");
        Files.write(run.resolve("huge.txt"), new byte[13 * 1024 * 1024]);                     // 파일 상한 초과

        exporter = new ExampleBundleExporter(works, jobs, stories, pages, art, harness);
    }

    @Test
    @DisplayName("그림은 폭 320·1080 만 담는다 — 폭 0 원본은 용량만 키운다")
    void 폭_0은_안_담는다() {
        ExampleBundle got = exporter.export(RUN);
        assertThat(got.pages()).extracting(p -> p.pageNo() + ":" + p.width())
                .containsExactlyInAnyOrder("1:320", "1:1080", "2:1080");
    }

    @Test
    @DisplayName("화면에 나가는 값과 사용자가 넣은 값을 담는다 — 줄거리가 비면 후보 소개글로, 사진은 뺀다")
    void 설명과_입력값() {
        ExampleBundle.Manifest m = exporter.export(RUN).manifest();
        assertThat(m.title()).isEqualTo("카페 사장에게는 비밀이 많다");
        assertThat(m.genre()).isEqualTo("느와르");
        assertThat(m.character()).isEqualTo("레나");
        assertThat(m.style()).isEqualTo("frost");
        assertThat(m.captions()).containsExactly("둘째 장");
        assertThat(m.logline()).isEqualTo("조폭 보스 레나는 카페에 들른다");           // 고른 3번 후보의 소개글, 공백 정리
        assertThat(m.input()).containsEntry("character", "조폭 보스").doesNotContainKey("photos");
    }

    @Test
    @DisplayName("작품 폴더는 글·그림만, 원가 기록·스크립트·안전하지 않은 이름·pages·cache·너무 큰 파일은 뺀다")
    void 작품_폴더_걸러내기() {
        ExampleBundle got = exporter.export(RUN);
        assertThat(got.runFiles()).containsKeys("scenes.json", "directions.json", "pick.json", "input.json", "sheet.png");
        assertThat(got.runFiles()).doesNotContainKeys("meta.json", "run.sh", "한글이름.txt", "huge.txt");
        assertThat(got.runFiles().keySet()).noneMatch(k -> k.startsWith("pages/") || k.startsWith("cache/"));
    }

    @Test
    @DisplayName("내보낸 번들을 zip 으로 쓰고 다시 읽으면 같다 — 사진 경로는 비워서")
    void 왕복() throws Exception {
        ExampleBundle out = exporter.export(RUN);
        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        ExampleBundles.writeZip(out, zip);
        Path file = tmp.resolve("rt.zip");
        Files.write(file, zip.toByteArray());

        ExampleBundle back = ExampleBundles.fromZip(file);

        assertThat(back.manifest().runId()).isEqualTo(RUN);
        assertThat(back.manifest().title()).isEqualTo(out.manifest().title());
        assertThat(back.manifest().logline()).isEqualTo(out.manifest().logline());
        assertThat(back.pages()).hasSameSizeAs(out.pages());
        assertThat(back.runFiles().keySet()).isEqualTo(out.runFiles().keySet());
        assertThat(new String(back.runFiles().get("input.json"), StandardCharsets.UTF_8)).doesNotContain("/opt/lore");
    }

    @Test
    @DisplayName("없는 작품 · 1080 폭 그림이 없는 작품은 내보낼 수 없다")
    void 내보낼_수_없는_작품() {
        assertThatThrownBy(() -> exporter.export("없는-작품-번호")).isInstanceOf(BusinessException.class);

        when(pages.entriesOf(RUN)).thenReturn(List.of(new PageStore.Entry(1, 320, "k320", 1)));
        assertThatThrownBy(() -> exporter.export(RUN)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("1080");
    }
}
