package com.lore.webtoon.work;

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
 * 알 필요도 없다. 예외는 내려받기 하나다 — 예시는 누구나 받을 수 있어서
 * 결과 한 편에 {@code example} 이 실린다({@link #isExample}).
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

    private static final String REPO_DIR = "webtoon/ai/assets/examples";
    private static final String CLASSPATH_DIR = "webtoon/ai/assets/examples/";

    /** 심은 작품의 주인 자리. 사람이 아니므로 계정도 브라우저도 없다. */
    /** 예시를 심은 브라우저 번호. 지우기(RunDeleteService)가 예시를 알아보는 표시이기도 하다. */
    static final String SEED_UID = "lore-example-seed";

    private final ExampleImporter importer;
    private final Path dir;
    private final boolean on;

    public ExampleWorks(ExampleImporter importer,
                        @Value("${lore.webtoon.example.works-dir:}") String worksDir,
                        @Value("${lore.webtoon.example.seed-works:true}") boolean on) {
        this.importer = importer;
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

    /**
     * 폴더 하나를 번들로 읽어 {@link ExampleImporter} 로 심는다.
     *
     * @return 이번에 심었으면 true (이미 있거나 건너뛰었으면 false)
     */
    private boolean plant(Path folder) {
        try {
            ExampleBundle bundle = ExampleBundles.fromFolder(folder);
            return importer.importBundle(bundle, false).status() == ExampleImporter.Status.PLANTED;
        } catch (Exception e) {
            // 한 작품이 잘못돼도 나머지 예시와 서버 기동은 막지 않는다.
            log.error("예시 작품을 심지 못했습니다: {}", folder, e);
            return false;
        }
    }

    /**
     * 심어 둔 예시 작품인가. 화면이 예시를 알아야 하는 자리는 하나뿐이다 — 예시는 누구나
     * 내려받을 수 있다(남의 작품은 주인만). 그래서 결과 한 편에 {@code example} 로 실어 준다.
     */
    public static boolean isExample(WebtoonWork work) {
        return work != null && (work.isExample() || SEED_UID.equals(work.getBrowserUid()));
    }
}
