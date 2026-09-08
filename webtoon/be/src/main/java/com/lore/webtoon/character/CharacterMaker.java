package com.lore.webtoon.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.job.AiHarnessResources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 캐릭터 그림 한 장을 <b>프로그램을 불러서</b> 만든다.
 *
 * 그리는 일은 하네스에 있다({@code new_harness/character.py}) — 외모를 글로
 * 적고 그 글로 그리는 규칙이 이미 거기 있고, 자바로 다시 쓰면 두 벌이 되어
 * 반드시 어긋난다. 웹툰을 만들 때와 같은 방식이다({@code HarnessProcess}).
 *
 * <h2>결과를 어떻게 받나</h2>
 *
 * 진행 상황은 stderr 로, <b>결과 한 줄만 stdout 으로</b> 나온다. 그래서 여기서는
 * 마지막 JSON 한 줄만 읽으면 된다 — 로그를 뒤져 경로를 긁어낼 필요가 없다.
 */
@Component
public class CharacterMaker {

    private static final Logger log = LoggerFactory.getLogger(CharacterMaker.class);

    private final String python;
    private final Path harnessDir;
    private final int timeoutSeconds;
    private final ObjectMapper mapper = new ObjectMapper();

    public CharacterMaker(@Value("${lore.webtoon.python.bin:python3}") String python,
                          @Value("${lore.webtoon.python.harness-dir:}") String harnessDir,
                          @Value("${lore.webtoon.character.timeout-seconds:300}") int timeoutSeconds,
                          AiHarnessResources resources) {
        this.python = python;
        this.harnessDir = (harnessDir == null || harnessDir.isBlank()
                ? resources.newHarnessDir() : Path.of(harnessDir).toAbsolutePath().normalize());
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean ready() {
        return Files.isRegularFile(harnessDir.resolve("character.py"));
    }

    /** 만든 결과. {@code art} 는 그린 파일, {@code source} 는 사진에서인지 글에서인지. */
    /** @param named 사양이 정한 이름. 사람이 안 적었을 때 쓴다. */
    public record Made(Path art, CharacterSource source, String named, List<JsonNode> calls) {
    }

    /**
     * 그린다.
     *
     * @param photo 있으면 읽어서 외모를 적는다. 없으면 이름·설명만으로 —
     *              <b>그 길이 이 기능의 핵심이다</b>(자캐 그림이 없는 사람)
     */
    public Made make(String name, String description, Path photo, String style, Path out)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(
                python, "-u", "character.py",
                "--name", name,
                "--description", description == null ? "" : description,
                "--out", out.toString()));
        if (photo != null) {
            cmd.add("--photo");
            cmd.add(photo.toString());
        }
        if (style != null && !style.isBlank()) {
            cmd.add("--style");
            cmd.add(style);
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(harnessDir.toFile());
        // 진행 상황(stderr)은 흘려보내고 결과(stdout)만 읽는다.
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        log.info("캐릭터를 그립니다: {}", String.join(" ", cmd));
        Process p = pb.start();

        StringBuilder last = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        last.setLength(0);
                        last.append(line);
                    }
                }
            } catch (IOException e) {
                log.warn("캐릭터 결과를 읽다 끊겼습니다", e);
            }
        });

        boolean done = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            reader.join(3_000);
            throw new IllegalStateException("캐릭터 만들기가 너무 오래 걸립니다");
        }
        reader.join(5_000);
        if (p.exitValue() != 0) {
            throw new IllegalStateException("캐릭터를 그리지 못했습니다");
        }

        JsonNode got = mapper.readTree(last.toString());
        Path art = Path.of(got.path("out").asText(""));
        if (!Files.isRegularFile(art)) {
            throw new IllegalStateException("캐릭터를 그리지 못했습니다");
        }
        CharacterSource source = "photo".equals(got.path("source").asText())
                ? CharacterSource.PHOTO : CharacterSource.PROMPT;
        List<JsonNode> calls = new ArrayList<>();
        got.path("calls").forEach(calls::add);
        return new Made(art, source, got.path("named").asText(""), calls);
    }
}
