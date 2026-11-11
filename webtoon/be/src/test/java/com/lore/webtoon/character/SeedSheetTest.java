package com.lore.webtoon.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.credit.CreditGate;

/** 기억해 둔 시트를 run 폴더에 심는 규칙 — 체험은 짤 시트만 심는다(2026-11-11). */
class SeedSheetTest {

    @TempDir Path dir;
    WebtoonCharacterRepository repo;
    PrivateArt art;
    CharacterService service;

    @BeforeEach
    void setUp() {
        repo = mock(WebtoonCharacterRepository.class);
        art = mock(PrivateArt.class);
        when(art.read("private/webtoon/char/sheet.png")).thenReturn(new byte[] {1, 2, 3});
        service = new CharacterService(repo, mock(CharacterMaker.class), mock(CharacterOwner.class), art,
                mock(CreditGate.class), mock(ShareReward.class), dir.toString(), 3, 2, false, Clock.systemUTC());
    }

    private void character(String mode) {
        WebtoonCharacter one = WebtoonCharacter.drawing("c1", 1L, null, "흑설", "", Instant.now());
        one.drewArt("private/webtoon/char/sheet.png", CharacterSource.UPLOAD, Instant.now());
        if (mode != null) {
            one.keepSheet("private/webtoon/char/sheet.png", null, mode, Instant.now());
        }
        when(repo.findByPublicId("c1")).thenReturn(Optional.of(one));
    }

    @Test
    @DisplayName("짤에서 시트째 가져온 캐릭터는 체험에서도 시트를 심는다")
    void zzalSheetSeedsInPlay() throws Exception {
        character(ZzalCharacterImport.SHEET_MODE);
        Path run = dir.resolve("run-play");
        assertThat(service.seedSheet("c1", true, run)).isTrue();
        assertThat(Files.readAllBytes(run.resolve("sheet.png"))).containsExactly(1, 2, 3);
        assertThat(run.resolve("sheet_spec.json")).doesNotExist();   // 사양은 하네스가 글로만 적는다
    }

    @Test
    @DisplayName("작품에서 기억한 시트는 체험에서 안 심는다 — 옷을 세계에 맞게 새로 그린다")
    void workSheetSkipsPlay() {
        character("work");
        assertThat(service.seedSheet("c1", true, dir.resolve("run-play"))).isFalse();
        assertThat(service.seedSheet("c1", false, dir.resolve("run-webtoon"))).isTrue();
    }

    @Test
    @DisplayName("시트가 없으면 안 심는다")
    void noSheet() {
        character(null);
        assertThat(service.seedSheet("c1", false, dir.resolve("run"))).isFalse();
    }
}
