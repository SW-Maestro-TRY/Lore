package com.lore.zzal.generation;

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
 * 파이프라인 스크립트를 실행 가능한 자리에 풀어 놓는다.
 *
 * ★ 왜 필요한가 — 스크립트는 jar 안에 들어가 배포된다. 그런데 파이썬은 jar 속 파일을
 *   실행할 수 없다(경로가 없다). 그래서 서버가 뜰 때 임시 폴더로 꺼내 두고, 그 경로를 넘긴다.
 *
 * ★ 스크립트를 레포에 넣은 이유 — 코드와 함께 배포되고 함께 버전이 매겨져야 하기 때문이다.
 *   원본은 실험 폴더(jakae-lab)에 그대로 있고, 여기 것은 서비스가 실행하는 사본이다.
 *   실험에서 스크립트를 고치면 여기로도 옮겨야 하며, 그때 파이프라인 버전을 올린다.
 */
@Component
public class PipelineScripts {

    private static final Logger log = LoggerFactory.getLogger(PipelineScripts.class);

    private Path root;

    /**
     * ★ 서버가 뜰 때마다 <b>빈 폴더에</b> 새로 푼다.
     *
     * 고정 경로에 덮어쓰면 옛 버전의 파일 중 새 버전에 없는 것이 남아, 스크립트끼리
     * 섞인 상태로 돌 수 있다. 매번 새 폴더면 그 상태가 원리적으로 생기지 않는다.
     */
    @PostConstruct
    public void extract() throws IOException {
        root = Files.createTempDirectory("zzal-pipeline-");
        root.toFile().deleteOnExit();

        Resource[] files = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:zzal/pipeline/**/*");

        int count = 0;
        for (Resource r : files) {
            String uri = r.getURI().toString();
            int at = uri.indexOf("zzal/pipeline/");
            if (at < 0) {
                continue;
            }
            String rel = uri.substring(at + "zzal/pipeline/".length());
            if (rel.isBlank() || rel.endsWith("/")) {
                continue;
            }
            // ★ 파이썬이 만들어 둔 캐시(.pyc)는 풀지 않는다. 새 버전을 올렸을 때 옛 캐시가
            //   섞여 들어가면, 스크립트는 v2 인데 실행은 v1 로 되는 상황이 생길 수 있다.
            //   캐시는 파이썬이 실행 시 알아서 다시 만든다.
            if (rel.contains("__pycache__") || rel.endsWith(".pyc")) {
                continue;
            }
            Path dst = root.resolve(rel);
            Files.createDirectories(dst.getParent());
            try (InputStream in = r.getInputStream()) {
                Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            count++;
        }
        log.info("파이프라인 스크립트 {}개를 {} 에 풀었습니다", count, root);
    }

    /** 해당 버전의 스크립트 경로. 예: script("v1", "service_post.py") */
    public String script(String version, String name) {
        Path p = root.resolve(version).resolve(name);
        if (!Files.exists(p)) {
            // 설정이 원인일 때는 무엇이 없는지 그대로 말해 준다.
            throw new IllegalStateException("파이프라인 스크립트가 없습니다: " + p);
        }
        return p.toString();
    }
}
