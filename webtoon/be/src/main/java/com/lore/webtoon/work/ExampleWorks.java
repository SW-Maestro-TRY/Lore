package com.lore.webtoon.work;

import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.job.WebtoonJob;
import com.lore.webtoon.job.WebtoonJobRepository;
import com.lore.webtoon.story.StoryStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 둘러보기에 처음부터 놓여 있는 예시 작품들.
 *
 * <h2>왜 있어야 하나</h2>
 *
 * 둘러보기가 비어 있으면 <b>무엇이 나오는 곳인지 알 수 없다.</b> 한 편을
 * 만들려면 값이 드는데, 무엇이 나올지 모르는 채로 내라는 것이 된다.
 *
 * <h2>왜 DB 에 심나 — 정적 파일로 두면 안 되나</h2>
 *
 * 전에는 예시 작품이 화면 쪽 정적 파일이었다({@code fe/static/gallery} 와
 * {@code runs.json}). 그래서 같은 "작품" 이 <b>두 갈래</b>로 존재했다 —
 * 진짜 작품은 DB, 예시는 파일. 화면은 그 둘을 {@code example} 값으로 갈라
 * 그리고, 예시 작품 번호를 코드에 직접 적은 자리가 생기고, 예시를 하나 빼면
 * 그 자리가 조용히 빈칸이 됐다.
 *
 * 지금은 예시도 <b>보통 작품과 똑같이 DB 에 있다.</b> 화면은 예시인지 모르고,
 * 알 필요도 없다.
 *
 * <h2>심는 것은 한 작품에 한 번뿐이다</h2>
 *
 * 그 작품의 그림이 이미 적혀 있으면({@code webtoon_page}) 건너뛴다. 그래서
 * 서버를 다시 띄워도 늘지 않고, <b>심은 뒤 DB 에서 제목이나 공개 여부를
 * 고쳐도 되돌아가지 않는다.</b> 예시를 빼고 싶으면 비공개로 돌리거나 DB 에서
 * 지운다 — 여기 폴더를 지우는 것으로는 안 없어진다(고친 것을 기동할 때마다
 * 덮어쓰지 않으려고 일부러 그렇게 뒀다).
 *
 * <h2>그림은 어디로 가나</h2>
 *
 * 저장소에 둔 그림을 <b>그 환경의 창고에 올린다</b> — 노트북과 dev 는 MinIO,
 * staging·prod 는 각자의 S3 다. {@link PrivateArt} 를 거치므로 이 클래스는
 * 어느 쪽인지 몰라도 된다. 넣는 법은 {@code webtoon/docs/images.md} 에 있다.
 */
