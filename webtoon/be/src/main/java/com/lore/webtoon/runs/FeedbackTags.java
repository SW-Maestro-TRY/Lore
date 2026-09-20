package com.lore.webtoon.runs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 다시 그리기 창에서 고르는 「무엇이 별로였나」 항목표.
 *
 * <h2>화면이 목록을 안 들고 있게 한다</h2>
 *
 * 항목을 늘리거나 말을 바꿀 때 서버 한 군데만 고치면 되게 하려고 여기 둔다 —
 * 화면(편집실)이 이 값을 매번 {@code GET /config} 로 받아 간다.
 *
 * <b>파이썬(landing/pipeline.py 의 FEEDBACK_TAGS)과 같은 값이어야 한다.</b>
 * 다시 그리기는 지금 편집실(장 단위, {@code scene})만 쓰지만, 나머지 세
 * 단계(시트·이야기·콘티)도 언젠가 같은 화면 부품을 쓸 것이라 같이 옮겨 둔다 —
 * 절반만 옮기면 다음에 또 여기부터 찾아야 한다.
 */
final class FeedbackTags {

    static final Map<String, List<Map<String, String>>> ALL = build();

    private FeedbackTags() {
    }

    private static Map<String, List<Map<String, String>>> build() {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        out.put("sheet", tags(
                "face", "얼굴이 원본과 달라요",
                "outfit", "옷·장신구가 달라요",
                "hair", "머리 모양이 달라요",
                "prop", "소품(무기·모자 등)이 달라요",
                "age", "나이대가 안 맞아요",
                "style", "그림체가 생각과 달라요",
                "ratio", "등신 비율이 안 맞아요",
                "etc", "기타"));
        out.put("story", tags(
                "genre", "고른 장르 느낌이 안 나요",
                "personality", "성격이 설정과 달라요",
                "logic", "개연성이 없어요",
                "line", "대사가 어색해요",
                "name", "이름이 잘못 나와요",
                "pace", "전개가 급하거나 지루해요",
                "etc", "기타"));
        out.put("board", tags(
                "flow", "컷 흐름이 끊겨요",
                "missing", "중요한 장면이 빠졌어요",
                "angle", "컷 앵글이 어색해요",
                "balance", "컷 분량 배분이 이상해요",
                "line", "대사 배치가 어색해요",
                "etc", "기타"));
        out.put("scene", tags(
                "character", "캐릭터가 이상해요",
                "background", "배경이 이상해요",
                "pose", "포즈가 어색해요",
                "face", "표정이 안 맞아요",
                "text", "글자가 깨져요",
                "light", "색·조명이 별로예요",
                "artifact", "이상한 게 그려졌어요",
                "etc", "기타"));
        return out;
    }

    /** {id, label, id, label, ...} 짝을 표로. */
    private static List<Map<String, String>> tags(String... idLabel) {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        for (int i = 0; i < idLabel.length; i += 2) {
            out.add(Map.of("id", idLabel[i], "label", idLabel[i + 1]));
        }
        return out;
    }
}
