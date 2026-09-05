package com.lore.zzal.motion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lore.zzal.motion.UnlockRule.Kind.BATH;
import static com.lore.zzal.motion.UnlockRule.Kind.CHAT_ANSWERS;
import static com.lore.zzal.motion.UnlockRule.Kind.FIRST_GIFT;
import static com.lore.zzal.motion.UnlockRule.Kind.GAME_STARTS;
import static com.lore.zzal.motion.UnlockRule.Kind.LAYER2_OPEN;
import static com.lore.zzal.motion.UnlockRule.Kind.SECOND_GIFT;
import static com.lore.zzal.motion.UnlockRule.Kind.SLEEP_WAKE;
import static com.lore.zzal.motion.UnlockRule.Kind.ZERO_MISS_DAYS;

/**
 * 동작 카탈로그 — <b>고정 18종</b>(정본 13장: 1층 8 · 2층 8 · 선물 2).
 *
 * <h3>★ 왜 설정이 아니라 코드에 박는가(v1 과 반대)</h3>
 * v1 은 "무엇을 몇 개 열지는 실험을 보고 계속 정한다" 며 목록을 설정({@code app.zzal.motions})에 뒀다.
 * 정본 v1.2 에서 그 결정이 끝났다 — 기본 행동 16종은 격자 2장으로 <b>부화 때 한꺼번에 생기고</b>,
 * 2층 8종은 동작마다 행동 조건이 붙어 있다. 조건과 번호가 동작에 묶인 이상 목록은 코드가 맞다.
 * 화면도 잠긴 칸의 이름·조건을 보여준다(플랜 T2 결정 4). 이름을 미리 약속해도 되는 이유는
 * 기본 행동이 검수 없이 부화 때 이미 다 만들어져 있기 때문이다.
 *
 * <h3>설정이 정하는 것은 하나 — 무엇을 <b>밤에 구울 수 있나</b></h3>
 * 심화 행동(16프레임)은 지시문 파일이 있어야 굽는다. {@code app.zzal.advanced-motions} · {@code gift-motions} 에
 * 적힌 key 만 밤 큐에 오르고, 적혔는데 파일이 없으면 <b>부팅할 때</b> 막힌다 — 사용자가 재우고 나서야
 * 드러나면 그 사람은 밤을 헛되이 기다린 것이 된다. 비어 있으면 아무것도 굽지 않는다(정상 상태).
 *
 * <h3>파일명 규약(api-v2.md 3절)</h3>
 * 기본 행동 그림 = {@code images/zzal/pets/{id}/basic/{key}.webp}. 지시문 = {@code zzal/prompt/{버전}/motions/{promptFile}.txt}.
 */
@Component
public class MotionCatalog {

    /** 정본 13장 그대로. 순서 = seq 오름차순 = 3층 심화 순서. */
    public static final List<MotionSpec> ALL = List.of(
            // 격자 1장 = 1층. 처음부터.
            new MotionSpec(1, "base", "기본 자세", MotionLayer.BASIC_1, UnlockRule.always(), "idle", "기본자세"),
            new MotionSpec(2, "eat", "먹기", MotionLayer.BASIC_1, UnlockRule.always(), "eat", "먹기"),
            new MotionSpec(3, "joy", "기쁜 자세", MotionLayer.BASIC_1, UnlockRule.always(), "happy", "기쁜자세"),
            new MotionSpec(4, "sad", "슬픈 자세", MotionLayer.BASIC_1, UnlockRule.always(), "sad", "슬픈자세"),
            new MotionSpec(5, "sick", "아픈 자세", MotionLayer.BASIC_1, UnlockRule.always(), null, "아픈자세"),
            new MotionSpec(6, "practice", "훈련 자세", MotionLayer.BASIC_1, UnlockRule.always(), "train", "훈련자세"),
            new MotionSpec(7, "shy", "교감 자세", MotionLayer.BASIC_1, UnlockRule.always(), "pet", "교감자세"),
            new MotionSpec(8, "call", "부르기", MotionLayer.BASIC_1, UnlockRule.always(), null, "부르기"),
            // 격자 2장 = 2층. 정본 6장 조건표.
            new MotionSpec(9, "tilt", "갸웃", MotionLayer.BASIC_2, UnlockRule.of(CHAT_ANSWERS, 1), null, "갸웃"),
            new MotionSpec(10, "wave", "손 흔들며 인사", MotionLayer.BASIC_2, UnlockRule.of(CHAT_ANSWERS, 4), null, "손흔들며인사"),
            new MotionSpec(11, "sleep", "자기", MotionLayer.BASIC_2, UnlockRule.of(SLEEP_WAKE, 3), null, "자기"),
            new MotionSpec(12, "wash", "씻기", MotionLayer.BASIC_2, UnlockRule.of(BATH, 3), null, "씻기"),
            new MotionSpec(13, "startle", "놀라기", MotionLayer.BASIC_2, UnlockRule.of(GAME_STARTS, 3), null, "놀라기"),
            new MotionSpec(14, "nod", "끄덕이기", MotionLayer.BASIC_2, UnlockRule.of(CHAT_ANSWERS, 12), null, "끄덕이기"),
            new MotionSpec(15, "smile_idle", "웃는 대기", MotionLayer.BASIC_2, UnlockRule.of(ZERO_MISS_DAYS, 3), null, "웃는대기"),
            new MotionSpec(16, "sit", "앉아 쉬기", MotionLayer.BASIC_2, UnlockRule.of(LAYER2_OPEN, 6), null, "앉아쉬기"),
            // 선물. 카탈로그 밖 특별 1종씩. 기본 행동 없음(16장: 구르기 먼저, 뒤로 넘어짐은 3층 8번째 뒤).
            new MotionSpec(101, "roll", "구르기", MotionLayer.GIFT, UnlockRule.of(FIRST_GIFT, 0), null, "구르기"),
            new MotionSpec(102, "fall_back", "뒤로 넘어짐", MotionLayer.GIFT, UnlockRule.of(SECOND_GIFT, 0), null, "뒤로넘어짐"));

