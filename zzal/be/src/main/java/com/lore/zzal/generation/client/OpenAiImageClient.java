package com.lore.zzal.generation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.s3.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * OpenAI 이미지 생성. 시트와 격자가 이걸 쓴다.
 *
 * ★ /v1/images/edits 를 쓴다 — 참조 이미지를 함께 보내야 하기 때문이다.
 *   시트는 사용자 원본을, 격자는 시트를 참조로 받는다. 참조 없이 만들면 캐릭터가 달라진다.
 *
 * ★★ 자동으로 다시 부르지 않는다. 이미지 생성은 한 번에 실제로 돈이 나가므로,
 *    클라이언트가 조용히 재시도하면 **돈은 두 번 나가고 우리 기록에는 한 줄만 남는다.**
 *    다시 할지는 위층(GenerationRunner)이 기록을 남기며 판단한다.
 *
 * 실측(2026-08-26, 5캐릭터) — 시트 54~60초 $0.063 · 격자 54~60초 $0.086
 */
public class OpenAiImageClient implements ImageClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageClient.class);
    private static final String ENDPOINT = "https://api.openai.com/v1/images/edits";

    /**
     * gpt-image-2 토큰 단가 (USD per 1M) — 2026-09-02 공식 가격표 재확인.
     *
     * 이미지 입력 $8 · 출력 $30. (텍스트 입력은 $5 로 다르지만, 이 클라이언트는
     * 이미지 생성만 하므로 이미지 단가를 쓴다)
     */
    private static final BigDecimal PRICE_INPUT = new BigDecimal("8.0");
    private static final BigDecimal PRICE_OUTPUT = new BigDecimal("30.0");
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final S3Storage storage;
    private final String apiKey;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiImageClient(S3Storage storage, String apiKey, int timeoutSeconds) {
        this.storage = storage;
        this.apiKey = apiKey;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                // 리다이렉트를 따라가지 않는다 — 따라가면 같은 요청이 두 번 갈 수 있다
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Result generate(String prompt, List<String> refImageKeys, String outputKey, ModelSpec spec)
            throws Exception {
        Path work = Files.createTempDirectory("zzal-img-");
        try {
            // 참조 이미지를 S3 에서 받아 온다(시트 생성이면 원본, 격자면 시트).
            Path[] refs = new Path[refImageKeys.size()];
            for (int i = 0; i < refImageKeys.size(); i++) {
                refs[i] = work.resolve("ref" + i + ".png");
                storage.download(refImageKeys.get(i), refs[i]);
            }

            String boundary = "----zzal" + UUID.randomUUID();
            byte[] body = multipart(boundary, prompt, spec, refs);

            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .timeout(Duration.ofSeconds(180))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                // 본문을 그대로 붙인다 — moderation 차단인지 한도 초과인지가 여기 적혀 있고,
                // 위층이 그 문구를 보고 실패 종류를 가른다(처방이 정반대라 반드시 구분해야 한다).
                throw new IllegalStateException(
                        "이미지 생성 실패(HTTP %d): %s".formatted(res.statusCode(), res.body()));
            }

            JsonNode payload = json.readTree(res.body());
            String b64 = payload.path("data").path(0).path("b64_json").asText(null);
            if (b64 == null || b64.isBlank()) {
                throw new IllegalStateException("응답에 이미지가 없습니다: " + res.body());
            }

            Path out = work.resolve("out.png");
            Files.write(out, Base64.getDecoder().decode(b64));
            storage.upload(outputKey, out, "image/png");

            BigDecimal cost = cost(payload);
            log.info("이미지 생성 — {} · {} · ${}", outputKey, spec.model(), cost);
            return new Result(outputKey, cost);
        } finally {
            deleteQuietly(work);
        }
    }

    /** 응답의 토큰 사용량으로 실제 비용을 계산한다. 추정이 아니라 청구 근거와 같은 숫자다. */
    private BigDecimal cost(JsonNode payload) {
        JsonNode usage = payload.path("usage");
        long in = usage.path("input_tokens").asLong(0);
        long out = usage.path("output_tokens").asLong(0);
        return PRICE_INPUT.multiply(BigDecimal.valueOf(in))
                .add(PRICE_OUTPUT.multiply(BigDecimal.valueOf(out)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private byte[] multipart(String boundary, String prompt, ModelSpec spec, Path[] refs) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String dash = "--" + boundary + "\r\n";

        field(out, dash, "model", spec.model());
        field(out, dash, "prompt", prompt);
        field(out, dash, "n", "1");
        if (spec.size() != null) {
            field(out, dash, "size", spec.size());
        }
        if (spec.quality() != null) {
            field(out, dash, "quality", spec.quality());
        }
        for (Path ref : refs) {
            out.write((dash + "Content-Disposition: form-data; name=\"image[]\"; filename=\""
                    + ref.getFileName() + "\"\r\nContent-Type: image/png\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(Files.readAllBytes(ref));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void field(ByteArrayOutputStream out, String dash, String name, String value) throws Exception {
        out.write((dash + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> p.toFile().delete());
        } catch (Exception e) {
            log.warn("임시 폴더 정리 실패 — {}", dir, e);
        }
    }
}
