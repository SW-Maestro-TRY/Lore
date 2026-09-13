package com.lore.webtoon.job;

import com.lore.webtoon.art.PageUploader;
import com.lore.webtoon.usage.UsageService;
import com.lore.webtoon.work.WorkLedger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final PageUploader uploader;
    private final WorkLedger works;
    private final HarnessProcess harness;
    private final Path runsDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public AfterRun(UsageService usage, PageUploader uploader, WorkLedger works,
                    HarnessProcess harness) {
        this.usage = usage;
        this.uploader = uploader;
        this.works = works;
        this.harness = harness;
        /* **자리는 HarnessProcess 하나가 정한다.** 여기서 기본값을 또 적으면
           넷이 같은 문자열을 따로 갖게 되고, 한쪽만 안 고치는 순간 그 걸음만
           다른 폴더를 본다 — 실제로 AfterRun 이 그래서 비용을 하나도 못 적었다
           (2026-09-12 배포에서 실측). 바꾸려면 `lore.webtoon.python.runs-dir`. */
        this.runsDir = harness.runsDir();
    }

    /** 다 끝났다. 남길 것을 남긴다. */
    public void finish(String runId, java.util.function.Consumer<String> onLine) {
        cost(runId);
        uploadArt(runId, onLine);
    }

    /**
     * 호출마다 나간 돈을 DB 로.
     *
     * 하네스가 {@code meta.json} 에 아주 촘촘히 적어 둔다 — 단계 · 모델 ·
     * 토큰(입력/출력/캐시) · 달러 · 원 · 걸린 초. 그걸 그대로 읽어 올린다.
     * 다시 올려도 (작품, 몇 번째) 로 겹치는 것이 걸러진다.
     *
     * <h2>다 만든 뒤에만 부르면 안 된다</h2>
     *
     * 이건 <b>걸음마다</b> 부르라고 밖으로 열어 둔 것이다. 끝에서 한 번만
     * 부르면 <b>끝까지 못 간 작품의 값이 영영 안 잡힌다</b> — 죽어도 돈은 이미
     * 나간 뒤다. 그러면 일일 상한이 성공한 것만 세게 되고, 실패가 잦을수록
     * 상한이 헐거워진다. 파이썬 서버는 이걸 알고 단계마다 올렸다
     * ({@code newharness_pipeline._run} 의 {@code usage_report.push}).
     *
     * 사람이 이야기·시트 앞에서 멈춰 서 있는 동안에도 마찬가지다. 그 사람은
     * 실패한 것도 성공한 것도 아니지만 <b>거기까지 그린 값은 나갔다.</b>
     *
     * 여러 번 불러도 된다 — 겹치는 것은 서버가 (작품, 몇 번째) 로 거른다.
     */
    public void cost(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
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
     * <b>줄이는 것은 파이썬, 올리고 적는 것은 여기.</b> 파이썬이 폭마다 줄여
     * 디스크에 놓고 경로만 알려 주면({@code s3_upload.py --prepare}), 그
     * 파일을 공통 저장소로 올리고 그 자리에서 바로 DB 에 적는다.
     *
     * 전에는 올리는 것까지 파이썬이 하고 <b>주소를 이 서버에 HTTP 로 도로
     * 알려</b> 줬다. 그래서 알리는 쪽만 조용히 실패하면(내부 토큰이 없으면
     * 그랬다 — 실제로 겪었다) 그림은 S3 에 있는데 DB 는 비고, 화면에는
     * "올렸습니다" 가 찍혔다. 지금은 올린 그 자리에서 적으므로 둘이 갈릴 수가
     * 없다 — 그 실패 모드와 그것을 찾으려고 두던 확인이 함께 없어졌다.
     *
     * 버킷을 안 정해 뒀으면 그냥 넘어간다 — 로컬에서는 안 올려도 된다.
     */
    private void uploadArt(String runId, java.util.function.Consumer<String> onLine) {
        if (!uploader.ready()) {
            return;
        }
        try {
            String prepared = harness.prepareUpload(runId, onLine);
            uploader.uploadPrepared(runId, prepared, onLine);
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
