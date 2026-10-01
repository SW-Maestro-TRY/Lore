package com.lore.webtoon.runs;

import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 내려받는 파일에 <b>LORECOMIC 도장이 실제로 찍히는가</b>.
 *
 * 이 검사가 지키는 것은 유입 경로다. 만든 사람이 결과물을 SNS 에 올릴 때
 * 어디서 만든 것인지가 안 남으면, 퍼질수록 우리는 아무것도 못 얻는다.
 * 도장은 <b>조용히 안 찍히기 쉬운</b> 종류의 기능이라(글꼴이 없거나, 루
 * 그림을 못 읽거나) 그림에서 직접 확인한다.
 *
 * 눈으로 볼 때는 {@code LORE_STAMP_IN=<그림>} · {@code LORE_STAMP_OUT=<저장할 png>}
 * 환경변수를 주고 돌린다 — 실제 장에 도장을 찍어 저장한다.
 */
class EpisodeExportTest {

    private PageStore pages;
    private BakeService bakery;
    private EpisodeExport export;
    private final Map<Integer, BufferedImage> sheets = new LinkedHashMap<>();

    @BeforeEach
    void 세운다() {
        pages = mock(PageStore.class);
        // 구운 것이 없는 작품 — 여기서 보는 것은 잇기와 표시다.
        bakery = mock(BakeService.class);
        when(bakery.keysOf(anyString())).thenReturn(new LinkedHashMap<>());
        S3Storage storage = mock(S3Storage.class);
        // "S3 에서 받는다" 를 대신한다 — 키가 곧 몇 번째 장인가다.
        doAnswer(c -> {
            int no = Integer.parseInt(c.getArgument(0, String.class));
            Path tmp = Files.createTempFile("sheet-", ".png");
            ImageIO.write(sheets.get(no), "png", tmp.toFile());
            Files.copy(tmp, c.getArgument(1, Path.class), StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(tmp);
            return null;
        }).when(storage).download(anyString(), any(Path.class));
        export = new EpisodeExport(pages, bakery, storage);
    }

    /** 한 장 짜 넣는다. 키는 장 번호를 글자로 쓴 것이다. */
    private void sheet(int no, int w, int h, Color fill) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(fill);
        g.fillRect(0, 0, w, h);
        g.dispose();
        sheets.put(no, img);
        Map<Integer, String> keys = new LinkedHashMap<>();
        sheets.keySet().forEach(k -> keys.put(k, String.valueOf(k)));
        when(pages.keysOf(anyString())).thenReturn(keys);
    }

