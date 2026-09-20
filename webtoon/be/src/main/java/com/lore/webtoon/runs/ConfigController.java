package com.lore.webtoon.runs;

import com.lore.webtoon.WebtoonApi;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 편집실이 매번 새로 받아 가는 작은 설정.
 *
 * <b>지금은 {@code feedback_tags} 하나뿐이다.</b> 원본({@code /api/config})은
 * 화면 스타일 · 세계관 프리셋 · 크레딧 값 등을 훨씬 많이 담지만, 그건 전부
 * 아직 파이썬 화면(캐릭터 만들기 마법사)이 읽는 값이지 <b>이 편집실이 실제로
 * 읽는 값은 아니다</b>(editorCore.ts 는 {@code feedback_tags.scene} 한 줄만
 * 쓴다). 안 쓰는 것까지 옮기면 나중에 원본이 바뀔 때마다 여기도 같이
 * 맞춰야 하는 짐만 늘어난다 — 실제로 읽는 것만 옮긴다.
 *
 * 스위치는 {@code /runs} 와 같다({@link RunController}) — 만들기·읽기가
 * 자바로 넘어간 자리에서만 이 설정도 자바가 준다.
 */
@Tag(name = "Webtoon", description = "웹툰 스튜디오")
@RestController
@ConditionalOnProperty(name = "lore.webtoon.python.direct", havingValue = "true")
public class ConfigController {

    @Operation(summary = "편집실 설정",
            description = "지금은 다시 그리기 창의 feedback_tags 뿐이다.")
    @GetMapping(WebtoonApi.V1 + "/config")
    public Map<String, Object> config() {
        return Map.of("feedback_tags", FeedbackTags.ALL);
    }
}