@Component
public class ExampleWorks implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExampleWorks.class);

    /** {@code p03-w1080.jpg} 에서 쪽 번호와 폭을 읽는다. */
    private static final Pattern PAGE_FILE = Pattern.compile("^p(\\d+)-w(\\d+)\\.(jpg|jpeg|png|webp)$");

    private static final String REPO_DIR = "webtoon/ai/assets/examples";
    private static final String CLASSPATH_DIR = "webtoon/ai/assets/examples/";

    /** 심은 작품의 주인 자리. 사람이 아니므로 계정도 브라우저도 없다. */
    private static final String SEED_UID = "lore-example-seed";

    private final PrivateArt art;
    private final PageStore pages;
    private final StoryStore stories;
    private final WorkLedger ledger;
    private final WebtoonJobRepository jobs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dir;
    private final boolean on;

    public ExampleWorks(PrivateArt art, PageStore pages, StoryStore stories, WorkLedger ledger,
                        WebtoonJobRepository jobs,
                        @Value("${lore.webtoon.example.works-dir:}") String worksDir,
                        @Value("${lore.webtoon.example.seed-works:true}") boolean on) {
        this.art = art;
        this.pages = pages;
        this.stories = stories;
        this.ledger = ledger;
        this.jobs = jobs;
        this.dir = resolveDir(worksDir);
        this.on = on;
    }

    /** {@link com.lore.webtoon.character.BuiltinCharacters} 와 같은 순서로 찾는다. */
    private static Path resolveDir(String given) {
        if (given != null && !given.isBlank()) {
            return Path.of(given).toAbsolutePath().normalize();
        }
        Path repo = Path.of(REPO_DIR).toAbsolutePath().normalize();
        if (Files.isDirectory(repo)) {
            return repo;
        }
        return extractFromClasspath();
    }

    /** 배포 서버에는 저장소가 없다 — jar 리소스를 임시 폴더로 푼다. */
    private static Path extractFromClasspath() {
        try {
            Path root = Files.createTempDirectory("webtoon-examples-");
            root.toFile().deleteOnExit();
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + CLASSPATH_DIR + "**/*");
            int count = 0;
            for (Resource r : files) {
                String uri = r.getURI().toString();
                int at = uri.indexOf(CLASSPATH_DIR);
                if (at < 0) {
                    continue;
                }
                String rel = uri.substring(at + CLASSPATH_DIR.length());
                if (rel.isBlank() || rel.endsWith("/")) {
                    continue;
                }
                Path dst = root.resolve(rel);
                Files.createDirectories(dst.getParent());
                try (InputStream in = r.getInputStream()) {
                    Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
            log.info("예시 작품 파일 {}개를 jar 리소스에서 {} 에 풀었습니다", count, root);
            return root;
        } catch (IOException e) {
            log.error("예시 작품을 jar 리소스에서 풀지 못했습니다", e);
            return Path.of(REPO_DIR).toAbsolutePath().normalize();
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!on) {
            return;
        }
        if (!Files.isDirectory(dir)) {
            log.warn("예시 작품 폴더가 없습니다: {}", dir);
            return;
        }
        int planted = 0;
        try (var found = Files.list(dir)) {
            for (Path one : found.filter(Files::isDirectory).sorted().toList()) {
                if (plant(one)) {
                    planted++;
                }
            }
        } catch (IOException e) {
            log.error("예시 작품 폴더를 읽지 못했습니다: {}", dir, e);
            return;
        }
        if (planted > 0) {
            log.info("예시 작품 {}편을 심었습니다", planted);
        }
    }

    /** @return 이번에 심었으면 true (이미 있거나 건너뛰었으면 false) */
    private boolean plant(Path folder) {
        String runId = folder.getFileName().toString();
        Path metaFile = folder.resolve("meta.json");
        if (!Files.isRegularFile(metaFile)) {
            log.warn("meta.json 이 없어 건너뜁니다: {}", folder);
            return false;
        }
        if (pages.has(runId)) {
            return false;               // 이미 심었다 — 다시 덮지 않는다
        }
        JsonNode meta;
        try {
            meta = mapper.readTree(Files.readString(metaFile));
        } catch (IOException e) {
            log.error("meta.json 을 읽지 못했습니다: {}", metaFile, e);
            return false;
        }

        List<PageStore.Upload> uploads = new ArrayList<>();
        try (var found = Files.list(folder)) {
            for (Path file : found.sorted().toList()) {
                Matcher m = PAGE_FILE.matcher(file.getFileName().toString());
                if (!m.matches()) {
                    continue;           // meta.json 등
                }
                byte[] bytes = Files.readAllBytes(file);
                /* 공개 자리에 올린다 — 예시는 누구나 본다. 키는 PrivateArt 가
                   짓고, 어느 창고인지는 환경이 정한다. */
                String key = art.upload(bytes, typeOf(file.getFileName().toString()), true);
                if (key == null || key.isBlank()) {
                    log.error("예시 그림을 못 올렸습니다 — 이 작품은 건너뜁니다: {}", file);
                    return false;
                }
                uploads.add(new PageStore.Upload(
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), key, bytes.length));
            }
        } catch (IOException e) {
            log.error("예시 그림을 읽지 못했습니다: {}", folder, e);
            return false;
        }
        if (uploads.isEmpty()) {
            log.warn("예시 그림이 한 장도 없습니다: {}", folder);
            return false;
        }
        pages.record(runId, uploads, true);

        /* 이야기 — 후보 하나만 적고 그것을 고른 것으로 둔다. 예시는 고르는
           과정을 거치지 않았지만, 화면이 「고른 이야기」에서 제목·장르·
           로그라인·컷 설명을 읽으므로 그 자리를 채워야 한다. */
        Map<String, Object> story = new LinkedHashMap<>();
        story.put("n", 1);
        story.put("title", meta.path("title").asText(""));
        story.put("genre", meta.path("genre").asText(""));
        story.put("plot", meta.path("logline").asText(""));
        story.put("scenes", mapper.convertValue(meta.path("captions"), List.class));
        stories.save(runId, List.of(story));
        stories.choose(runId, 1);

        /* 작업 줄 — 카드에 캐릭터 이름과 그림체 이름이 뜨려면 있어야 한다.
           예시는 실제로 돈 작업이 아니라서 상태 값은 뜻이 없다(읽는 쪽이
           상태를 안 본다). */
        String jobId = jobIdOf(runId);
        if (jobs.findByPublicId(jobId).isEmpty()) {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("name", meta.path("character").asText(""));
            String inputJson;
            try {
                inputJson = mapper.writeValueAsString(input);
            } catch (IOException e) {
                inputJson = "{}";
            }
            jobs.save(WebtoonJob.queued(jobId, null, SEED_UID, null,
                    meta.path("style").asText(""), null, false, inputJson, Instant.now()));
        }

        ledger.started(jobId, null, SEED_UID);
        ledger.learnedRun(jobId, runId, null);
        ledger.setPublic(runId, true);
        return true;
    }

    /** 작품 번호에서 곧장 짓는다 — 다시 띄워도 같은 값이라 줄이 늘지 않는다. */
    private static String jobIdOf(String runId) {
        return "example-" + runId;
    }

    private static String typeOf(String file) {
        String lower = file.toLowerCase();
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