    private static final Map<Integer, MotionSpec> BY_SEQ =
            ALL.stream().collect(Collectors.toUnmodifiableMap(MotionSpec::seq, Function.identity()));
    private static final Map<String, MotionSpec> BY_KEY =
            ALL.stream().collect(Collectors.toUnmodifiableMap(MotionSpec::key, Function.identity()));

    private final List<String> advancedKeys;
    private final List<String> giftKeys;
    private final String version;
    private final Map<String, String> blocks = new ConcurrentHashMap<>();

    public MotionCatalog(@Value("${app.zzal.advanced-motions:}") String advanced,
                         @Value("${app.zzal.gift-motions:}") String gifts,
                         @Value("${app.zzal.motion-pipeline-version:v1}") String version) {
        this.version = version;
        this.advancedKeys = parse(advanced, "app.zzal.advanced-motions", false);
        this.giftKeys = parse(gifts, "app.zzal.gift-motions", true);
        // 적힌 key 의 지시문이 실제로 있는지 여기서 다 확인한다 — 부팅 때 걸리게.
        advancedKeys.forEach(this::block);
        giftKeys.forEach(this::block);
    }

    /**
     * 설정의 쉼표 목록을 key 목록으로. 모르는 key 나 층이 안 맞는 key 는 <b>설정 이름을 말하며</b> 부팅을 막는다 —
     * "무엇을 고쳐야 하는지" 를 예외가 말해야 한다(2026-08-25 설정 불일치 사고의 처방).
     */
    private static List<String> parse(String configured, String property, boolean gift) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<String> keys = Arrays.stream(configured.split("\\s*,\\s*")).filter(s -> !s.isBlank()).toList();
        for (String key : keys) {
            MotionSpec spec = BY_KEY.get(key);
            if (spec == null) {
                throw new IllegalStateException(
                        "%s 에 카탈로그에 없는 동작이 있습니다: %s (가능한 값: %s)"
                                .formatted(property, key, BY_KEY.keySet()));
            }
            if (spec.isGift() != gift) {
                throw new IllegalStateException(
                        "%s 에 %s 동작이 섞여 있습니다: %s".formatted(property, gift ? "선물이 아닌" : "선물", key));
            }
        }
        return keys;
    }

    // ── 카탈로그 ──────────────────────────────────────────────────────────

    /** 18종 전부, seq 오름차순. */
    public List<MotionSpec> all() {
        return ALL;
    }

    /** 기본 행동 16종(1·2층), seq 오름차순 = 3층 심화 순서. */
    public List<MotionSpec> basic() {
        return ALL.stream().filter(m -> !m.isGift()).toList();
    }

    /** 선물 2종. */
    public List<MotionSpec> gifts() {
        return ALL.stream().filter(MotionSpec::isGift).toList();
    }

    public Optional<MotionSpec> bySeq(int seq) {
        return Optional.ofNullable(BY_SEQ.get(seq));
    }

    public Optional<MotionSpec> byKey(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }

    /** 기본 행동 16종의 key, seq 순. 부화 후처리 출력 이름과 같아야 한다(application.yml hatch.states.v2). */
    public List<String> basicKeys() {
        return basic().stream().map(MotionSpec::key).toList();
    }

    // ── 굽기 가능한 것(설정) ──────────────────────────────────────────────

    /** 지시문이 있어 밤 큐에 오를 수 있는 3층 동작 key(설정 순서). */
    public List<String> advancedKeys() {
        return advancedKeys;
    }

    /** 지시문이 있는 선물 key. */
    public List<String> giftKeys() {
        return giftKeys;
    }

    /** 이 동작을 지금 구울 수 있나(지시문이 설정에 등록돼 있나). */
    public boolean isBakeable(String key) {
        return advancedKeys.contains(key) || giftKeys.contains(key);
    }

    /**
     * 그 동작의 16프레임 지시문 블록. 카탈로그 key 로만 찾는다 — 모르는 key 는 설정 이름을 말하며 막는다.
     */
    public String block(String key) {
        return blocks.computeIfAbsent(key, k -> {
            MotionSpec spec = BY_KEY.get(k);
            if (spec == null) {
                throw new IllegalArgumentException(
                        "카탈로그에 없는 동작입니다: %s (가능한 값: %s)".formatted(k, BY_KEY.keySet()));
            }
            String path = "zzal/prompt/%s/motions/%s.txt".formatted(version, spec.promptFile());
            try {
                ClassPathResource r = new ClassPathResource(path);
                return new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "동작 지시문이 없습니다: %s (동작 %s — app.zzal.advanced-motions / gift-motions 를 확인)"
                                .formatted(path, k), e);
            }
        });
    }

}
