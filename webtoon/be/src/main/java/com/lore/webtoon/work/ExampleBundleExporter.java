package com.lore.webtoon.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.story.WebtoonStory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 이 환경에 있는 작품 하나를 <b>번들로 내보낸다</b>(#614). 다른 환경에 {@link ExampleImporter} 로 심으려는 것이다.
 *
 * <h2>무엇을 담나</h2>
 *
 * 화면에 나가는 모양(제목·장르·줄거리·장면 설명)과 사용자가 넣은 값, 쪽 그림 두 폭(320 · 1080), 그리고
 * 작품 폴더의 글·그림 파일(이야기 후보 · 시트 · 장면 · 검수). 그림은 키가 아니라 <b>내용</b>을 읽어 담는다 —
 * 받는 환경의 창고는 이 환경과 다르다.
 *
 * <h2>무엇을 안 담나</h2>
 *
 * <ul>
 *   <li>원본 사진 — 서버에 있어도 담지 않는다. 입력값의 {@code photos} 도 비운다(받는 쪽의 검사가 한 번 더 한다).</li>
 *   <li>폭 0 원본 — 다시 그릴 때만 쓰는 것이라 용량만 몇 배가 된다.</li>
 *   <li>{@code meta.json} — 호출마다의 원가·시각 기록이라 예시에 쓸모가 없고 서버 경로가 적혀 있다.</li>
 *   <li>{@code pages/} · {@code cache/} · {@code versions/} — 그림은 위에서 따로 담았다.</li>
 * </ul>
 */
@Service
public class ExampleBundleExporter {

    private static final Logger log = LoggerFactory.getLogger(ExampleBundleExporter.class);

    private static final Set<String> SKIP_DIRS = Set.of("pages", "cache", "versions");
    private static final Set<String> SKIP_FILES = Set.of("meta.json");
    private static final Set<String> RUN_EXTENSIONS = Set.of("json", "txt", "md", "png", "jpg", "jpeg", "webp");
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]+$");
    /** 캐릭터 시트와 그 이전 판({@code sheet.v1.png} …). */
    private static final Pattern SHEET = Pattern.compile("^sheet(\\.v\\d+)?\\.png$");

    private final WebtoonWorkRepository works;
    private final WebtoonJobRepository jobs;
    private final StoryStore stories;
    private final PageStore pages;
    private final PrivateArt art;
    private final HarnessProcess harness;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExampleBundleExporter(WebtoonWorkRepository works, WebtoonJobRepository jobs, StoryStore stories,
                                 PageStore pages, PrivateArt art, HarnessProcess harness) {
        this.works = works;
        this.jobs = jobs;
        this.stories = stories;
        this.pages = pages;
        this.art = art;
        this.harness = harness;
    }

    public ExampleBundle export(String runId) {
        WebtoonWork work = works.findFirstByRunId(runId)
                .filter(w -> !w.isTrashed())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 작품이 없습니다"));
        WebtoonStory story = stories.chosenOf(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이야기를 아직 안 고른 작품입니다"));

        Map<String, byte[]> run = runFilesOf(runId);
        WebtoonJob job = work.getJobId() == null ? null : jobs.findByPublicId(work.getJobId()).orElse(null);
        Map<String, Object> input = inputOf(job);

        List<ExampleBundle.Page> out = new ArrayList<>();
        for (PageStore.Entry entry : pages.entriesOf(runId)) {
            if (entry.width() != 320 && entry.width() != 1080) {
                continue;                                   // 폭 0 원본은 안 담는다
            }
            byte[] bytes = art.read(entry.key());
            if (bytes == null || bytes.length == 0) {
                throw new IllegalStateException("그림을 읽지 못했습니다: " + runId + " p" + entry.pageNo() + " w" + entry.width());
            }
            out.add(new ExampleBundle.Page(entry.pageNo(), entry.width(), bytes));
        }
        if (out.stream().noneMatch(p -> p.width() == 1080)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "1080 폭 그림이 없는 작품은 내보낼 수 없습니다");
        }

        String character = input != null && input.get("name") != null ? String.valueOf(input.get("name")) : "";
        ExampleBundle.Manifest manifest = new ExampleBundle.Manifest(
                runId, story.displayTitle(), story.getGenre() == null ? "" : story.getGenre(), character,
                job == null || job.getStyle() == null ? "" : job.getStyle(),
                loglineOf(story, run), stories.scenesOf(runId), input);
        return new ExampleBundle(manifest, out, run);
    }

    /** 줄거리. 빠르게 만든 작품은 후보의 줄거리 칸이 비어 있어서 소개글(intro)로 대신한다. */
    private String loglineOf(WebtoonStory story, Map<String, byte[]> run) {
        String plot = story.displayPlot();
        if (plot != null && !plot.isBlank()) {
            return plot;
        }
        byte[] raw = run.get("directions.json");
        if (raw == null) {
            return "";
        }
        try {
            for (JsonNode one : mapper.readTree(raw)) {
                if (one.path("n").asInt() == story.getN()) {
                    return one.path("intro").asText("").replaceAll("\\s+", " ").trim();
                }
            }
        } catch (IOException e) {
            log.warn("후보 소개를 읽지 못했습니다 (run={})", story.getRunId(), e);
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> inputOf(WebtoonJob job) {
        if (job == null || job.getInputJson() == null || job.getInputJson().isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(job.getInputJson());
            if (node instanceof ObjectNode obj) {
                obj.remove("photos");                       // 사진은 안 옮긴다
                return mapper.convertValue(obj, LinkedHashMap.class);
            }
        } catch (IOException e) {
            log.warn("입력값을 읽지 못했습니다 (job={})", job.getPublicId(), e);
        }
        return null;
    }

    /** 작품 폴더의 글·그림 파일. 작은 것부터 담아 상한 안에서 최대한 많이. */
    private Map<String, byte[]> runFilesOf(String runId) {
        Path base = harness.runsDir().resolve(runId).normalize();
        Map<String, byte[]> out = new LinkedHashMap<>();
        if (!base.startsWith(harness.runsDir().normalize()) || !Files.isDirectory(base)) {
            return out;
        }
        List<Path> candidates = new ArrayList<>();
        try (var walk = Files.walk(base, 3)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                Path rel = base.relativize(file);
                if (rel.getNameCount() > 3 || SKIP_FILES.contains(rel.getFileName().toString())) {
                    return;
                }
                for (int i = 0; i < rel.getNameCount() - 1; i++) {
                    String dir = rel.getName(i).toString();
                    if (SKIP_DIRS.contains(dir) || dir.startsWith("_")) {   // _old 같은 실험 백업
                        return;
                    }
                }
                String name = rel.getFileName().toString();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                /* 그림은 캐릭터 시트(와 이전 판) · 조연 시트만. 이어 붙인 전체 그림(episode.png)이나 쪽 그림 사본은
                   몇 MB 씩이라 번들만 키운다 — 쪽 그림은 위에서 두 폭으로 따로 담았다. */
                boolean image = Set.of("png", "jpg", "jpeg", "webp").contains(ext);
                if (image && !(SHEET.matcher(name).matches()
                        || (rel.getNameCount() == 2 && rel.getName(0).toString().equals("sheets")))) {
                    return;
                }
                boolean safe = true;
                for (Path part : rel) {
                    safe &= SAFE_NAME.matcher(part.toString()).matches();
                }
                if (safe && RUN_EXTENSIONS.contains(ext)) {
                    candidates.add(file);
                }
            });
        } catch (IOException e) {
            log.warn("작품 폴더를 읽지 못했습니다 (run={})", runId, e);
            return out;
        }
        candidates.sort(Comparator.comparingLong(this::sizeOf));
        long total = 0;
        for (Path file : candidates) {
            long size = sizeOf(file);
            if (size > ExampleBundles.MAX_RUN_FILE_BYTES || total + size > ExampleBundles.MAX_RUN_BYTES) {
                continue;
            }
            try {
                out.put(base.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                total += size;
            } catch (IOException e) {
                log.warn("파일을 못 읽어 건너뜁니다: {}", file, e);
            }
        }
        return out;
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }
}
