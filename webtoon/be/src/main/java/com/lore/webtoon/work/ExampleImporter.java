package com.lore.webtoon.work;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.story.StoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 번들({@link ExampleBundle})을 <b>이 환경에</b> 심는다(#614).
 *
 * <h2>부팅 시드와 관리자 API 가 같은 코드를 쓴다</h2>
 *
 * 전에는 {@link ExampleWorks} 가 저장소 폴더를 읽어 직접 심었다. 이제 폴더든 zip 이든 {@link ExampleBundle}
 * 로 바꾼 뒤 이 클래스가 심는다 — 검증·순서·멱등이 한 곳이고, 테스트도 한 곳이다.
 *
 * <h2>그림은 새 키로 이 환경의 창고에 올린다</h2>
 *
 * {@link PrivateArt#upload} 가 이 환경이 어느 창고인지(로컬·dev 는 MinIO, staging·운영은 각자의 S3) 알아서
 * 정한다. 번들에는 키가 없으므로 환경이 달라도 DB 와 S3 가 항상 그 환경 안에서 맞는다.
 *
 * <h2>같은 작품은 한 번뿐이다 — 마지막에 표시한다</h2>
 *
 * 「이미 심었나」는 그 작품의 그림이 적혀 있는지({@code webtoon_page})로 안다. 그래서 <b>그림 기록을 맨
 * 마지막에</b> 적는다. 중간에 죽으면 표시가 없으므로 다시 돌렸을 때 이어서 하고, 이미 적힌 이야기·작업은
 * 각자 건너뛴다. 올려 둔 그림 파일은 고아가 될 수 있지만 작아서 그대로 둔다.
 *
 * 심은 뒤 DB 에서 고친 것(제목·공개 여부)은 다시 심을 때 되돌리지 않는다.
 */
@Service
public class ExampleImporter {

    private static final Logger log = LoggerFactory.getLogger(ExampleImporter.class);

    /** 예시를 심은 브라우저 번호. 지우기(RunDeleteService)가 예시를 알아보는 표시이기도 하다. */
    static final String SEED_UID = ExampleWorks.SEED_UID;

    public enum Status {
        /** 새로 심었다. */
        PLANTED,
        /** 이미 있어서 건드리지 않았다. */
        EXISTS,
        /** 검사만 했다. */
        DRY_RUN
    }

    /** @param runFolderRestored 서버 작업 폴더에 만든 과정을 풀었는가 */
    public record Result(String runId, String title, Status status, int pages, boolean runFolderRestored) {
    }

    private final PrivateArt art;
    private final PageStore pages;
    private final StoryStore stories;
    private final WorkLedger ledger;
    private final WebtoonJobRepository jobs;
    private final HarnessProcess harness;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExampleImporter(PrivateArt art, PageStore pages, StoryStore stories, WorkLedger ledger,
                           WebtoonJobRepository jobs, HarnessProcess harness) {
        this.art = art;
        this.pages = pages;
        this.stories = stories;
        this.ledger = ledger;
        this.jobs = jobs;
        this.harness = harness;
    }

    /**
     * @param dryRun 참이면 아무것도 쓰지 않고 심을 수 있는지만 본다
     */
    public Result importBundle(ExampleBundle bundle, boolean dryRun) {
        ExampleBundle.Manifest m = bundle.manifest();
        String runId = m.runId();
        int pageCount = (int) bundle.pages().stream().map(ExampleBundle.Page::pageNo).distinct().count();

        if (dryRun) {
            return new Result(runId, m.title(), pages.has(runId) ? Status.EXISTS : Status.DRY_RUN, pageCount, false);
        }

        /* 만든 과정(작품 폴더)은 그림과 따로 챙긴다 — 서버의 작업 폴더가 비어 있으면(새 서버 · 볼륨을 비운 뒤)
           그림이 이미 심겨 있어도 다시 풀어 둔다. */
        boolean restored = restoreRunFolder(runId, bundle.runFiles());

        /* 이미 있는 작품은 <b>예시 표시도 건드리지 않는다.</b> 같은 작품 번호의 작품이 이미 있다는 것은 누군가의 작품일
           수 있다(로컬에서 만든 작품이 예시 폴더와 같은 번호인 경우가 실제로 있었다). 말없이 예시로 바꾸면 공개되고
           주인이 못 지우게 된다. 예시 지정은 관리자가 PATCH 로 명시적으로 한다. */
        if (pages.has(runId)) {
            return new Result(runId, m.title(), Status.EXISTS, pageCount, restored);
        }

        /* 1. 그림을 이 환경의 창고에 올린다. 공개 자리 — 예시는 누구나 본다. */
        List<PageStore.Upload> uploads = new ArrayList<>();
        for (ExampleBundle.Page page : bundle.pages()) {
            String key = art.upload(page.bytes(), "image/jpeg", true);
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("예시 그림을 올리지 못했습니다: " + runId + " p" + page.pageNo());
            }
            uploads.add(new PageStore.Upload(page.pageNo(), page.width(), key, page.bytes().length));
        }

        /* 2. 이야기. 만든 과정에 후보 넷이 있으면 그대로 적고, 없으면 후보 하나만 적어 고른 것으로 둔다 —
           화면이 「고른 이야기」에서 제목·장르·로그라인·컷 설명을 읽으므로 어느 쪽이든 그 자리는 채워야 한다. */
        List<Object> candidates = candidatesOf(bundle.runFiles());
        if (candidates != null) {
            stories.save(runId, candidates);
            stories.choose(runId, pickedOf(bundle.runFiles()));
            if (!m.captions().isEmpty()) {
                stories.setScenes(runId, m.captions());           // 고른 후보의 장면 줄 — 편집실이 읽는다
            }
        } else {
            Map<String, Object> story = new LinkedHashMap<>();
            story.put("n", 1);
            story.put("title", m.title());
            story.put("genre", m.genre());
            story.put("plot", m.logline());
            story.put("scenes", m.captions());
            stories.save(runId, List.of(story));
            stories.choose(runId, 1);
        }

        /* 3. 작업 줄 — 카드에 캐릭터 이름과 그림체 이름이 뜨려면 있어야 한다. 예시는 실제로 돈 작업이 아니라서
           끝난 것(DONE)으로 넣는다 — QUEUED 로 넣으면 줄에 영영 서 있다(WebtoonJob.seeded). */
        String jobId = jobIdOf(runId);
        if (jobs.findByPublicId(jobId).isEmpty()) {
            jobs.save(WebtoonJob.seeded(jobId, SEED_UID, runId, m.style(), inputJsonOf(m), Instant.now()));
        }
        ledger.started(jobId, null, SEED_UID);
        ledger.learnedRun(jobId, runId, null);
        ledger.setPublic(runId, true);
        ledger.markExample(runId, true, null);

        /* 4. 맨 마지막 — 「이미 심었다」 표시. */
        pages.record(runId, uploads, true);
        log.info("예시 작품을 심었습니다 (run={}, 제목={}, 그림 {}장)", runId, m.title(), uploads.size());
        return new Result(runId, m.title(), Status.PLANTED, pageCount, restored);
    }

    /** 사용자가 실제로 넣은 값이 있으면 그대로, 없으면 캐릭터 이름만. */
    private String inputJsonOf(ExampleBundle.Manifest m) {
        Object input = m.input() != null ? m.input() : Map.of("name", m.character() == null ? "" : m.character());
        try {
            return mapper.writeValueAsString(input);
        } catch (IOException e) {
            return "{}";
        }
    }

    /** 만들 때 나왔던 이야기 후보들. 없으면 null — 줄거리가 빈 후보는 소개글(intro)로 채운다. */
    private List<Object> candidatesOf(Map<String, byte[]> run) {
        byte[] raw = run.get("directions.json");
        if (raw == null) {
            return null;
        }
        try {
            JsonNode all = mapper.readTree(raw);
            if (!all.isArray() || all.isEmpty()) {
                return null;
            }
            List<Object> out = new ArrayList<>();
            for (JsonNode one : all) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = mapper.convertValue(one, LinkedHashMap.class);
                Object plot = map.get("plot");
                if (plot == null || plot.toString().isBlank()) {
                    map.put("plot", String.valueOf(map.getOrDefault("intro", "")).replaceAll("\\s+", " ").trim());
                }
                out.add(map);
            }
            return out;
        } catch (IOException e) {
            log.warn("예시의 이야기 후보를 읽지 못했습니다", e);
            return null;
        }
    }

    /** 고른 후보 번호({@code pick.json}). 모르면 1. */
    private int pickedOf(Map<String, byte[]> run) {
        byte[] raw = run.get("pick.json");
        if (raw == null) {
            return 1;
        }
        try {
            return mapper.readTree(raw).path("n").asInt(1);
        } catch (IOException e) {
            return 1;
        }
    }

    /**
     * 만든 과정을 서버의 작업 폴더로 푼다. 이미 있으면 덮지 않는다 — 심은 뒤 고친 것이 되돌아가면 안 된다.
     * 못 풀어도 예시는 심는다(그림이 본체다).
     */
    private boolean restoreRunFolder(String runId, Map<String, byte[]> files) {
        if (files == null || files.isEmpty()) {
            return false;
        }
        Path to = harness.runsDir().resolve(runId).normalize();
        if (!to.startsWith(harness.runsDir().normalize()) || Files.exists(to)) {
            return false;
        }
        try {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Path dst = to.resolve(file.getKey()).normalize();
                if (!dst.startsWith(to)) {
                    throw new IOException("작업 폴더 밖으로 나가는 경로: " + file.getKey());   // 번들 검사가 이미 막는다
                }
                Files.createDirectories(dst.getParent());
                Files.write(dst, file.getValue());
            }
            log.info("예시 작품 폴더를 작업 폴더에 풀었습니다: {}", to);
            return true;
        } catch (IOException e) {
            log.warn("예시 작품 폴더를 풀지 못했습니다: {}", runId, e);
            return false;
        }
    }

    /** 작품 번호에서 곧장 짓는다 — 다시 띄워도 같은 값이라 줄이 늘지 않는다. */
    static String jobIdOf(String runId) {
        return "example-" + runId;
    }
}
