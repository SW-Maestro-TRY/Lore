package com.lore.piecemaker.metapixel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MetaPixelSettingsTest {
    @Test
    void needsBothSwitchesAndAValidConfiguration() {
        assertThat(new MetaPixelSettings(false, "123456789", "https://lorecomic.com", true).enabled()).isFalse();
        assertThat(new MetaPixelSettings(true, "123456789", "https://lorecomic.com", false).enabled()).isFalse();
        assertThat(new MetaPixelSettings(true, "123456789", "https://lorecomic.com", true).enabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123", "pixel12345", "12345<script>", "12345678901234567890123456789012345"})
    void invalidPixelDisablesTransmission(String id) {
        assertThat(new MetaPixelSettings(true, id, "https://lorecomic.com", true).enabled()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://lorecomic.com", "javascript:alert(1)", "https://lorecomic.com/",
            "https://lorecomic.com/piece-maker", "https://user@lorecomic.com", "https://lorecomic.com?q=1",
            "https://lorecomic.com#fragment", "https://lorecomic.com:443", "https://"})
    void invalidOriginDisablesTransmission(String origin) {
        assertThat(new MetaPixelSettings(true, "123456789", origin, true).enabled()).isFalse();
    }
}
