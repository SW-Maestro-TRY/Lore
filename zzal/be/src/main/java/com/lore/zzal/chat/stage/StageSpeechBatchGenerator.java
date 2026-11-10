package com.lore.zzal.chat.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.zzal.chat.line.*;
import com.lore.zzal.chat.prompt.PromptAssembler;
import com.lore.zzal.chat.prompt.TutorialEndingSpeech;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** One provider attempt for all frozen tutorial lines. It never creates conversation history. */
@Component
public class StageSpeechBatchGenerator {
    private final ChatLineClient client;
    private final String model;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public StageSpeechBatchGenerator(@Value("${app.zzal.chat.llm:true}") boolean enabled,
            @Value("${app.zzal.openai.api-key:}") String key,
            @Value("${app.zzal.chat.model:gpt-5.6-luna}") String model,
            @Value("${app.zzal.chat.reasoning-effort:none}") String effort,
            com.lore.zzal.generation.client.PersistentProviderUsageRecorder usageRecorder) {
        this(enabled && key != null && !key.isBlank() ? new OpenAiChatLineClient(key, effort, 3200, usageRecorder.forFeature("tutorial_batch")) : null,
                model, Duration.ofSeconds(12));
    }

    public StageSpeechBatchGenerator(ChatLineClient client, String model, Duration timeout) {
        this.client = client; this.model = model; this.timeout = timeout;
    }

    public List<GeneratedLine> generate(List<StageSpeechTransactions.Claim> claims) {
        if (claims.isEmpty()) return List.of();
        if (client == null) return failed(claims.size(), "llm_off", 0, BigDecimal.ZERO, 0);
        long start = System.nanoTime();
        BigDecimal cost = BigDecimal.ZERO;
        try {
            var first = claims.getFirst().context();
            String system = PromptAssembler.system(first.sheet());
            int extraction = system.indexOf("[의미기억 추출]");
            if (extraction >= 0) system = system.substring(0, extraction);
            system += PromptAssembler.TUTORIAL_ADDRESS_RULES + "\n";
            system += """
                [튜토리얼 안내 묶음]
                아래 장면의 고정 안내를 모두 미리 작성한다. 아직 실제로 말하거나 행동한 기록이 아니다.
                돌봄 장면의 ENTRY는 캐릭터 자신의 상태·마음과 해당 행동을 부탁하는 짧은 말이다. 마무리 장면은 아래 장면별 의미를 따른다.
                AFTER_ACTION은 해당 행동을 서버가 성공 처리했을 때만 쓸 짧은 반응과 다음 행동 부탁을 함께 담는다.
                다음 행동 연결: FEED 뒤 간식, SNACK 뒤 바닥 청소, CLEAN 뒤 목욕, BATH 뒤 좌우 맞히기 놀이,
                GAME 뒤 낮잠 자기, NAP_SLEEP 뒤 깨워 주기, NAP_WAKE 뒤 첫 출석 선물 확인.
                사용자 일상·끼니·놀이 의향을 묻거나 답변을 기다리지 않는다. 호칭을 다시 묻지 않는다.
                캐릭터에게 밥을 챙겨 달라는 직접 행동 요청형 의문문은 허용한다.
                답변 선택지·버튼명·절차를 나열하지 않는다. 사용자 발언·사용자 사실·완료 이력을 만들지 않는다.
                GAME AFTER_ACTION은 승리와 패배 모두에 맞는 함께 논 뒤의 중립 반응이다. 승패를 단정하지 않는다.
                각 대사는 짧은 1~2문장, 보통 40~70자 이내이며 최대 100자다. 같은 설정과 저장 호칭을 전 장면에 유지한다.
                모든 speeches[].line은 요청 데이터의 responseLanguage 언어로 작성한다(en=English, ja=Japanese, ko=Korean).
                JSON 객체 {"speeches":[{"step":"요청된 단계","phase":"요청된 박자","line":"대사","motion":"base"}]}만 출력한다.
                요청된 장면을 순서대로 정확히 한 번씩 전부 반환한다. memory_deltas나 사용자 답은 만들지 않는다.
                """;
            for (var step : TutorialEndingSpeech.STEPS) system += step.name() + ": " + TutorialEndingSpeech.instruction(step) + "\n";
            var request = json.createObjectNode();
            request.put("responseLanguage", first.sheet().preferredResponseLanguage());
            request.put("sceneGuide", "FEED=밥, SNACK=간식, CLEAN=바닥 청소, BATH=목욕, GAME=좌우 맞히기 놀이, NAP_SLEEP=낮잠 자기, NAP_WAKE=낮잠에서 깨기.");
            request.put("stateScope", "첫 돌봄 전 캐릭터 자신의 참고 상태다. 미래 행동 완료 사실이 아니다. 마무리·채팅소개 장면에 초기 배고픔·청결·돌봄 요구를 반복하거나 옮기지 않는다.");
            request.put("initialState", first.state().labeledWords());
            var scenes = request.putArray("scenes");
            for (var claim : claims) {
                var context = claim.context();
                scenes.addObject().put("step", context.scene().step().name()).put("phase", context.scene().beat().name());
            }
            ChatLineClient.Completion response;
            try(var usage=com.lore.zzal.generation.client.ProviderUsageContext.open("tutorial_batch",first.petId(),null,java.util.UUID.randomUUID().toString(),1)) {
                response = client.complete(system, request.toString(), model, timeout);
            }
            cost = response.costUsd() == null ? BigDecimal.ZERO : response.costUsd();
            var speeches = json.readTree(response.text()).path("speeches");
            if (!speeches.isArray() || speeches.size() != claims.size()) throw new IllegalArgumentException("batch_shape");
            var result = new ArrayList<GeneratedLine>();
            for (int i = 0; i < claims.size(); i++) {
                var item = speeches.get(i); var scene = claims.get(i).context().scene();
                if (!scene.step().name().equals(item.path("step").asText()) || !scene.beat().name().equals(item.path("phase").asText())) throw new IllegalArgumentException("batch_scene");
                String line = item.path("line").asText("").strip();
                if (line.isBlank()) throw new IllegalArgumentException("batch_blank");
                line = com.lore.zzal.chat.session.ZzalChatTurn.fit(line);
                result.add(new GeneratedLine(line, item.path("motion").asText("base"), "llm", model,
                        i == 0 ? cost : BigDecimal.ZERO, null, LineOutcome.OK, elapsed(start), 1, LineExtract.NONE));
            }
            return List.copyOf(result);
        } catch (Exception failure) {
            if (failure instanceof ChatLineClient.BilledException billed) cost = billed.costUsd();
            return failed(claims.size(), failure instanceof java.util.concurrent.TimeoutException ? "batch_timeout" : "batch_failed", 1, cost, elapsed(start));
        }
    }

    private List<GeneratedLine> failed(int count, String reason, int attempts, BigDecimal cost, long millis) {
        var result = new ArrayList<GeneratedLine>();
        for (int i = 0; i < count; i++) result.add(new GeneratedLine(null, "base", LineChain.FIXED, model,
                i == 0 ? cost : BigDecimal.ZERO, reason, LineOutcome.FAILED_REPLY, millis, attempts, LineExtract.NONE));
        return List.copyOf(result);
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
}
