package com.lore.piecemaker.adlanding;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public final class AdLandingRequests {
    private AdLandingRequests() { }

    @Schema(name = "PieceMakerAdLandingRequest")
    public record Capture(@NotNull UUID requestKey, Long expectedUserId, @NotNull @Valid AdLandingRequests.Attribution attribution) { }

    /** 광고 전용 API의 여섯 필드만 받는다. 공통 이벤트 계약과 공유하지 않는다. */
    @Schema(name = "PieceMakerAdAttribution")
    public record Attribution(String utmSource, String utmMedium, String utmCampaign,
                              String utmContent, String placement, Long firstAdLandedAt) { }

    @Schema(name = "PieceMakerAdLandingClaimRequest")
    public record Claim(@NotNull @Positive Long expectedUserId) { }
}
