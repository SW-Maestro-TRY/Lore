package com.lore.zzal.chat.line;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI Chat Completions 로 대사 한 줄. 응답은 JSON 객체로 받는다({@code response_format=json_object}).
 *
 * <h3>★ 단가는 모델마다 다르다</h3>
 * 그림 쪽에서 이미지 단가를 글에 적용해 원가가 3~4배 부푼 적이 있다({@code OpenAiTextClient} 주석).
 * 그래서 모델 이름으로 단가를 찾고, 모르는 모델이면 <b>비싼 쪽(gpt-5)</b>으로 세고 경고를 남긴다 —
 * 상한 판정은 넘치게 세는 쪽이 안전하다. 2026-10-08 공식 가격표 확인(USD / 1M 토큰).
 *
 * <h3>★ 추론 강도</h3>
 * gpt-5 계열은 추론 모델이라 기본 설정이면 짧은 대사에도 몇 초를 생각한다. {@code reasoning_effort}
 * 를 낮춰 4초 안에 들게 한다(설정 {@code app.zzal.chat.reasoning-effort}, 비우면 안 보낸다).
 */
public class OpenAiChatLineClient implements ChatLineClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatLineClient.class);
    private static final String ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final BigDecimal MILLION = new BigDecimal("1000000");

    /** {입력, 출력} USD / 1M 토큰. */
    static final Map<String, BigDecimal[]> PRICES = Map.of(
            "gpt-5", new BigDecimal[]{new BigDecimal("1.25"), new BigDecimal("10.00")},
            "gpt-5-mini", new BigDecimal[]{new BigDecimal("0.25"), new BigDecimal("2.00")},
            "gpt-5-nano", new BigDecimal[]{new BigDecimal("0.05"), new BigDecimal("0.40")},
            "gpt-5.4-mini", new BigDecimal[]{new BigDecimal("0.75"), new BigDecimal("4.50")},
            "gpt-5.4-nano", new BigDecimal[]{new BigDecimal("0.20"), new BigDecimal("1.25")},
            "gpt-4.1-mini", new BigDecimal[]{new BigDecimal("0.40"), new BigDecimal("1.60")});

    private final String apiKey;
    private final String reasoningEffort;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper json = new ObjectMapper();

    public OpenAiChatLineClient(String apiKey, String reasoningEffort) {
        this.apiKey = apiKey;
        this.reasoningEffort = reasoningEffort == null || reasoningEffort.isBlank() ? null : reasoningEffort.strip();
    }

    @Override
    public Completion complete(String system, String userText, String model, Duration timeout) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("model", model);
        if (reasoningEffort != null && model.startsWith("gpt-5")) {
            body.put("reasoning_effort", reasoningEffort);
        }
        body.putObject("response_format").put("type", "json_object");
        // ★ 시스템 메시지(펫당 고정)를 앞에 — 같은 앞부분이 반복돼야 OpenAI 프롬프트 캐시가 먹는다.
        var messages = body.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", system);
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userText);

        HttpRequest req = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        // ★ 전체 시간을 한 번 더 묶는다 — 요청 timeout 은 응답 머리까지만 센다.
        CompletableFuture<HttpResponse<String>> f = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> res;
        try {
            res = f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            throw e;
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof java.net.http.HttpTimeoutException) {
                throw new java.util.concurrent.TimeoutException("응답 시간 초과");
            }
            throw e;
        }
        if (res.statusCode() != 200) {
            // 4xx·5xx 는 돈이 안 나간다(OpenAI 는 실패 요청에 과금하지 않는다).
            throw new IllegalStateException("대사 생성 실패(HTTP %d): %s".formatted(res.statusCode(),
                    res.body().length() > 300 ? res.body().substring(0, 300) : res.body()));
        }
        JsonNode payload = json.readTree(res.body());
        JsonNode usage = payload.path("usage");
        long in = usage.path("prompt_tokens").asLong(0);
        long out = usage.path("completion_tokens").asLong(0);
        BigDecimal cost = cost(model, in, out);
        String text = payload.path("choices").path(0).path("message").path("content").asText("").trim();
        if (text.isBlank()) {
            throw new BilledException("응답에 글이 없습니다", cost);
        }
        return new Completion(text, cost, in, out);
    }

    /** 단가표로 비용. 모르는 모델은 gpt-5 단가(비싼 쪽). */
    static BigDecimal cost(String model, long in, long out) {
        BigDecimal[] p = PRICES.get(model);
        if (p == null) {
            log.warn("채팅 LLM 단가표에 없는 모델 — {}. gpt-5 단가로 셉니다(상한은 넘치게).", model);
            p = PRICES.get("gpt-5");
        }
        return p[0].multiply(BigDecimal.valueOf(in)).add(p[1].multiply(BigDecimal.valueOf(out)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
    }

    /**
     * 시간 초과처럼 응답을 못 받았을 때의 추정 비용 — 지시문 글자 수로 입력 토큰을 넉넉히 잡고 출력 300토큰.
     * ★ 실제로 과금됐는지 알 수 없다. 상한 판정이 새지 않게 넘치게 적는다.
     */
    public static BigDecimal estimate(String model, int promptChars) {
        return cost(model, promptChars, 300);
    }
}
