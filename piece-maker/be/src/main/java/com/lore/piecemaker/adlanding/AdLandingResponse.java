package com.lore.piecemaker.adlanding;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "PieceMakerAdLanding", description = "광고 URL의 최초 서버 수신. 실제 광고 클릭을 외부 검증한 기록은 아님")
public record AdLandingResponse(UUID landingId, Instant landedAt) {
    @Schema(name = "PieceMakerAdLandingClaim")
    public record Claim(boolean linked, Instant landedAt) { }
}
