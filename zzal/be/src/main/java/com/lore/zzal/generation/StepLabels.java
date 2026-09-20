package com.lore.zzal.generation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 단계 이름 → 화면에 보여줄 말.
 *
 * ★ 단계 스스로가 자기 문구를 갖고 있으므로(GenerationStep.label) 여기서는 모아두기만 한다.
 *   단계가 새로 생기면 그 클래스 안에 문구가 있고, 이 표는 자동으로 따라온다.
 */
@Component
public class StepLabels {

    private final Map<String, String> labels;

    public StepLabels(List<GenerationStep> steps) {
        this.labels = steps.stream().collect(
                Collectors.toMap(GenerationStep::name, GenerationStep::label, (a, b) -> a));
    }

    public String label(String stepName) {
        return labels.getOrDefault(stepName, "준비하는 중");
    }
}