    private BufferedImage exported() throws IOException {
        byte[] png = export.png("run-1");
        assertThat(png).isNotNull();
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    /** 이 자리에 배경(`bg`) 말고 다른 것이 찍혔는가. */
    private static boolean painted(BufferedImage img, int x0, int y0, int x1, int y1, int bg) {
        for (int x = x0; x < x1; x++) {
            for (int y = y0; y < y1; y++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != (bg & 0xFFFFFF)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    @DisplayName("낱장을 잇는다 — 띠를 덧대지 않으니 높이는 장의 합 그대로")
    void 이어_붙인다() throws IOException {
        sheet(1, 800, 500, Color.WHITE);
        sheet(2, 800, 400, Color.WHITE);

        BufferedImage out = exported();

        assertThat(out.getWidth()).isEqualTo(800);
        assertThat(out.getHeight()).isEqualTo(900);
    }

    @Test
    @DisplayName("장마다 오른쪽 아래에 도장을 찍는다 — 한 장만 잘라 가도 딸려 가야 한다")
    void 장마다_찍는다() throws IOException {
        sheet(1, 800, 500, Color.WHITE);
        sheet(2, 800, 500, Color.WHITE);

        BufferedImage out = exported();

        // 두 장 각자의 오른쪽 아래(폭의 17% 남짓)에 루와 글자가 찍혀 있어야 한다.
        assertThat(inked(out, 600, 350, 800, 500, 0xFFFFFF)).as("첫 장 도장").isGreaterThan(1500);
        assertThat(inked(out, 600, 850, 800, 1000, 0xFFFFFF)).as("둘째 장 도장").isGreaterThan(1500);
        // 왼쪽 위는 깨끗하다 — 도장은 구석에만.
        assertThat(inked(out, 0, 0, 600, 350, 0xFFFFFF)).as("첫 장 나머지").isZero();
    }

    @Test
    @DisplayName("어두운 장에서도 보인다 — 흰 글자에 짙은 테두리")
    void 어두운_장에서도() throws IOException {
        sheet(1, 800, 500, Color.BLACK);

        BufferedImage out = exported();

        assertThat(inked(out, 600, 350, 800, 500, 0x000000)).as("어두운 장 도장").isGreaterThan(1500);
    }

    @Test
    @DisplayName("컷 하나만 받아도 같은 도장이 찍힌다")
    void 컷_하나() throws IOException {
        sheet(1, 800, 500, Color.WHITE);

        byte[] png = export.pagePng("run-1", 1);
        assertThat(png).isNotNull();
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(png));

        assertThat(out.getWidth()).isEqualTo(800);
        assertThat(out.getHeight()).isEqualTo(500);
        assertThat(inked(out, 600, 350, 800, 500, 0xFFFFFF)).isGreaterThan(1500);
    }

    @Test
    @DisplayName("좁은 장에도 찍힌다")
    void 좁은_장() throws IOException {
        sheet(1, 320, 200, Color.WHITE);

        BufferedImage out = exported();

        assertThat(inked(out, 200, 100, 320, 200, 0xFFFFFF)).isGreaterThan(300);
    }

    @Test
    @DisplayName("환경변수를 주면 실제 장에 도장을 찍어 저장한다 (눈으로 보는 용도)")
    void 눈으로_보기() throws IOException {
        String in = System.getenv("LORE_STAMP_IN"), to = System.getenv("LORE_STAMP_OUT");
        if (in == null || to == null) {
            return;
        }
        BufferedImage page = ImageIO.read(Path.of(in).toFile());
        BufferedImage out = EpisodeExport.stitch(java.util.List.of(page));
        export.stampEachSheet(out, java.util.List.of(page));
        Files.createDirectories(Path.of(to).toAbsolutePath().getParent());
        ImageIO.write(out, "png", Path.of(to).toFile());
    }

    /** 이 상자 안에서 배경(`bg`)이 아닌 픽셀 수. */
    private static int inked(BufferedImage img, int x0, int y0, int x1, int y1, int bg) {
        int n = 0;
        for (int y = y0; y < Math.min(y1, img.getHeight()); y++) {
            for (int x = x0; x < Math.min(x1, img.getWidth()); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) != (bg & 0xFFFFFF)) {
                    n++;
                }
            }
        }
        return n;
    }

    @Test
    @DisplayName("폭이 다른 장은 가장 넓은 폭에 맞춰 가운데로 — 자르지도 늘리지도 않는다")
    void 폭이_다를_때() throws IOException {
        sheet(1, 800, 300, Color.WHITE);
        sheet(2, 600, 300, Color.RED);

        BufferedImage out = exported();

        assertThat(out.getWidth()).isEqualTo(800);
        // 좁은 장은 (800-600)/2 = 100 만큼 들여서 놓인다.
        assertThat(new Color(out.getRGB(50, 450))).isEqualTo(Color.WHITE);
        assertThat(new Color(out.getRGB(400, 450))).isEqualTo(Color.RED);
    }

    @Test
    @DisplayName("그림이 없으면 파일도 없다 — 부르는 쪽이 404 를 낸다")
    void 그림이_없으면() {
        when(pages.keysOf(anyString())).thenReturn(new LinkedHashMap<>());
        assertThat(export.png("run-1")).isNull();
    }
}
