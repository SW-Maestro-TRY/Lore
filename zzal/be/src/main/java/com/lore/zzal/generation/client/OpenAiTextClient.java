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
    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;
    private final Integer maxCompletionTokens;
    /**
     * 정체성 호출의 reasoning_effort. null 이면 보내지 않는다(모델 기본값 medium).
     *
     * ★ 2026-10-27 추가 — 출력 약 1,900토큰 중 약 1,800이 생각 토큰이었고, OpenAI 가 느려진 10/26 에
     *   90초 제한을 넘겨 부화가 연달아 끊겼다. 모델(gpt-5)은 그대로 두고 생각 길이만 줄인다.
     *   설정 {@code app.zzal.openai.identity-reasoning-effort}, 기본 low, 비우면 안 보낸다.
     */
    private final String reasoningEffort;
    private final ProviderUsageRecorder usageRecorder;
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiTextClient(S3Storage storage, String apiKey) {
        this(storage, apiKey, null);
    }

    /** Optional verification bound; null preserves the existing request contract. */
    public OpenAiTextClient(S3Storage storage, String apiKey, Integer maxCompletionTokens) {
        this(storage, apiKey, maxCompletionTokens, ProviderUsageRecorder.NO_OP);
    }
    public OpenAiTextClient(S3Storage storage,String apiKey,Integer maxCompletionTokens,ProviderUsageRecorder recorder) {
        this(storage, apiKey, maxCompletionTokens, null, recorder);
    }
    public OpenAiTextClient(S3Storage storage,String apiKey,Integer maxCompletionTokens,String reasoningEffort,ProviderUsageRecorder recorder) {
        this(storage, apiKey, maxCompletionTokens, reasoningEffort, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create(ENDPOINT), Duration.ofSeconds(120),recorder);
    }

    OpenAiTextClient(S3Storage storage, String apiKey, Integer maxCompletionTokens,
                     HttpClient http, URI endpoint, Duration timeout) {
        this(storage,apiKey,maxCompletionTokens,http,endpoint,timeout,ProviderUsageRecorder.NO_OP);
    }
    OpenAiTextClient(S3Storage storage,String apiKey,Integer maxCompletionTokens,HttpClient http,URI endpoint,Duration timeout,ProviderUsageRecorder recorder) {
        this(storage,apiKey,maxCompletionTokens,null,http,endpoint,timeout,recorder);
    }
    OpenAiTextClient(S3Storage storage,String apiKey,Integer maxCompletionTokens,String reasoningEffort,HttpClient http,URI endpoint,Duration timeout,ProviderUsageRecorder recorder) {
        this.reasoningEffort = normalizeReasoningEffort(reasoningEffort);
        this.usageRecorder=java.util.Objects.requireNonNull(recorder);
        if (maxCompletionTokens != null && maxCompletionTokens <= 0) {
            throw new IllegalArgumentException("maxCompletionTokens must be positive");
        }
        this.storage = storage;
        this.apiKey = apiKey;
        this.maxCompletionTokens = maxCompletionTokens;
        this.http = java.util.Objects.requireNonNull(http);
        this.endpoint = java.util.Objects.requireNonNull(endpoint);
        this.timeout = java.util.Objects.requireNonNull(timeout);
    }

    @Override
    public Result generate(String prompt, List<String> refImageKeys, ModelSpec spec) throws Exception {
        Path work = Files.createTempDirectory("zzal-txt-");
        try (var attempt = new ProviderAttempt(usageRecorder, spec.model())) {
            ObjectNode body = json.createObjectNode();
            body.put("model", spec.model());
            if (maxCompletionTokens != null) body.put("max_completion_tokens", maxCompletionTokens);
            // 채팅 클라이언트(OpenAiChatLineClient)와 같은 규칙 — gpt-5 계열에만 보낸다.
            if (reasoningEffort != null && spec.model().startsWith("gpt-5")) {
                body.put("reasoning_effort", reasoningEffort);
            }

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

            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();

            attempt.start();
            HttpResponse<String> res;
            try { res=http.send(req,HttpResponse.BodyHandlers.ofString()); }
            catch(Exception e){attempt.record(ProviderUsageRecorder.headers(spec.model(),0,null));throw e;}
            String requestId=res.headers().firstValue("x-request-id").orElse(null);
            attempt.record(ProviderUsageRecorder.headers(spec.model(),res.statusCode(),requestId));
            if (res.statusCode() != 200) {
                throw new IllegalStateException(
                        "문단 생성 실패(HTTP %d): %s".formatted(res.statusCode(), res.body()));
            }

            // ★★ 여기서부터는 돈이 이미 나간 뒤다 — 이미지 쪽과 같은 이유로 비용을 먼저 계산해 둔다.
            JsonNode payload = json.readTree(res.body());
            attempt.record(ProviderUsageRecorder.payload(spec.model(),res.statusCode(),requestId,payload));
            BigDecimal cost = cost(payload);
            attempt.estimatedCost(cost);
            String text;
            try {
                text = payload.path("choices").path(0).path("message").path("content").asText("").trim();
                if (text.isBlank()) {
                    throw new IllegalStateException("응답에 글이 없습니다: " + res.body());
                }
            } catch (Exception e) {
                throw new BilledFailureException(cost, e);
            }

            // 입력 토큰 수로 그림이 실제로 실렸는지 가늠한다(글만이면 수백, 그림이 붙으면 그보다 크게 뛴다).
            JsonNode usage = payload.path("usage");
            log.info("정체성 문단 — {}자 · {} · effort {} · ${} · 토큰 입력 {} · 출력 {}", text.length(), spec.model(),
                    reasoningEffort == null ? "default" : reasoningEffort, cost,
                    usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0));
            attempt.success();
            return new Result(text, cost);
        } finally {
            deleteQuietly(work);
        }
    }

    /** 허용값. 그 외는 기동 시 막는다 — 오타가 실제 호출 때 400 으로야 드러나면 늦다. */
    public static final List<String> REASONING_EFFORTS = List.of("minimal", "low", "medium", "high");

    /** 빈 값 → null(안 보냄). 허용값이 아니면 설정 이름을 말하는 예외. */
    public static String normalizeReasoningEffort(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.strip();
        if (!REASONING_EFFORTS.contains(v)) {
            throw new IllegalArgumentException(
                    "app.zzal.openai.identity-reasoning-effort 값이 잘못됐습니다: '%s' (허용: %s, 비우면 안 보냄)"
                            .formatted(v, String.join("/", REASONING_EFFORTS)));
        }
        return v;
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
