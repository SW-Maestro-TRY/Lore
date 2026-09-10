package com.lore.webtoon.character;

import com.lore.webtoon.art.PrivateArt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * 처음 온 사람이 바로 써 볼 캐릭터들.
 *
 * <h2>왜 있어야 하나</h2>
 *
 * 캐릭터 탭에 처음 들어오면 아무것도 없다. "만들어 보세요" 만 있는 화면은
 * <b>무엇이 나오는지 모르는 채로 값을 내라는 것</b>이라, 대부분 거기서 나간다.
 * 그림체가 여덟 개인데 「세미리얼」이 무엇인지도 글로만 적혀 있다.
 *
 * 그래서 그림체마다 하나씩, 이미 그려 둔 것을 얹어 둔다. 골라서 웹툰을
 * 만들어 보고 마음에 들면 그때 자기 것을 만든다.
 *
 * <h2>왜 견본 그림을 그대로 쓰나</h2>
 *
 * 그림체 고르개가 쓰는 견본이 이미 여덟 장 있다({@code web/samples/ex-*}).
 * 같은 그림을 캐릭터로도 쓰면 <b>고른 그림체와 나온 캐릭터가 같아 보인다</b> —
 * 새로 그리면 값이 나가고, 무엇보다 둘이 달라 보이면 고르개가 거짓말이 된다.
 *
 * <h2>여러 번 떠도 한 번만 심는다</h2>
 *
 * 이름으로 이미 있는지 본다. 서버가 다시 떠도 늘지 않는다.
 *
 * <h2>목록에서 뺀 것은 거둔다</h2>
 *
 * 여기 {@code SEEDS} 가 <b>유일한 근거</b>다. 목록에서 지웠는데 DB 에 남아
 * 있으면 화면에는 그대로 보이고, 지운 사람은 지워진 줄 안다. 그래서 뜰 때마다
 * 목록에 없는 기본 제공 캐릭터를 지운다.
 *
 * 이미 그 캐릭터로 만든 작품은 <b>안 없어진다</b> — 만들 때 그림을 작업 폴더로
 * 복사해 두고 쓰므로 작품이 이 줄을 참조하지 않는다.
 */
