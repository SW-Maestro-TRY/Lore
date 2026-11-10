package com.lore.piecemaker.resultview;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import com.lore.piecemaker.metapixel.MetaPixelEvent;

@Schema(name = "PieceMakerResultView", description = "보관 중인 계정의 첫 정상 판정 열람 기록. 생애 최초나 광고 전환을 보장하지 않는다")
public record ResultViewResponse(
        @Schema(description = "이번 요청으로 보관 범위의 첫 기록을 저장했으면 true. 만료 뒤에도 true가 될 수 있다") boolean firstView,
        @Schema(description = "보관 중인 최초 기록 시각(UTC). 보관 중 재열람에는 바뀌지 않는다") Instant viewedAt,
        @Schema(description = "전송 설정이 켜져 있고 대상인 첫 요청에만 발급. 운영자·시험 계정·불완전 이력·재열람은 null", nullable = true)
        MetaPixelEvent metaEvent) {
}
