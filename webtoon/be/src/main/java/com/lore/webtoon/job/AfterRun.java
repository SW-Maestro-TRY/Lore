package com.lore.webtoon.job;

import com.lore.webtoon.art.PageStore;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
    private final PageStore pages;
    private final WorkLedger works;
    private final HarnessProcess harness;
    private final Path runsDir;
    /**
     * 한 번 봐 둔 작품과 그 결과. <b>망가진 작품에 매번 파이썬을 띄우지 않으려는
     * 것</b>이다 — 결과 화면은 0.8초마다 묻는다. 동시에 물어도 먼저 온 하나만
     * 돌고 나머지는 기다린다(computeIfAbsent 가 그 열쇠를 잡는다).
     *
     * 서버를 다시 띄우면 비워지므로, 고친 뒤 배포하면 저절로 한 번 더 해 본다.
     */
    private final ConcurrentMap<String, Boolean> healed = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public AfterRun(UsageService usage, PageUploader uploader, PageStore pages,
                    WorkLedger works, HarnessProcess harness) {
        this.usage = usage;
        this.uploader = uploader;
        this.pages = pages;
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
     * <b>다시 남긴다.</b> 그림은 다 그려졌는데 남기는 데서 실패한 작품을 살린다.
     *
     * <h2>왜 있나</h2>
     *
     * {@link #finish} 는 실패를 삼킨다 — 다 만든 사람에게 "실패했습니다" 를
     * 보여줄 수 없기 때문이다. 그런데 삼킨 다음이 문제였다: 그림이 S3 에도
     * DB 에도 없으니 <b>결과 화면이 통째로 비었고, 되살릴 길이 없었다.</b>
     * 돈은 이미 다 나간 작품이다(2026-09-12 배포에서 실제로 한 편이 그랬다 —
     * {@code s3_upload.py} 가 지워진 모듈을 부르고 있었다).
     *
     * <h2>{@link #finish} 와 무엇이 다른가</h2>
     *
     * <b>실패를 삼키지 않는다.</b> 사람이 「다시 올리기」를 눌렀는데 아무 일도
     * 안 일어나면 두 번째로 속는 것이다. 왜 안 됐는지 그대로 올려 보낸다.
     *
     * <h2>다시 눌러도 된다</h2>
     *
     * 비용은 (작품, 몇 번째)로 겹치는 것이 걸러지고, 그림은 이미 적힌 장을
     * 건너뛴다. <b>그리는 일은 다시 하지 않으므로 돈이 안 나간다.</b>
     *
     * @return 이번에 새로 적은 그림 줄 수. 0 이면 이미 다 적혀 있었다는 뜻이다
     */
    public int recover(String runId, java.util.function.Consumer<String> onLine)
            throws IOException, InterruptedException {
        cost(runId);
        if (!uploader.ready()) {
            // 로컬에는 버킷이 없다. 하네스 디스크로 화면이 뜨므로 할 일이 없다.
            return 0;
        }
        String prepared = harness.prepareUpload(runId, onLine);
        return uploader.uploadPrepared(runId, prepared, onLine);
    }



    /**
     * <b>그림이 디스크에는 있는데 안 적혀 있으면, 그 자리에서 적는다.</b>
     *
     * <h2>왜 읽는 자리에서 하나</h2>
     *
     * 다 그려 놓고 올리는 데서 실패한 작품은 결과 화면이 404 다. 되살리려면
     * 주인임을 보여야 하는데({@link com.lore.webtoon.work.MyWebtoonService#reupload})
     * <b>게스트는 주인이 될 수 없다</b> — uid 는 지어낼 수 있어서 믿지 않는다.
     * 그런데 웹툰 스튜디오는 로그인 없이 끝까지 만들 수 있는 화면이다. 즉
     * 가장 되살려 줘야 할 사람이 되살릴 수 없었다.
     *
     * 그래서 <b>여는 것만으로 낫게</b> 한다. 자기 작품을 열어 보는 사람에게
     * "로그인하고 이 버튼을 누르세요" 를 시킬 이유가 없다.
     *
     * <h2>같은 작품을 동시에 열면 기다린다</h2>
     *
     * 화면 하나를 여는 데 요청이 여럿 간다(서버가 미리 읽는 것 · 브라우저가
     * 읽는 것). 예전에는 먼저 온 것이 되살리는 동안 나머지가 <b>"이미 해 봤다"
     * 로 그냥 지나가서 404</b> 를 받았고, 화면은 그 404 를 보고 「작품을 열지
     * 못했습니다」를 띄웠다 — 되살리기는 그 직후 성공했는데도 그랬다(2026-09-13
     * 실측). 그래서 먼저 온 것이 끝날 때까지 <b>나머지는 기다린다.</b>
     *
     * <h2>안전한가</h2>
     *
     * 세 가지가 다 맞을 때만 움직인다 — 적힌 그림이 없고, 디스크에 그린 그림이
     * 있고, 이 작품을 아직 안 해 봤다. 그리는 일은 하지 않으므로 <b>돈이 안
     * 나간다</b>. 올릴 자리(공개/비공개)는 {@code PageUploader} 가 작품의 공개
     * 여부를 보고 정하므로, 비공개 작품이 열린 자리에 남지 않는다.
     *
     * @return 이번에 되살렸으면 true
     */
    public boolean healIfMissing(String runId) {
        if (runId == null || runId.isBlank() || pages.has(runId)) {
            return false;               // 거의 매번 여기서 돌아간다
        }
        if (!Files.isDirectory(runsDir.resolve(runId).resolve("pages"))) {
            return false;               // 그린 것이 없다 — 아직 만드는 중이거나 없는 작품
        }
        /* 작품 하나당 한 번만 돌고, 도는 동안 같은 작품을 물은 요청은 여기서
           기다린다(computeIfAbsent 가 그 열쇠를 잡고 있다). 끝나면 그 결과를
           같이 받는다 — 기다린 쪽도 적힌 그림을 보게 되므로 404 가 안 난다. */
        return healed.computeIfAbsent(runId, this::heal);
    }

    private boolean heal(String runId) {
        try {
            int recorded = recover(runId, line -> log.info("[되살리기] {}", line));
            log.warn("그림이 안 적혀 있어 다시 올렸습니다 (run={}, 적은 줄={})", runId, recorded);
            return recorded > 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {         // noqa: 되살리다 죽어서 화면까지 막으면 더 나쁘다
            log.error("그림을 되살리지 못했습니다 (run={})", runId, e);
            return false;               // 적어 둔다 — 또 해도 같은 데서 걸린다
        }
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
