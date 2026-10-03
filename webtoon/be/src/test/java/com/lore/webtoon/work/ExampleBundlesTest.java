package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예시 번들 읽기·쓰기(#614) — <b>받은 zip 은 믿지 않는다.</b>
 *
 * 경로 이탈 · 스크립트 파일 · JPEG 아닌 그림 · 용량 폭탄을 막는지, 쓴 것을 그대로 읽는지 본다.
 */
class ExampleBundlesTest {

    @TempDir
    Path tmp;

    static final String RUN = "20261003T133334-b5ccce";

    static byte[] jpeg() throws IOException {
        BufferedImage img = new BufferedImage(12, 18, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    static byte[] png() throws IOException {
        BufferedImage img = new BufferedImage(12, 18, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static final String MANIFEST = """
            {"run_id": "%s", "title": "카페 사장에게는 비밀이 많다", "genre": "느와르", "character": "레나",
             "style": "frost", "logline": "한 줄", "captions": ["둘째 장", "셋째 장"],
             "input": {"name": "레나", "photos": 0}}
            """.formatted(RUN);

    private Path zip(Map<String, byte[]> files) throws IOException {
        Path file = tmp.resolve("b-" + System.nanoTime() + ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var e : files.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private Map<String, byte[]> good() throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", MANIFEST.getBytes(StandardCharsets.UTF_8));
        files.put("pages/p01-w320.jpg", jpeg());
        files.put("pages/p01-w1080.jpg", jpeg());
        return files;
    }

    @Test
    @DisplayName("쓴 번들을 그대로 읽는다 — 설명 · 그림 · 만든 과정, 사진 경로는 비워서")
    void 쓰고_읽는다() throws Exception {
        Map<String, byte[]> run = new LinkedHashMap<>();
        run.put("input.json", "{\"name\":\"레나\",\"photos\":[\"/opt/lore/jobs/x/photo1.png\"]}".getBytes(StandardCharsets.UTF_8));
        run.put("scenes.json", "{\"scenes\":[]}".getBytes(StandardCharsets.UTF_8));
        ExampleBundle bundle = new ExampleBundle(
                new ExampleBundle.Manifest(RUN, "제목", "장르", "레나", "frost", "줄거리", List.of("a", "b"),
                        Map.of("name", "레나")),
                List.of(new ExampleBundle.Page(1, 320, jpeg()), new ExampleBundle.Page(1, 1080, jpeg())), run);
        Path file = tmp.resolve("out.zip");
        try (var out = Files.newOutputStream(file)) {
            ExampleBundles.writeZip(bundle, out);
        }

        ExampleBundle got = ExampleBundles.fromZip(file);

        assertThat(got.manifest().runId()).isEqualTo(RUN);
        assertThat(got.manifest().title()).isEqualTo("제목");
        assertThat(got.manifest().captions()).containsExactly("a", "b");
        assertThat(got.manifest().input()).containsEntry("name", "레나");
        assertThat(got.pages()).extracting(ExampleBundle.Page::width).containsExactly(320, 1080);
        assertThat(got.runFiles()).containsKeys("input.json", "scenes.json");
        assertThat(new String(got.runFiles().get("input.json"), StandardCharsets.UTF_8))
                .doesNotContain("/opt/lore").contains("\"photos\"");
    }

    @Test
    @DisplayName("저장소 예시 폴더(meta.json)도 번들로 읽는다 — 폴더 이름이 작품 번호")
    void 옛_폴더를_읽는다() throws Exception {
        Path folder = Files.createDirectories(tmp.resolve(RUN));
        Files.writeString(folder.resolve("meta.json"), "{\"title\":\"예전 예시\",\"genre\":\"로맨스\",\"captions\":[]}");
        Files.write(folder.resolve("p01-w320.jpg"), jpeg());
        Files.write(folder.resolve("p01-w1080.jpg"), jpeg());

        ExampleBundle got = ExampleBundles.fromFolder(folder);

        assertThat(got.manifest().runId()).isEqualTo(RUN);
        assertThat(got.pages()).hasSize(2);
        assertThat(got.runFiles()).isEmpty();
    }

    @Test
    @DisplayName("경로를 벗어나는 파일 이름은 거절한다")
    void 경로_이탈은_거절() throws Exception {
        Map<String, byte[]> files = good();
        files.put("run/../../etc/passwd.txt", new byte[]{1});
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(files)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("올바르지 않");

        Map<String, byte[]> abs = good();
        abs.put("/tmp/evil.txt", new byte[]{1});
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(abs))).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("run/ 에는 글·JSON·그림만 둘 수 있다 — 스크립트는 거절")
    void 스크립트는_거절() throws Exception {
        Map<String, byte[]> files = good();
        files.put("run/run.sh", "rm -rf /".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(files)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("둘 수 없는 파일");
    }

    @Test
    @DisplayName("JPEG 가 아닌 그림은 거절한다 — 확장자만 jpg 인 PNG 포함")
    void 가짜_그림은_거절() throws Exception {
        Map<String, byte[]> files = good();
        files.put("pages/p02-w320.jpg", png());
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(files)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("JPEG");

        Map<String, byte[]> broken = good();
        broken.put("pages/p02-w320.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3});
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(broken))).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("manifest · 제목 · 올바른 작품 번호 · 그림이 하나라도 있어야 한다")
    void 꼭_있어야_하는_것() throws Exception {
        Map<String, byte[]> noManifest = good();
        noManifest.remove("manifest.json");
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(noManifest))).hasMessageContaining("manifest");

        Map<String, byte[]> badId = good();
        badId.put("manifest.json", MANIFEST.replace(RUN, "../x").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(badId))).hasMessageContaining("작품 번호");

        Map<String, byte[]> noTitle = good();
        noTitle.put("manifest.json", MANIFEST.replace("카페 사장에게는 비밀이 많다", " ").getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(noTitle))).hasMessageContaining("제목");

        Map<String, byte[]> noPages = new LinkedHashMap<>();
        noPages.put("manifest.json", MANIFEST.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(noPages))).hasMessageContaining("그림");
    }

    @Test
    @DisplayName("용량 폭탄: 압축하면 작아도 풀린 크기가 상한을 넘으면 거절한다")
    void 용량_폭탄은_거절() throws Exception {
        Map<String, byte[]> files = good();
        files.put("run/big.txt", new byte[13 * 1024 * 1024]);    // 0 으로 채워 압축하면 몇 KB
        Path zip = zip(files);
        assertThat(Files.size(zip)).isLessThan(1024 * 1024);

        assertThatThrownBy(() -> ExampleBundles.fromZip(zip))
                .isInstanceOf(BusinessException.class).hasMessageContaining("너무 큽니다");
    }

    @Test
    @DisplayName("항목이 너무 많으면 거절한다")
    void 항목_수_상한() throws Exception {
        Map<String, byte[]> files = good();
        for (int i = 0; i < ExampleBundles.MAX_ENTRIES + 1; i++) {
            files.put("run/f" + i + ".txt", new byte[]{1});
        }
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(files))).hasMessageContaining("너무 많습니다");
    }

    @Test
    @DisplayName("같은 쪽·폭이 두 번이면 거절한다")
    void 중복은_거절() throws Exception {
        Map<String, byte[]> files = good();
        files.put("p01-w320.jpg", jpeg());                      // pages/ 밖에 같은 쪽이 또 있다
        assertThatThrownBy(() -> ExampleBundles.fromZip(zip(files))).hasMessageContaining("두 번");
    }
}
