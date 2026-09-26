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

    /** 「랜덤으로 만들어보기」 재료. 하네스 프롬프트 옆에 데이터로 둔다 — 코드가 아니다. */
    public Path randomPoolFile() {
        return harnessDir.resolve("prompt").resolve("random_pool.json");
    }

    /** 세계관 프리셋 파일. new_harness 옆에 story-harness 가 같이 풀려 있다. */
    public Path worldsFile() {
        return harnessDir.getParent() == null ? null
                : harnessDir.getParent().resolve("story-harness").resolve("worlds.json");
    }

    /**
     * 만든 결과. {@code art} 는 그린 파일, {@code source} 는 사진에서인지 글에서인지.
     *
     * @param named 사양이 정한 이름. 사람이 안 적었을 때 쓴다
     * @param card  「캐릭터 만들어보기」(한 컷)일 때만 있다. 초상 한 장이면 {@code null}
     */
    public record Made(Path art, CharacterSource source, String named, List<JsonNode> calls,
                       WebtoonCharacter.Card card) {
    }

    /**
     * 그린다.
     *
     * @param photos 있으면 읽어서 외모를 적는다(최대 4장, 같은 사람의 다른
     *              각도·표정). 비어 있으면 이름·설명만으로 — <b>그 길이 이
     *              기능의 핵심이다</b>(자캐 그림이 없는 사람)
     */
    public Made make(String name, String description, List<Path> photos, String style, Path out)
            throws IOException, InterruptedException {
        return run(name, description, photos, style, out, null);
    }

    /**
     * 「캐릭터 만들어보기」 — 그 세계관 웹툰의 <b>한 컷</b>과 카드 글.
     *
     * 사진·설명·이름이 전부 없어도 된다. {@code world} 는 프리셋 키이거나 사람이
     * 직접 쓴 한 줄이고, 비우면 하네스가 프리셋에서 무작위로 고른다. 규칙은
     * 전부 하네스({@code character.py --panel} · {@code prompt/panel_prompt})에
     * 있다 — 자바는 넘기고 받기만 한다.
     */
    public Made makePanel(String name, String description, List<Path> photos, String world,
                          Path out) throws IOException, InterruptedException {
        return run(name, description, photos, null, out, world == null ? "" : world);
    }

    private Made run(String name, String description, List<Path> photos, String style, Path out,
                     String world) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(
                python, "-u", "character.py",
                "--name", name == null ? "" : name,
                "--description", description == null ? "" : description,
                "--out", out.toString()));
        for (Path photo : photos) {
            cmd.add("--photo");
            cmd.add(photo.toString());
        }
        if (style != null && !style.isBlank()) {
            cmd.add("--style");
            cmd.add(style);
        }
        if (world != null) {                       // null = 초상 한 장, "" = 한 컷(세계관 무작위)
            cmd.add("--panel");
            if (!world.isBlank()) {
                cmd.add("--world");
                cmd.add(world);
            }
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
        return new Made(art, source, got.path("named").asText(""), calls, cardOf(got));
    }

    /** 한 컷 결과의 카드 글. 반전 한 줄이 없으면 카드가 아니다(초상 한 장). */
    static WebtoonCharacter.Card cardOf(JsonNode got) {
        String twist = got.path("twist").asText("");
        if (twist.isBlank()) {
            return null;
        }
        List<String> fate = new ArrayList<>();
        got.path("fate").forEach(f -> {
            if (!f.asText("").isBlank()) {
                fate.add(f.asText());
            }
        });
        List<WebtoonCharacter.DialogueLine> dialogue = new ArrayList<>();
        got.path("dialogue").forEach(d -> {
            String text = d.path("text").asText("");
            if (!text.isBlank()) {
                dialogue.add(new WebtoonCharacter.DialogueLine(
                        d.path("who").asText(""), d.path("mine").asBoolean(false),
                        d.path("side").asText("center"), text));
            }
        });
        return new WebtoonCharacter.Card(
                got.path("world").asText(""),
                got.path("world_label").asText(""),
                got.path("genre").asText(""),
                got.path("role").asText(""),
                got.path("role_tier").asText(""),
                twist,
                got.path("quote").asText(""),
                dialogue,
                fate,
                got.path("style").asText(""),
                got.path("lucky").asBoolean(false),
                got.path("species").asText(""));
    }
}
