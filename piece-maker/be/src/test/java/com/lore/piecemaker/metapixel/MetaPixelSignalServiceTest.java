package com.lore.piecemaker.metapixel;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MetaPixelSignalServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MetaPixelSettings enabled = new MetaPixelSettings(true, "123456789", "https://lorecomic.com", true);

    @Test
    void reviewIsRequiredBeforeIssuingAnySignal() {
        assertThat(new MetaPixelSignalService(enabled, jdbc, "", false).forFirstView(10)).isNull();
        verifyNoInteractions(jdbc);
    }

    @Test
    void explicitTestAccountsAreExcludedWithoutQueryingUserData() {
        assertThat(new MetaPixelSignalService(enabled, jdbc, "10, 20", true).forFirstView(10)).isNull();
        verifyNoInteractions(jdbc);
    }

    @Test
    void disabledPixelDoesNotQueryUserData() {
        var disabled = new MetaPixelSettings(false, "123456789", "https://lorecomic.com", true);
        assertThat(new MetaPixelSignalService(disabled, jdbc, "", true).forFirstView(10)).isNull();
        verifyNoInteractions(jdbc);
    }
}
