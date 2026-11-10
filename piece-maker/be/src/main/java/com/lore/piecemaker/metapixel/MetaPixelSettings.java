package com.lore.piecemaker.metapixel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;

/** PM 전용 전송 설정. 미설정·잘못된 ID나 주소는 다른 서비스의 기동을 막지 않고 전송만 끈다. */
@Component
public class MetaPixelSettings {
    private final boolean enabled;
    private final String pixelId;
    private final String siteOrigin;

    public MetaPixelSettings(
            @Value("${lore.piece-maker.meta-pixel.enabled:false}") boolean enabled,
            @Value("${lore.piece-maker.meta-pixel.id:}") String pixelId,
            @Value("${lore.piece-maker.meta-pixel.site-origin:}") String siteOrigin,
            @Value("${app.analytics.enabled:true}") boolean analyticsEnabled) {
        this.pixelId = pixelId.strip();
        this.siteOrigin = siteOrigin.strip();
        this.enabled = enabled && analyticsEnabled && this.pixelId.matches("[0-9]{5,32}")
                && validOrigin(this.siteOrigin);
    }

    public boolean enabled() { return enabled; }
    public String pixelId() { return pixelId; }
    public String siteOrigin() { return siteOrigin; }

    private static boolean validOrigin(String text) {
        try {
            var uri = URI.create(text);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getPort() == -1;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
