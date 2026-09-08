package com.lore.webtoon.job;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 생성 하네스(new_harness · story-harness)를 실행 가능한 자리에 풀어 놓는다.
 *
 * <h2>왜 필요한가</h2>
 *
 * 하네스는 jar 안에 리소스({@code webtoon/ai/new_harness}, {@code webtoon/ai/story-harness} —
 * {@code webtoon/be/sync-harness.sh} 가 build.gradle 의 {@code processResources} 마다
 * {@code haeun/} 원본에서 최신 사본을 만든다)로 실려 배포된다. 그런데 파이썬은 jar 속
 * 파일을 실행할 수 없다(경로가 없다). 그래서 서버가 뜰 때 임시 폴더로 꺼내 두고,
 * 그 경로를 {@link HarnessProcess} · {@code CharacterMaker} 에 넘긴다.
 *
 * new_harness 는 story-harness 를 형제 폴더로 보고 import 한다
 * ({@code STORY_HARNESS = HERE.parent / "story-harness"}) — 그래서 둘을 같은
 * 임시 폴더 아래 형제로 함께 푼다.
 *
 * (zzal 의 {@code PipelineScripts} 와 같은 방식·같은 이유다.)
 */
@Component
public class AiHarnessResources {

    private static final Logger log = LoggerFactory.getLogger(AiHarnessResources.class);

    private Path newHarnessDir;

    /**
     * ★ 서버가 뜰 때마다 <b>빈 폴더에</b> 새로 푼다.
     *
     * 고정 경로에 덮어쓰면 옛 버전의 파일 중 새 버전에 없는 것이 남아, 스크립트끼리
     * 섞인 상태로 돌 수 있다. 매번 새 폴더면 그 상태가 원리적으로 생기지 않는다.
     */
    @PostConstruct
    public void extract() throws IOException {
        Path root = Files.createTempDirectory("webtoon-ai-");
        root.toFile().deleteOnExit();

        int count = 0;
        count += extractOne(root, "new_harness");
        count += extractOne(root, "story-harness");

        newHarnessDir = root.resolve("new_harness");
        log.info("생성 하네스 {}개 파일을 {} 에 풀었습니다", count, root);
    }

    private int extractOne(Path root, String name) throws IOException {
        String prefix = "webtoon/ai/" + name + "/";
        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:webtoon/ai/" + name + "/**/*");

        int count = 0;
        for (Resource r : files) {
            String uri = r.getURI().toString();
            int at = uri.indexOf(prefix);
            if (at < 0) {
                continue;
            }
            String rel = uri.substring(at + prefix.length());
            if (rel.isBlank() || rel.endsWith("/")) {
                continue;
            }
            if (rel.contains("__pycache__") || rel.endsWith(".pyc")) {
                continue;
            }
            Path dst = root.resolve(name).resolve(rel);
            Files.createDirectories(dst.getParent());
            try (InputStream in = r.getInputStream()) {
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            count++;
        }
        return count;
    }

    /** {@code run.py} 등이 있는 자리. new_harness 가 형제로 보는 story-harness 도 옆에 같이 풀려 있다. */
    public Path newHarnessDir() {
        return newHarnessDir;
    }
}
