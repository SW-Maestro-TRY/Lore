package com.lore.piecemaker.metapixel;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "PieceMakerMetaPixelEvent", description = "브라우저가 한 번 시도할 PM 전용 이벤트. Meta 수신이나 광고 기여를 뜻하지 않는다")
public record MetaPixelEvent(String pixelId, String siteOrigin, String eventName, String eventId) { }
