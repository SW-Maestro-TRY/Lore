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
import static com.lore.zzal.motion.UnlockRule.Kind.CLEANS;
import static com.lore.zzal.motion.UnlockRule.Kind.FEEDS;
import static com.lore.zzal.motion.UnlockRule.Kind.FIRST_GIFT;
import static com.lore.zzal.motion.UnlockRule.Kind.GAME_STARTS;
import static com.lore.zzal.motion.UnlockRule.Kind.PET_COUNT;
import static com.lore.zzal.motion.UnlockRule.Kind.SECOND_GIFT;
import static com.lore.zzal.motion.UnlockRule.Kind.SNACKS;
import static com.lore.zzal.motion.UnlockRule.Kind.WAKES;

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

    /**
     * 기본 행동 16종 + 선물 2종. 순서 = seq 오름차순 = 격자 칸 순서 = 3층 심화 순서.
     *
     * <h3>★★ 순서를 바꾸지 않는다 — 이 목록이 곧 격자 칸 번호다</h3>
     * 부화 후처리가 격자 한 장을 여덟 칸으로 자를 때 <b>이 순서대로</b> 이름을 붙인다
     * ({@code PostProcessStep} 이 {@code --keys} 로 넘긴다). 한 칸만 밀려도 예외 없이
     * 전부 다른 자세로 저장되고, 그것은 화면을 봐야만 드러난다.
     *
     * <h3>★ 카탈로그에서 빠진 이름을 클래스·enum 에서 지우지 않는다</h3>
     * {@code practice} · {@code call} · {@code tilt} · {@code nod} · {@code smile_idle} ·
     * {@code sit} · {@code wave} · {@code shy} 는 옛 격자로 구워진 펫이 들고 있는 이름이다.
     * 조건 종류({@code UnlockRule.Kind}) 도 마찬가지로 남긴다 — 그 펫들을 계속 설명해야 한다.
     */
    public static final List<MotionSpec> ALL = List.of(
            // 격자 1장 = 1층 8종. 처음부터 전부 열려 있다.
            // ★ 모자란 것은 소품이 때운다 — 자세는 소품이 올 자리를 비워 두고 그린다
            //   (eat 은 입 앞, pet 은 머리 위).
            new MotionSpec(1, "base", "기본", MotionLayer.BASIC_1, UnlockRule.always(), "idle", "기본자세"),
            new MotionSpec(2, "eat", "식사", MotionLayer.BASIC_1, UnlockRule.always(), "eat", "먹기"),
            new MotionSpec(3, "joy", "기쁨", MotionLayer.BASIC_1, UnlockRule.always(), "happy", "기쁜자세"),
            new MotionSpec(4, "sad", "슬픔", MotionLayer.BASIC_1, UnlockRule.always(), "sad", "슬픈자세"),
            new MotionSpec(5, "sick", "아픔", MotionLayer.BASIC_1, UnlockRule.always(), null, "아픈자세"),
            new MotionSpec(6, "pet", "쓰다듬", MotionLayer.BASIC_1, UnlockRule.always(), "pet", "교감자세"),
            new MotionSpec(7, "hello", "인사", MotionLayer.BASIC_1, UnlockRule.always(), null, "인사"),
            new MotionSpec(8, "sleep", "잠", MotionLayer.BASIC_1, UnlockRule.always(), null, "자기"),
            // 격자 2장 = 2층 8종. 여덟 개가 사용자 행동 하나씩에 붙는다.
            //
            // ★★ 해금은 "못 보던 행동이 열리는 것" 이 아니라 "하던 행동이 좋아지는 것" 이다.
            //   조건은 전부 그 행동 자체다 — 다른 행동으로 열리는 자세가 하나도 없다.
            // ★ 약 먹기는 여기 없다. 아픔은 케어를 놓쳤거나 간식을 많이 줬을 때 생기므로,
            //   약 주기를 조건으로 걸면 아이를 아프게 해야 상을 받는 구조가 된다.
            new MotionSpec(9, "eat_rice", "밥 먹기", MotionLayer.BASIC_2, UnlockRule.of(FEEDS, 9), null, "밥먹기"),
            new MotionSpec(10, "eat_snack", "간식 먹기", MotionLayer.BASIC_2, UnlockRule.of(SNACKS, 9), null, "간식먹기"),
            new MotionSpec(11, "sweep", "청소하기", MotionLayer.BASIC_2, UnlockRule.of(CLEANS, 13), null, "청소"),
            new MotionSpec(12, "wash", "목욕하기", MotionLayer.BASIC_2, UnlockRule.of(BATH, 3), null, "씻기"),
            new MotionSpec(13, "reply", "답하기", MotionLayer.BASIC_2, UnlockRule.of(CHAT_ANSWERS, 4), null, "답하기"),
            new MotionSpec(14, "petted", "쓰다듬 받기", MotionLayer.BASIC_2, UnlockRule.of(PET_COUNT, 4), null, "쓰다듬받기"),
            new MotionSpec(15, "startle", "놀람", MotionLayer.BASIC_2, UnlockRule.of(GAME_STARTS, 4), null, "놀라기"),
            new MotionSpec(16, "wake_up", "일어나기", MotionLayer.BASIC_2, UnlockRule.of(WAKES, 4), null, "일어나기"),
            // 선물. 카탈로그 밖 특별 1종씩. 기본 행동 없음(16장: 구르기 먼저, 뒤로 넘어짐이 두 번째).
            new MotionSpec(101, "roll", "구르기", MotionLayer.GIFT, UnlockRule.of(FIRST_GIFT, 0), null, "구르기"),
            new MotionSpec(102, "fall_back", "뒤로 넘어지기", MotionLayer.GIFT, UnlockRule.of(SECOND_GIFT, 0), null, "뒤로넘어짐"));

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

    /** 기본 행동 16종의 key, seq 순. 부화 후처리 출력 이름과 같아야 한다(application.yml hatch.states.v4). */
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
