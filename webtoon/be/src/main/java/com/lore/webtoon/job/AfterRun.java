package com.lore.webtoon.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.PageStore;
import com.lore.webtoon.UsageService;
import com.lore.webtoon.WorkLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 다 그린 뒤에 남길 것 — 비용과 그림.
 *
 * <h2>왜 따로 있나</h2>
 *
 * 파이썬 서버는 이 둘을 자기가 했다({@code usage_report.push} ·
 * {@code s3_upload.publish}). 스프링이 직접 만들기 시작하면서 그 길을 안
 * 지나가게 됐고, 그래서 <b>비용이 파일에만 남고 그림은 하네스 디스크에만
 * 남았다.</b> 실측으로 확인했다 — 한 편(17콜 1,138원)을 만들었는데
 * {@code webtoon_usage} 는 0줄이었다.
 *
 * 비용이 DB 에 안 쌓이면 <b>일일 상한이 무의미해진다</b>. 아무리 만들어도
 * "오늘 0원 썼다" 가 되기 때문이다.
 *
 * <h2>여기서 실패해도 만들기는 성공이다</h2>
 *
 * 그림은 이미 만들어져 있고 사람은 볼 수 있다. 못 남긴 것은 크게 남기고
 * 넘어간다 — 다 만든 사람에게 "실패했습니다" 를 보여줄 수는 없다.
 */
@Service
public class AfterRun {

    private static final Logger log = LoggerFactory.getLogger(AfterRun.class);

    private final UsageService usage;
    private final PageStore pages;
    private final WorkLedger works;
    private final HarnessProcess harness;
    private final Path runsDir;
    private final String bucket;
    private final ObjectMapper mapper = new ObjectMapper();

    public AfterRun(UsageService usage, PageStore pages, WorkLedger works,
                    HarnessProcess harness,
                    @Value("${lore.webtoon.python.runs-dir:}") String runsDir,
                    @Value("${app.s3.content-bucket:}") String bucket) {
        this.usage = usage;
        this.pages = pages;
        this.works = works;
        this.harness = harness;
        this.runsDir = (runsDir == null || runsDir.isBlank()
                ? harness.dir().resolve("runs")
                : Path.of(runsDir)).toAbsolutePath().normalize();
        this.bucket = bucket == null ? "" : bucket;
    }

    /** 다 끝났다. 남길 것을 남긴다. */
    public void finish(String runId, java.util.function.Consumer<String> onLine) {
        recordCost(runId);
        uploadArt(runId, onLine);
    }

    /**
     * 호출마다 나간 돈을 DB 로.
     *
     * 하네스가 {@code meta.json} 에 아주 촘촘히 적어 둔다 — 단계 · 모델 ·
     * 토큰(입력/출력/캐시) · 달러 · 원 · 걸린 초. 그걸 그대로 읽어 올린다.
     * 다시 올려도 (작품, 몇 번째) 로 겹치는 것이 걸러진다.
     */
    private void recordCost(String runId) {
        Path meta = runsDir.resolve(runId).resolve("meta.json");
        if (!Files.isRegularFile(meta)) {
            log.warn("비용 기록이 없습니다 (run={})", runId);
            return;
        }
        try {
            JsonNode calls = mapper.readTree(meta.toFile()).path("calls");
            List<UsageService.Call> out = new ArrayList<>();
            for (JsonNode one : calls) {
                JsonNode used = one.path("usage");
                JsonNode cost = one.path("cost");
                out.add(new UsageService.Call(
                        one.path("stage").asText(""),
                        one.path("provider").asText(""),
                        one.path("model").asText(""),
                        used.path("input").asLong(0),
                        used.path("output").asLong(0),
                        cost.path("total").asDouble(0),
                        cost.path("total_krw").asLong(0),
                        cost.path("cost_basis").asText(null),
                        one.path("error").asText(null),
                        at(one.path("at").asDouble(0))));
            }
            int saved = usage.ingest(runId, out);
            log.info("비용을 적었습니다 (run={}, {}건)", runId, saved);
        } catch (IOException | RuntimeException e) {
            log.error("비용을 적지 못했습니다 (run={})", runId, e);
        }
    }

    /**
     * 그림을 S3 로.
     *
     * 올리는 일 자체는 파이썬이 한다({@code s3_upload.py}) — 원본을 줄이고
     * 올리는 코드가 이미 거기 있고, 자바로 다시 쓰면 두 벌이 된다. 스프링은
     * 그것을 프로그램으로 부르고, <b>주소를 DB 에 적는 것은 그 스크립트가
     * 이 서버에게 도로 알려 준다</b>(내부 주소).
     *
     * 버킷을 안 정해 뒀으면 그냥 넘어간다 — 로컬에서는 안 올려도 된다.
     */
    private void uploadArt(String runId, java.util.function.Consumer<String> onLine) {
        if (bucket.isEmpty()) {
            return;
        }
        try {
            int code = harness.upload(runId, onLine);
            if (code != 0) {
                log.error("그림을 S3 에 못 올렸습니다 (run={}, exit={})", runId, code);
                return;
            }
            /* **올린 것과 적힌 것은 다른 일이다.**
             *
             * 스크립트는 S3 에 올린 다음 그 주소를 이 서버에 도로 알려 주는데,
             * 알리는 쪽만 조용히 실패할 수 있다(내부 토큰이 없으면 그렇다 —
             * 실제로 겪었다). 그러면 화면에는 "올렸습니다" 가 찍히는데 DB 는
             * 비어 있고, 나중에 작품을 DB 로 찾으면 그림이 없는 줄만 나온다.
             *
             * 올린 직후에 한 번 세어 본다. 여기서 크게 남겨 두지 않으면 이걸
             * 배포에서 다시 찾게 된다. */
            if (!pages.has(runId)) {
                log.error("그림은 S3 에 올라갔는데 주소가 DB 에 없습니다 (run={}). "
                        + "LORE_WEBTOON_INTERNAL_TOKEN 을 확인하세요 — 없으면 "
                        + "s3_upload.py 가 알리는 단계를 건너뜁니다.", runId);
            }
        } catch (Exception e) {                     // noqa: 여기서 만들기를 실패시키지 않는다
            log.error("그림을 S3 에 못 올렸습니다 (run={})", runId, e);
        }
    }

    /** 하네스가 적는 에포크 초 -> 시각. 없으면 지금으로 둔다. */
    private static Instant at(double epochSeconds) {
        return epochSeconds > 0
                ? Instant.ofEpochMilli(Math.round(epochSeconds * 1000))
                : Instant.now();
    }
}
