package com.lore.webtoon.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** 함께 주인공 시트의 판 보관·되돌리기(#899) — 첫 주인공 시트와 같은 조작, 판은 조연 시트로 안 읽히는 자리에. */
class RunArtCoSheetTest {
    @TempDir Path runs;

    @Test
    void coSheetVersionsStayOutOfTheCastSheetFolder() throws Exception {
        RunArt art = new RunArt(new HarnessProcess("python3", "/없는/자리", 60, runs.toString(), null));
        Path sheets = runs.resolve("r1").resolve("sheets");
        Files.createDirectories(sheets);
        Files.writeString(sheets.resolve("서진.png"), "1판");
        Files.writeString(sheets.resolve("서진.spec.json"), "{\"v\":1}");

        assertThat(art.archiveSheet("r1", "서진")).isEqualTo(1);
        assertThat(sheets.resolve("서진.png")).doesNotExist();
        assertThat(art.sheetVersions("r1", "서진")).isEqualTo(1);
        // 판은 sheets/versions/ 아래 — sheets/*.png 를 조연 시트로 읽는 하네스·서버가 판을 조연으로 보지 않는다
        try (var list = Files.list(sheets)) {
            assertThat(list.filter(p -> p.toString().endsWith(".png")).count()).isZero();
        }

        Files.writeString(sheets.resolve("서진.png"), "2판");
        art.restoreSheet("r1", "서진", 1);
        assertThat(Files.readString(sheets.resolve("서진.png"))).isEqualTo("1판");
        assertThat(Files.readString(sheets.resolve("서진.spec.json"))).isEqualTo("{\"v\":1}");
        assertThat(art.sheetVersions("r1", "서진")).isEqualTo(2);      // 지금 판(2판)도 잃지 않았다
        // 첫 주인공 시트 판은 따로 센다
        assertThat(art.sheetVersions("r1")).isZero();
        assertThat(art.sheetVersions("r1", null)).isZero();
    }
}
