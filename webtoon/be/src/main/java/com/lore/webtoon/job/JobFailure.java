package com.lore.webtoon.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 작업이 <b>왜</b> 실패했나(#531).
 *
 * 하네스는 실패하면 작품 폴더에 {@code failure.json} 을 남긴다
 * ({@code webtoon/ai/new_harness/failure.py}). 자바는 끝난 코드만 받으므로 전에는
 * 어디서 멈췄는지만 알았다 — 화면에는 「캐릭터 시트를 만들지 못했습니다」만 떴고,
 * 그림이 안전 검사에 걸린 것인지 사진이 없어진 것인지는 서버 로그를 뒤져야 알았다.
 *
 * 두 가지로 쓴다.
 * <ul>
 *   <li>{@link #humanMessage} — 사람에게 무엇이 문제였고 어떻게 하면 되는지</li>
 *   <li>{@code webtoon_job.fail_*} — 개발자가 나중에 모아 볼 원문</li>
 * </ul>
 *
 * @param stage      어느 걸음에서(SHEET · SHEET_IMAGE · PAGE_IMAGE …)
 * @param code       image_safety · photo_missing · error · cancelled · server_restart
 * @param categories 안전 검사가 걸었다고 한 분류(sexual 등). 없으면 빈 목록
 * @param detail     원문 에러와 하네스 출력 끝부분
 */
public record JobFailure(String stage, String code, List<String> categories, String detail) {

    public static final String FILE = "failure.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 하네스가 남긴 이유. 없거나 못 읽으면 비어 있다. */
    static Optional<JobFailure> read(Path runDir) {
        if (runDir == null) {
            return Optional.empty();
        }
        Path file = runDir.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            JsonNode n = MAPPER.readTree(file.toFile());
            List<String> cats = new ArrayList<>();
            n.path("categories").forEach(c -> cats.add(c.asText()));
            return Optional.of(new JobFailure(
                    n.path("stage").asText(null), n.path("code").asText("error"),
                    List.copyOf(cats), n.path("message").asText(null)));
        } catch (Exception e) {          // noqa: 이유를 못 읽어도 원래 실패는 그대로 알린다
            return Optional.empty();
        }
    }

    /** 원문 뒤에 하네스 출력 끝부분을 붙인 것. */
    JobFailure withTail(List<String> tail) {
        if (tail == null || tail.isEmpty()) {
            return this;
        }
        String head = detail == null ? "" : detail + "\n\n";
        return new JobFailure(stage, code, categories, head + "--- 하네스 출력 끝부분 ---\n" + String.join("\n", tail));
    }

    boolean sexual() {
        return categories.stream().anyMatch(c -> c.toLowerCase().contains("sexual"));
    }

    boolean imageSafety() {
        return "image_safety".equals(code);
    }

    /**
     * 시트가 걸려 <b>멈춰 기다릴 때</b> 할 말(#626) — 작업은 안 끝났고 이야기는 남아 있다.
     * 문장은 화면 사전({@code webtoon/fe/screens/progress/i18n.ts})에 그대로 키로 있어야 번역된다.
     */
    String sheetFixMessage() {
        boolean sexual = categories.stream().anyMatch(c -> c.toLowerCase().contains("sexual"));
        if (sexual) {
            return "캐릭터 그림이 이미지 안전 기준(선정성)에 걸렸어요. 적어 주신 이야기는 그대로 있어요. "
                    + "노출이 적은 옷을 입은 사진으로 바꾸거나 옷차림 설명을 고쳐서 캐릭터만 다시 그려 주세요.";
        }
        return "캐릭터 그림이 이미지 안전 기준에 걸렸어요. 적어 주신 이야기는 그대로 있어요. "
                + "다른 사진으로 바꾸거나 설명을 고쳐서 캐릭터만 다시 그려 주세요.";
    }

    /**
     * 사람에게 할 말. 알아볼 수 있는 이유가 아니면 {@code fallback}(어디서 멈췄나)을 그대로 쓴다.
     *
     * 안전 검사는 이미 한 번 스스로 다시 그려 본 뒤에 여기 온다 — 그래서 "다시 누르세요"
     * 가 아니라 <b>무엇을 바꾸면 되는지</b>를 말한다. 문장은 화면 사전
     * ({@code webtoon/fe/screens/progress/i18n.ts})에 그대로 키로 있어야 번역된다.
     */
    String humanMessage(String fallback) {
        if ("photo_missing".equals(code)) {
            return "올린 사진을 찾지 못했어요. 사진을 다시 올려 새로 만들어 주세요.";
        }
        if (!imageSafety()) {
            return fallback;
        }
        boolean sexual = categories.stream().anyMatch(c -> c.toLowerCase().contains("sexual"));
        boolean sheet = stage != null && stage.startsWith("SHEET");
        if (sheet && sexual) {
            return "캐릭터 그림이 이미지 안전 기준(선정성)에 걸렸어요. 한 번 더 그려 봤지만 같았어요. "
                    + "노출이 많은 옷차림이 원인일 수 있으니, 노출이 적은 옷을 입은 사진이나 설명으로 다시 만들어 주세요.";
        }
        if (sheet) {
            return "캐릭터 그림이 이미지 안전 기준에 걸렸어요. 한 번 더 그려 봤지만 같았어요. "
                    + "다른 사진이나 설명으로 다시 만들어 주세요.";
        }
        if (sexual) {
            return "페이지 그림이 이미지 안전 기준(선정성)에 걸렸어요. 한 번 더 그려 봤지만 같았어요. "
                    + "캐릭터 옷차림이나 이야기 속 노출 장면이 원인일 수 있으니, 다른 이야기나 옷차림으로 다시 만들어 주세요.";
        }
        return "페이지 그림이 이미지 안전 기준에 걸렸어요. 한 번 더 그려 봤지만 같았어요. "
                + "다른 이야기로 다시 만들어 주세요.";
    }
}
