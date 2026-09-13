package com.lore.zzal.generation.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 움짤 캔버스 크기 읽기.
 *
 * <h3>★ 왜 재는가</h3>
 * 캔버스가 <b>판마다 다르다</b>(실측 295~301 x 321~339). 화면이 상수로 가정하면 어떤 판에서만
 * 그림이 몇 px 어긋나는데, 오류가 안 나서 눈으로 봐야만 드러난다. 그래서 서버가 재서 응답에 싣는다.
 *
 * <h3>★ 왜 손으로 만든 바이트로 시험하나</h3>
 * 진짜 움짤을 만들려면 파이썬·pillow 가 필요하다. 규격(RIFF/VP8X)은 바이트가 전부라, 그 바이트를
 * 직접 세워 두면 <b>파이썬 없이 매번</b> 돌면서도 같은 것을 지킨다.
 */
@DisplayName("webp 캔버스 크기 — 헤더에서 읽는다")
class WebpSizeTest {

    /** 애니메이션 webp 의 머리 부분. 캔버스 크기는 VP8X 청크에 "값-1" 3바이트 LE 로 들어간다. */
    private static byte[] animatedWebp(int width, int height) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write("WEBP".getBytes(StandardCharsets.US_ASCII));
        body.write("VP8X".getBytes(StandardCharsets.US_ASCII));
        body.write(new byte[]{10, 0, 0, 0});                  // 청크 길이 = 10
        body.write(new byte[]{0x12, 0, 0, 0});                // 플래그(애니메이션·알파) + 예약 3바이트
        body.write(le24(width - 1));
        body.write(le24(height - 1));

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        all.write(le32(body.size()));
        all.write(body.toByteArray());
        return all.toByteArray();
    }

    private static byte[] le24(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16)};
    }

    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)};
    }

    @Test
    @DisplayName("★ 판마다 다른 캔버스를 그대로 읽는다")
    void readsTheCanvasOfEachTake(@TempDir Path dir) throws Exception {
        Path a = Files.write(dir.resolve("a.webp"), animatedWebp(295, 321));
        Path b = Files.write(dir.resolve("b.webp"), animatedWebp(301, 339));

        assertThat(WebpSize.read(a)).isEqualTo(new WebpSize.Size(295, 321));
        assertThat(WebpSize.read(b)).isEqualTo(new WebpSize.Size(301, 339));
    }

    @Test
    @DisplayName("★ webp 가 아니면 조용히 넘어가지 않고 멈춘다 — 모르는 크기를 0 으로 채우면 화면이 그걸 믿는다")
    void refusesWhatIsNotWebp(@TempDir Path dir) throws Exception {
        Path junk = Files.writeString(dir.resolve("junk.webp"), "이건 그림이 아니다");

        assertThatThrownBy(() -> WebpSize.read(junk))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("junk.webp");
    }
}
