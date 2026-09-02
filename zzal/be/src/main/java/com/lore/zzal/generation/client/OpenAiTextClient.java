package com.lore.zzal.generation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lore.common.s3.S3Storage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * OpenAI 텍스트 생성. 정체성 문단을 만든다.
 *
 * ★ 시트 그림을 함께 보낸다 — 글만으로는 그 캐릭터를 묘사할 수 없다.
 *   결과 문단은 격자 프롬프트의 {IDENT} 자리에 그대로 들어간다.
 *
 * ⚠️ 이 단계가 실패의 원인이 된 적이 있다(2026-08-26) — 고양이 시트를 보고 엉뚱한 캐릭터를
 *    묘사하는 문단이 나왔고, 그 문단 때문에 다음 단계(격자)가 차단됐다.
 *    그래서 격자가 '거부' 로 실패하면 이 단계부터 다시 한다.
 */
public class OpenAiTextClient implements TextClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTextClient.class);
    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";

    /**
     * gpt-5 텍스트 단가 (USD per 1M) — 2026-09-02 공식 가격표 확인.
     *
     * ★ 이 값을 틀리면 원가 계산이 통째로 어긋난다. 실제로 처음에는 **이미지 모델 단가**
     *   (입력 $8 · 출력 $30)를 텍스트에 적용해 3~4배 부풀린 값이 나왔다.
     *   실험 스크립트도 같은 실수를 하고 있었다(api_guard.py 는 PRICE 하나로 둘 다 계산한다).
     *   모델이 다르면 단가도 다르다 — 새 모델을 쓸 때마다 여기를 확인해야 한다.
     */
    private static final BigDecimal PRICE_INPUT = new BigDecimal("1.25");
    private static final BigDecimal PRICE_OUTPUT = new BigDecimal("10.0");
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final S3Storage storage;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiTextClient(S3Storage storage, String apiKey) {
        this.storage = storage;
        this.apiKey = apiKey;
    }

    @Override
    public Result generate(String prompt, List<String> refImageKeys, ModelSpec spec) throws Exception {
        Path work = Files.createTempDirectory("zzal-txt-");
        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", spec.model());

            ArrayNode messages = body.putArray("messages");
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            ArrayNode content = user.putArray("content");

            ObjectNode textPart = content.addObject();
            textPart.put("type", "text");
            textPart.put("text", prompt);

            for (String key : refImageKeys) {
                Path ref = work.resolve("ref.png");
                storage.download(key, ref);
                String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(ref));

                ObjectNode imagePart = content.addObject();
                imagePart.put("type", "image_url");
                imagePart.putObject("image_url").put("url", "data:image/png;base64," + b64);
            }

            HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException(
                        "문단 생성 실패(HTTP %d): %s".formatted(res.statusCode(), res.body()));
            }

            JsonNode payload = json.readTree(res.body());
            String text = payload.path("choices").path(0).path("message").path("content").asText("").trim();
            if (text.isBlank()) {
                throw new IllegalStateException("응답에 글이 없습니다: " + res.body());
            }

            BigDecimal cost = cost(payload);
            log.info("정체성 문단 — {}자 · {} · ${}", text.length(), spec.model(), cost);
            return new Result(text, cost);
        } finally {
            deleteQuietly(work);
        }
    }

    private BigDecimal cost(JsonNode payload) {
        JsonNode usage = payload.path("usage");
        long in = usage.path("prompt_tokens").asLong(0);
        long out = usage.path("completion_tokens").asLong(0);
        return PRICE_INPUT.multiply(BigDecimal.valueOf(in))
                .add(PRICE_OUTPUT.multiply(BigDecimal.valueOf(out)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    private void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> p.toFile().delete());
        } catch (Exception e) {
            log.warn("임시 폴더 정리 실패 — {}", dir, e);
        }
    }
}
