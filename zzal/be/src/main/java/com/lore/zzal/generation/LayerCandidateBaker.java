package com.lore.zzal.generation;

import com.lore.zzal.generation.client.PostProcessor;
import com.lore.zzal.generation.steps.GridStep;
import com.lore.zzal.generation.steps.PostProcessStep;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionImageKeys;
import com.lore.zzal.motion.MotionLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 관리자가 올린 격자 후보 한 장을 <b>부화와 같은 후처리</b>(게이트 + 자르기)에 태워 임시 자리에 둔다(#696).
 *
 * <h3>★ 같은 스크립트·같은 인자</h3>
 * 1층은 {@code PostProcessStep}, 2층은 {@code Layer2PostStep} 이 쓰는 것과 같은 키·자세 매핑·{@code --normalize} 로 자른다.
 * 2층은 지금 정식 판의 {@code anchors.json}(1층 K·Hw)을 먼저 깐다 — 그래서 후보의 그림·앵커가 자동 굽기와 같은 바이트가 된다.
 *
 * <h3>★ 임시 자리</h3>
 * {@code images/zzal/pets/{id}/candidates/layer{1|2}/{후보id}/} — 고르기 전까지 사용자 응답은 이 주소를 모른다.
 */
@Component
public class LayerCandidateBaker {

    private static final Logger log = LoggerFactory.getLogger(LayerCandidateBaker.class);

    /** 게이트·후처리 결과. */
    public enum Gate {
        /** 통과 — 8종 webp 와 앵커가 임시 자리에 있다. 고를 수 있다. */
        PASS,
        /** 격자 구조 게이트가 막았다(GRID_STRUCTURE_INVALID). */
        REJECTED,
        /** 후처리 스크립트가 0 이 아닌 코드로 끝났다(빈 칸 등). */
        CRASHED,
        /** 그 밖의 실패(내려받기·시간 초과 등). */
        ERROR
    }

    public record Result(Gate gate, String message, Map<String, String> previewKeys) {
    }

    private final PostProcessor postProcessor;
    private final MotionCatalog catalog;
    private final HatchPostures postures;

    public LayerCandidateBaker(PostProcessor postProcessor, MotionCatalog catalog, HatchPostures postures) {
        this.postProcessor = postProcessor;
        this.catalog = catalog;
        this.postures = postures;
    }

    /** 후보 하나가 놓이는 자리. */
    public static String prefix(long petId, int layer, String candidateId) {
        return "images/zzal/pets/%d/candidates/layer%d/%s".formatted(petId, layer, candidateId);
    }

    /** 그 층의 key 8개(격자 칸 순서). */
    public List<String> keys(int layer) {
        return PostProcessStep.keysOf(catalog, layer == 1 ? MotionLayer.BASIC_1 : MotionLayer.BASIC_2);
    }

    /**
     * @param baseRound 2층이면 앵커를 이어받을 지금 정식 판(0 이면 이어받을 것이 없어 실패). 1층이면 무시
     */
    public Result bake(long petId, int layer, String candidateId, String gridKey, String version, int baseRound) {
        String prefix = prefix(petId, layer, candidateId);
        List<String> keys = keys(layer);
        String step = layer == 1 ? GridStep.NAME : PostProcessStep.GRID2;
        try (PostProcessor.Session session = postProcessor.open(prefix, version)) {
            if (layer == 2) {
                if (baseRound <= 0) {
                    return new Result(Gate.ERROR, "1층 판이 없어 2층 앵커를 이어받을 수 없습니다", Map.of());
                }
                session.seedAnchors(MotionImageKeys.anchors(petId, baseRound));
            }
            PostProcessStep.splitTagged(session, step, gridKey,
                    layer == 1 ? MotionLayer.BASIC_1 : MotionLayer.BASIC_2, version, keys, postures);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            Gate gate = msg.contains(GenerationRunner.GRID_STRUCTURE_MARK) ? Gate.REJECTED
                    : msg.contains(GenerationRunner.POSTPROCESS_EXIT_MARK) ? Gate.CRASHED : Gate.ERROR;
            log.info("후보 후처리 실패 — petId={} layer={} 후보={} {} : {}", petId, layer, candidateId, gate, firstLines(msg));
            return new Result(gate, summarize(msg), Map.of());
        }
        Map<String, String> preview = new LinkedHashMap<>();
        for (String k : keys) {
            preview.put(k, "%s/%s.webp".formatted(prefix, k));
        }
        log.info("후보 후처리 통과 — petId={} layer={} 후보={}", petId, layer, candidateId);
        return new Result(Gate.PASS, null, preview);
    }

    private static String summarize(String msg) {
        List<String> lines = msg.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        for (String l : lines) {
            if (l.contains(GenerationRunner.GRID_STRUCTURE_MARK)) {
                return cut(l);
            }
        }
        String head = lines.isEmpty() ? msg : lines.get(0);
        String tail = lines.size() > 1 ? lines.get(lines.size() - 1) : "";
        return cut(tail.isEmpty() ? head : head + " / " + tail);
    }

    private static String firstLines(String msg) {
        return cut(msg.replace('\n', ' '));
    }

    private static String cut(String s) {
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
