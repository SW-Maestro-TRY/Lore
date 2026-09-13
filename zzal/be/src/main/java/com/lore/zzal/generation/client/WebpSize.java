package com.lore.zzal.generation.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * webp 파일의 <b>캔버스 크기</b>를 헤더에서 읽는다.
 *
 * <h3>★ 왜 필요한가</h3>
 * 움짤 캔버스는 판마다 다르다(실측 295~301 x 321~339). 화면이 상수로 가정하면 어떤 판에서만
 * 그림이 몇 px 어긋나는데, <b>오류가 안 나서 눈으로 봐야만</b> 드러난다. 그래서 서버가 재서
 * 응답에 싣는다 — 값을 아는 유일한 자리가 후처리 직후의 <b>그 파일</b>이다.
 *
 * <h3>★ 왜 라이브러리를 안 쓰나</h3>
 * 자바 표준 {@code ImageIO} 는 webp 를 못 읽고, 애니메이션 webp 를 읽는 의존성을 하나 더 들이는
 * 것보다 <b>헤더 몇 바이트</b>를 직접 읽는 편이 싸고 확실하다. 규격은 RIFF 컨테이너다.
 *
 * <pre>
 *   "RIFF" [파일크기 4] "WEBP" 다음부터 청크가 이어진다
 *   청크 = FourCC 4 + 크기 4(LE) + 내용(홀수면 1바이트 채움)
 *     VP8X  확장(애니메이션은 항상 이것) — 캔버스 가로-1(3바이트 LE) · 세로-1(3바이트 LE)
 *     VP8   손실 — 동기코드 뒤 14비트씩
 *     VP8L  무손실 — 서명 뒤 14비트씩
 * </pre>
 *
 * ★ 못 읽으면 <b>null 이 아니라 예외</b>다. 크기를 모르는 채 지급하면 화면이 제 값으로 그릴 수
 *   없는데, 조용히 null 로 넘어가면 그 사실이 응답에서만 드러난다.
 */
final class WebpSize {

    /** 가로·세로(px). */
    record Size(int width, int height) {
    }

    private WebpSize() {
    }

    static Size read(Path file) throws IOException {
        // ★ 앞 몇 바이트만 읽지 않는다 — 색 프로파일 같은 청크가 앞에 붙으면 그 길이만큼 건너뛰어야 하고,
        //   그 길이는 미리 알 수 없다. 움짤 한 장은 수백 KB라 통째로 읽어도 싸다.
        byte[] b = Files.readAllBytes(file);
        if (b.length < 16 || !fourCc(b, 0).equals("RIFF") || !fourCc(b, 8).equals("WEBP")) {
            throw new IllegalStateException("webp 가 아닙니다: " + file.getFileName());
        }
        int at = 12;
        while (at + 8 <= b.length) {
            String cc = fourCc(b, at);
            int size = le32(b, at + 4);
            int body = at + 8;
            switch (cc) {
                case "VP8X" -> {
                    require(b, body + 10, file);
                    return new Size(le24(b, body + 4) + 1, le24(b, body + 7) + 1);
                }
                case "VP8L" -> {
                    require(b, body + 5, file);
                    int bits = le32(b, body + 1);
                    return new Size((bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1);
                }
                case "VP8 " -> {
                    require(b, body + 10, file);
                    return new Size(le16(b, body + 6) & 0x3FFF, le16(b, body + 8) & 0x3FFF);
                }
                default -> at = body + size + (size % 2);      // 홀수 크기는 1바이트 채움이 붙는다
            }
            if (size < 0) {
                break;                                          // 깨진 길이 — 무한 반복을 막는다
            }
        }
        throw new IllegalStateException("webp 헤더에서 크기를 못 찾았습니다: " + file.getFileName());
    }

    private static void require(byte[] b, int need, Path file) {
        if (b.length < need) {
            throw new IllegalStateException("webp 헤더가 잘렸습니다: " + file.getFileName());
        }
    }

    private static String fourCc(byte[] b, int at) {
        return new String(b, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int le16(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8);
    }

    private static int le24(byte[] b, int at) {
        return le16(b, at) | ((b[at + 2] & 0xFF) << 16);
    }

    private static int le32(byte[] b, int at) {
        return le24(b, at) | ((b[at + 3] & 0xFF) << 24);
    }
}
