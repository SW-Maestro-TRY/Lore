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
 * 내려받는 파일에 <b>LORE 띠가 실제로 붙는가</b>.
 *
 * 이 검사가 지키는 것은 유입 경로다. 만든 사람이 결과물을 SNS 에 올릴 때
 * 어디서 만든 것인지가 안 남으면, 퍼질수록 우리는 아무것도 못 얻는다.
 * 띠는 <b>조용히 빈 채로 나가기 쉬운</b> 종류의 기능이라(글꼴이 없거나, 루
 * 그림을 못 읽거나) 그림에서 직접 확인한다.
 *
 * 눈으로 볼 때는 {@code LORE_BAND_IN=<그림>} · {@code LORE_BAND_OUT=<저장할 png>}
 * 환경변수를 주고 돌린다 — 실제 장으로 띠를 만들어 저장한다.
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
    @DisplayName("낱장을 잇고 그 아래에 브랜드 띠를 덧댄다")
    void 이어_붙이고_띠를_단다() throws IOException {
        sheet(1, 800, 500, Color.WHITE);
        sheet(2, 800, 400, Color.WHITE);

        BufferedImage out = exported();

        assertThat(out.getWidth()).isEqualTo(800);
        // 그림 900 + 띠. 띠는 폭의 11.5% 이되 88~240 사이다.
        assertThat(out.getHeight()).isEqualTo(900 + 92);
        // 그림과 띠 사이의 가는 선 — 띠가 작품의 일부로 안 읽히게 두는 경계다.
        assertThat(new Color(out.getRGB(400, 900))).isEqualTo(new Color(161, 198, 187));
    }

    @Test
    @DisplayName("띠 안에 루와 글자가 찍힌다 — 리소스를 못 찾으면 빈 띠만 나간다")
    void 띠에_루와_글자() throws IOException {
        sheet(1, 800, 500, Color.WHITE);

        BufferedImage out = exported();

        // 띠(500 아래)에 종이색이 아닌 픽셀이 충분히 있어야 한다. 글자만 있으면
        // 수백, 루까지 있으면 수천이다.
        assertThat(inked(out, 502, 0xFFFDF7)).as("띠에 찍힌 픽셀").isGreaterThan(2000);
    }

    @Test
    @DisplayName("장 위에는 아무것도 찍지 않는다 — 표시는 아래 띠 하나뿐이다")
    void 장은_그대로() throws IOException {
        sheet(1, 800, 500, Color.WHITE);
        sheet(2, 800, 500, Color.WHITE);

        BufferedImage out = exported();

        assertThat(painted(out, 0, 0, 800, 1000, 0xFFFFFF)).as("장 위 표시").isFalse();
    }

    @Test
    @DisplayName("좁은 그림에도 띠가 붙는다")
    void 좁은_그림() throws IOException {
        sheet(1, 320, 200, Color.WHITE);

        BufferedImage out = exported();

        assertThat(out.getWidth()).isEqualTo(320);
        assertThat(inked(out, 202, 0xFFFDF7)).isGreaterThan(500);
    }

    @Test
    @DisplayName("환경변수를 주면 실제 장으로 띠를 만들어 저장한다 (눈으로 보는 용도)")
    void 눈으로_보기() throws IOException {
        String in = System.getenv("LORE_BAND_IN"), to = System.getenv("LORE_BAND_OUT");
        if (in == null || to == null) {
            return;
        }
        BufferedImage page = ImageIO.read(Path.of(in).toFile());
        BufferedImage out = export.withBand(page);
        Files.createDirectories(Path.of(to).toAbsolutePath().getParent());
        ImageIO.write(out, "png", Path.of(to).toFile());
    }

    /** `fromY` 아래에서 배경(`bg`)이 아닌 픽셀 수. */
    private static int inked(BufferedImage img, int fromY, int bg) {
        int n = 0;
        for (int y = fromY; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
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
