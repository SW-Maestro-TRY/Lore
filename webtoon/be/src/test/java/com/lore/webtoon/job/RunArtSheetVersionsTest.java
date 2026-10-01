package com.lore.webtoon.job;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시트 판 보관(#548).
 *
 * 다시 그리면 첫 시트가 사라졌다(run 20261001T134352-9819d2). 여기서 못 박는 것은
 * 「치워도 지워지지 않는다」와 「되돌려도 잃는 판이 없다」 둘이다.
 */
class RunArtSheetVersionsTest {

    @TempDir
    Path runs;

    private RunArt art;
    private Path dir;

    @BeforeEach
    void setUp() throws IOException {
        art = new RunArt(new HarnessProcess("python3", "/없는/자리", 60, runs.toString(), null));
        dir = runs.resolve("r1");
        Files.createDirectories(dir);
    }

    private void draw(String png, String spec) throws IOException {
        Files.writeString(dir.resolve("sheet.png"), png);
        Files.writeString(dir.resolve("sheet_spec.json"), spec);
    }

    @Test
    @DisplayName("run_id 가 아직 없으면 0 — 시작 직후 진행 상태 요청이 500 이 되지 않는다")
    void run_id_가_없으면_0() {
        assertThat(art.sheetVersions(null)).isZero();
        assertThat(art.sheetVersions("")).isZero();
    }

    @Test
    @DisplayName("치우면 지워지지 않고 다음 번호로 남는다 — 그림과 사양 같이")
    void 치우면_판으로_남는다() throws IOException {
        assertThat(art.sheetVersions("r1")).isZero();
        assertThat(art.archiveSheet("r1")).isZero();            // 시트가 없으면 아무것도 안 한다

        draw("first", "spec1");
        assertThat(art.archiveSheet("r1")).isEqualTo(1);
        assertThat(art.sheet("r1")).isNull();                   // 하네스가 「이미 있다」며 안 그리는 것을 막는다
        assertThat(dir.resolve("sheet_spec.json")).doesNotExist();
        assertThat(art.sheetVersions("r1")).isEqualTo(1);
        assertThat(Files.readString(art.sheetVersion("r1", 1))).isEqualTo("first");
        assertThat(Files.readString(dir.resolve("sheet_spec.v1.json"))).isEqualTo("spec1");

        draw("second", "spec2");
        assertThat(art.archiveSheet("r1")).isEqualTo(2);
        assertThat(art.sheetVersions("r1")).isEqualTo(2);
    }

    @Test
    @DisplayName("되돌리면 지금 것도 보관한 뒤 바꾼다 — 잃는 판이 없다")
    void 되돌려도_잃는_판이_없다() throws IOException {
        draw("first", "spec1");
        art.archiveSheet("r1");
        draw("second", "spec2");

        art.restoreSheet("r1", 1);

        assertThat(Files.readString(dir.resolve("sheet.png"))).isEqualTo("first");
        assertThat(Files.readString(dir.resolve("sheet_spec.json"))).isEqualTo("spec1");
        assertThat(art.sheetVersions("r1")).isEqualTo(2);      // second 가 2번으로 남았다
        assertThat(Files.readString(art.sheetVersion("r1", 2))).isEqualTo("second");
        assertThat(Files.readString(art.sheetVersion("r1", 1))).isEqualTo("first");   // 판 파일은 그대로다
    }

    @Test
    @DisplayName("없는 판은 못 되돌린다")
    void 없는_판() throws IOException {
        draw("only", "spec");
        assertThat(art.sheetVersion("r1", 1)).isNull();
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () -> art.restoreSheet("r1", 1));
        assertThat(Files.readString(dir.resolve("sheet.png"))).isEqualTo("only");
    }
}