@Component
public class BuiltinCharacters implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinCharacters.class);

    /** 그림체 하나에 캐릭터 하나. 이름과 설명은 그 그림에 맞춰 지었다. */
    private record Seed(String style, String file, String name, String description) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("webtoon", "ex-webtoon-1.jpg", "하리",
                    "동네 서점에서 일하는 스무 살. 무슨 일이 나도 일단 웃고 보는데, "
                    + "정작 자기 얘기는 한 마디도 안 한다."),
            new Seed("romance", "ex-romance-1.png", "세이엘",
                    "몰락한 공작가의 마지막 사람. 무도회장에서 제일 예쁘게 웃으면서 "
                    + "누가 자기 집을 무너뜨렸는지 세고 있다."),
            new Seed("shoujo", "ex-shoujo-1.jpg", "윤슬",
                    "전학 온 지 사흘째. 아무하고도 말을 안 하는데 창가 자리 하나만은 "
                    + "매일 오래 바라본다."),
            new Seed("frost", "ex-frost-1.jpg", "제하",
                    "밤에만 문을 여는 상담소 주인. 무엇이든 들어 주지만 "
                    + "값은 반드시 받는다."),
            new Seed("pastel", "ex-pastel-1.jpg", "소미",
                    "자취 3년 차. 오늘도 라면을 끓이다 말고 창밖을 본다. "
                    + "별일 없는 하루를 잘 견디는 사람."),
            new Seed("noir", "ex-noir-1.jpg", "강도윤",
                    "잠복 열흘째인 형사. 담배를 끊었다고 말하고 다니는데 "
                    + "주머니에는 늘 한 갑이 있다."),
            new Seed("game", "ex-game-1.jpg", "이올",
                    "길드 청산인. 망한 길드를 찾아가 장비를 회수한다. "
                    + "가는 곳마다 환영받지 못해서 말수가 적다."));

    private final WebtoonCharacterRepository characters;
    private final PrivateArt art;
    private final Path samples;
    private final boolean on;

    public BuiltinCharacters(WebtoonCharacterRepository characters, PrivateArt art,
                             @Value("${lore.webtoon.character.samples-dir:}") String samples,
                             @Value("${lore.webtoon.character.seed-builtin:true}") boolean on) {
        this.characters = characters;
        this.art = art;
        this.samples = resolveSamplesDir(samples);
        this.on = on;
    }

    /**
     * 견본 그림이 있는 자리를 찾는다 — 셋 중 먼저 되는 것을 쓴다.
     *
     * <ol>
     *   <li>{@code samples-dir} 로 직접 지정한 자리 (있으면 최우선)</li>
     *   <li>저장소 원본 {@code haeun/landing/web/samples} (로컬 개발 — 되풀이 켤
     *       때마다 최신 그림을 바로 본다)</li>
     *   <li>jar 리소스({@code webtoon/character-samples} — {@code sync-harness.sh}
     *       가 빌드마다 뜬 사본)를 임시 폴더로 풀어서 쓴다. <b>배포 서버가 이
     *       길이다</b> — 서버엔 haeun/ 원본이 없다(#274 — 그래서 운영 캐릭터
     *       탭이 통째로 비어 있었다).</li>
     * </ol>
     */
    private static Path resolveSamplesDir(String samples) {
        if (samples != null && !samples.isBlank()) {
            return Path.of(samples).toAbsolutePath().normalize();
        }
        Path repo = Path.of("haeun/landing/web/samples").toAbsolutePath().normalize();
        if (Files.isDirectory(repo)) {
            return repo;
        }
        return extractFromClasspath();
    }

    /** {@code webtoon/character-samples} 리소스를 임시 폴더로 푼다({@code AiHarnessResources} 와 같은 방식). */
    private static Path extractFromClasspath() {
        try {
            Path root = Files.createTempDirectory("webtoon-character-samples-");
            root.toFile().deleteOnExit();
            String prefix = "webtoon/character-samples/";
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:" + prefix + "**/*");
            int count = 0;
            for (Resource r : files) {
                String uri = r.getURI().toString();
                int at = uri.indexOf(prefix);
                if (at < 0) {
                    continue;
                }
                String rel = uri.substring(at + prefix.length());
                if (rel.isBlank() || rel.endsWith("/")) {
                    continue;
                }
                Path dst = root.resolve(rel);
                Files.createDirectories(dst.getParent());
                try (InputStream in = r.getInputStream()) {
                    Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
                }
                count++;
            }
            log.info("기본 캐릭터 견본 {}개를 jar 리소스에서 {} 에 풀었습니다", count, root);
            return root;
        } catch (IOException e) {
            log.error("기본 캐릭터 견본을 jar 리소스에서 풀지 못했습니다", e);
            return Path.of("haeun/landing/web/samples").toAbsolutePath().normalize();
        }
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!on) {
            return;
        }
        int made = 0;
        int fixed = 0;
        for (Seed seed : SEEDS) {
            /* **이름만 보고 넘기면 그림 없는 줄이 영영 남는다.**
             *
             * 심을 때 S3 를 못 잡으면 이름·설명만 들어가고 그림은 비는데,
             * 다음 기동에서 "이미 있다" 며 그냥 넘어가서 화면에 「그림 없음」
             * 이 계속 뜬다. DB 를 비우고 다시 띄웠을 때 실제로 그랬다 —
             * 그 순간 S3 설정이 없었고, 뒤에 설정을 채워도 안 고쳐졌다.
             *
             * 그래서 이름이 있어도 **그림이 비어 있으면 다시 채운다.** */
            WebtoonCharacter old = characters.findFirstBySourceAndName(CharacterSource.BUILTIN, seed.name())
                    .orElse(null);
            if (old != null && old.getArtKey() != null && !old.getArtKey().isBlank()) {
                continue;
            }
            Path file = samples.resolve(seed.file());
            if (!Files.isRegularFile(file)) {
                log.warn("기본 캐릭터 그림이 없습니다: {}", file);
                continue;
            }
            String key;
            try {
                // 기본 제공은 누구나 본다 — 열리는 자리에 둔다.
                // 기본 제공도 같은 크기로 줄여 올린다 — 견본 원본이 크다.
                key = art.upload(CharacterService.shrunk(Files.readAllBytes(file)),
                        "image/jpeg", true);
            } catch (Exception e) {                 // noqa: 하나 실패해도 나머지는 심는다
                log.error("기본 캐릭터 그림을 못 올렸습니다 ({})", seed.file(), e);
                continue;
            }
            Instant now = Instant.now();
            if (old != null) {
                // 이미 있는 줄에 그림만 채운다 — 지우고 새로 만들면 그 캐릭터로
                // 만든 작품이 가리키던 번호가 바뀐다.
                if (key != null) {
                    old.drewArt(key, CharacterSource.BUILTIN, now);
                    characters.save(old);
                    fixed++;
                }
                continue;
            }
            WebtoonCharacter one = WebtoonCharacter.builtin(
                    UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                    seed.name(), seed.description(), now);
            if (key != null) {
                one.drewArt(key, CharacterSource.BUILTIN, now);
            }
            characters.save(one);
            made++;
        }
        if (fixed > 0) {
            log.info("그림이 비어 있던 기본 캐릭터 {}명을 채웠습니다", fixed);
        }
        if (made > 0) {
            log.info("기본 캐릭터 {}명을 심었습니다", made);
        }
        retire();
    }

    /**
     * {@code SEEDS} 에서 빠진 기본 제공 캐릭터를 지운다.
     *
     * 목록에서 지웠는데 DB 에 남아 있으면 화면에는 그대로 보인다 — 지운
     * 사람은 지워진 줄 알고, 왜 아직 나오는지 찾느라 시간을 쓴다.
     */
    private void retire() {
        Set<String> keep = SEEDS.stream().map(Seed::name).collect(Collectors.toSet());
        /* **우리가 심은 것만 본다.**
         *
         * 전에는 주인이 빈 것(`findByOwnerIdIsNull`)을 다 훑었다. 그런데
         * 로그인 안 하고 만든 캐릭터도 주인이 비어 있다 — 캐릭터 만들기는
         * 로그인을 안 따지고(`CreditGate.currentUser()` 가 그냥 null 이 된다),
         * 그 줄은 주인 없이 저장된다.
         *
         * 그래서 **게스트가 만든 캐릭터가 서버를 다시 띄울 때마다 지워졌다.**
         * 무료 횟수를 써서 만들었는데 다음 기동에 사라진다. 심은 표시
         * (`source = BUILTIN`)로 좁힌다. */
        List<WebtoonCharacter> gone = characters.findBySource(CharacterSource.BUILTIN).stream()
                .filter(one -> !keep.contains(one.getName()))
                .toList();
        if (gone.isEmpty()) {
            return;
        }
        characters.deleteAll(gone);
        log.info("목록에서 빠진 기본 캐릭터 {}명을 거뒀습니다: {}", gone.size(),
                gone.stream().map(WebtoonCharacter::getName).toList());
    }

    private static String typeOf(String file) {
        return file.endsWith(".png") ? "image/png" : "image/jpeg";
    }
}
