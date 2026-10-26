package com.lore.webtoon.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 스스로 물어봐야 알 수 있는 것을 주기적으로 본다(#831).
 *
 * <ul>
 *   <li><b>DB</b> — 2분마다 {@code select 1}. 안 되면 급한 알림, 다시 되면 복구 알림.
 *       요청이 없는 새벽에 DB 가 멈춰도 아침에 사용자가 먼저 겪지 않게 하려는 것이다.</li>
 *   <li><b>OpenRouter 잔액</b> — 1시간마다. 남은 돈이 {@code lore.webtoon.alert.openrouter-min-usd}
 *       (기본 $20) 아래면 알린다. 10/25 OpenAI 선불이 바닥나 생성이 멈춘 뒤에야 알았던 일을 막으려는 것.
 *       키가 없으면(OpenRouter 를 안 쓰는 환경) 묻지 않는다.</li>
 *   <li><b>OpenAI 잔액(추정)</b> — 1시간마다. OpenAI 는 보통 API 키로 선불 잔액을 알려 주지 않으므로,
 *       마지막으로 충전한 금액({@code lore.webtoon.alert.openai-budget-usd})과 그 날짜
 *       ({@code lore.webtoon.alert.openai-budget-since})를 적어 두면 그 뒤 이 서버가 OpenAI 에 쓴 돈
 *       ({@code webtoon_usage})을 빼서 남은 돈을 어림한다. dev · staging 이 같은 키를 쓰면 그 몫은 빠져 있어
 *       실제 잔액은 이보다 적을 수 있다. 금액을 안 적으면 묻지 않는다. 바닥나서 생기는 실패는 따로
 *       생성 실패 알림(크레딧 소진)으로 바로 온다.</li>
 * </ul>
 *
 * 알림 주소가 없으면 아무것도 묻지 않는다.
 */
@Component
public class OpsWatch {

    private static final Logger log = LoggerFactory.getLogger(OpsWatch.class);

    private final OpsAlert alert;
    private final JdbcTemplate db;
    private final String openrouterKey;
    private final double minUsd;
    private final double openaiBudget;
    private final String openaiSince;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean dbDown;

    public OpsWatch(OpsAlert alert, DataSource dataSource,
                    @Value("${HAEUN_OPENROUTER_API_KEY:${OPENROUTER_API_KEY:}}") String openrouterKey,
                    @Value("${lore.webtoon.alert.openrouter-min-usd:20}") double minUsd,
                    @Value("${lore.webtoon.alert.openai-budget-usd:0}") double openaiBudget,
                    @Value("${lore.webtoon.alert.openai-budget-since:}") String openaiSince) {
        this.alert = alert;
        this.db = new JdbcTemplate(dataSource);
        this.db.setQueryTimeout(5);
        this.openrouterKey = openrouterKey == null ? "" : openrouterKey.trim();
        this.minUsd = minUsd;
        this.openaiBudget = openaiBudget;
        this.openaiSince = openaiSince == null ? "" : openaiSince.trim();
    }

    @Scheduled(initialDelayString = "PT4M", fixedDelayString = "PT1H")
    public void checkOpenAi() {
        if (!alert.enabled() || openaiBudget <= 0 || openaiSince.isEmpty()) {
            return;
        }
        try {
            Double spent = db.queryForObject(
                    "select coalesce(sum(cost_usd), 0) from webtoon_usage where provider = 'openai' "
                            + "and called_at >= cast(? as date) and error is null",
                    Double.class, openaiSince);
            double left = openaiBudget - (spent == null ? 0 : spent);
            if (left < minUsd) {
                alert.send(left < minUsd / 4 ? OpsAlert.Level.URGENT : OpsAlert.Level.WARN, "openai-low",
                        String.format("OpenAI 잔액 약 $%.2f (추정) — 충전이 필요해요", left),
                        String.format("%s 부터 충전 $%.0f 중 이 서버가 $%.2f 사용. dev·staging 사용분은 빠져 있어요. "
                                + "충전하면 Parameter Store lore_webtoon_alert_openai_budget_usd · _since 를 새로 적어 주세요.%n"
                                + "충전: https://platform.openai.com/settings/organization/billing/overview",
                                openaiSince, openaiBudget, spent));
            }
        } catch (RuntimeException e) {
            log.info("OpenAI 사용액을 못 셌습니다: {}", e.toString());
        }
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT2M")
    public void checkDb() {
        if (!alert.enabled()) {
            return;
        }
        try {
            db.queryForObject("select 1", Integer.class);
            if (dbDown) {
                dbDown = false;
                alert.send(OpsAlert.Level.OK, "db-ok", "DB 다시 응답해요", null);
            }
        } catch (RuntimeException e) {
            dbDown = true;
            alert.send(OpsAlert.Level.URGENT, "db-down", "DB 가 응답하지 않아요",
                    e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        }
    }

    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT1H")
    public void checkOpenRouter() {
        if (!alert.enabled() || openrouterKey.isEmpty()) {
            return;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/credits"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + openrouterKey)
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.info("OpenRouter 잔액을 못 읽었습니다 ({})", res.statusCode());
                return;
            }
            JsonNode d = json.readTree(res.body()).path("data");
            double left = d.path("total_credits").asDouble() - d.path("total_usage").asDouble();
            if (left < minUsd) {
                alert.send(left < minUsd / 4 ? OpsAlert.Level.URGENT : OpsAlert.Level.WARN, "openrouter-low",
                        String.format("OpenRouter 잔액 $%.2f — 충전이 필요해요", left),
                        String.format("기준 $%.0f 아래. 바닥나면 모든 생성이 실패해요.%n충전: https://openrouter.ai/settings/credits", minUsd));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.info("OpenRouter 잔액을 못 읽었습니다: {}", e.toString());
        }
    }
}
