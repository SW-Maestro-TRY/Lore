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
 *
 * 그래서 이미 그려 둔 것을 얹어 둔다. 골라서 웹툰을 만들어 보고 마음에 들면
 * 그때 자기 것을 만든다.
 *
 * <h2>그림체와 짝지어 두지 않는다</h2>
 *
 * 전에는 그림체 고르개의 여덟 견본({@code ex-webtoon-1} 따위)을 그대로
 * 캐릭터로도 썼다 — 고른 그림체와 나온 캐릭터가 같아 보이게 하려던 것이다.
 * 지금은 예시 캐릭터를 따로 고른다. 그림체 짝을 맞추느라 <b>보여 줄 그림의
 * 수가 그림체 수에 묶이는</b> 것이 더 아까웠다. 그림체 고르개는 여전히 같은
 * 견본 파일을 쓰므로({@code webtoon/fe/lib/styleThumbs.ts}) 그 파일들은
 * 지우면 안 된다.
 *
 * <h2>심는 것은 처음 한 번뿐이고, 그 뒤로는 DB 가 원본이다</h2>
 *
 * 이름으로 이미 있는지 보고, 있으면 건드리지 않는다. 그래서 심은 다음에
 * <b>DB 에서 이름·설명을 고치거나 카드 설정을 붙여도 다음 기동에 안 되돌아간다.</b>
 * 여기 {@code SEEDS} 는 "빈 DB 를 채우는 첫 값" 이지 계속 맞춰야 할 정답이
 * 아니다 — 고치는 법은 {@code webtoon/docs/images.md} 에 적어 두었다.
 *
 * 전에는 {@code SEEDS} 에 없는 이름의 기본 제공 캐릭터를 뜰 때마다 지웠다.
 * 그 규칙 아래에서는 <b>DB 에서 이름을 바꾸는 순간 다음 기동에 그 줄이
 * 사라진다</b> — 고쳐 쓰라고 열어 둔 자리가 고치면 지워지는 자리가 된다.
 * 그래서 지우지 않고 로그로만 알린다. 정말 뺄 때는 DB 에서 지운다.
 *
 * 이미 그 캐릭터로 만든 작품은 <b>안 없어진다</b> — 만들 때 그림을 작업 폴더로
 * 복사해 두고 쓰므로 작품이 이 줄을 참조하지 않는다.
 */
@Component
public class BuiltinCharacters implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinCharacters.class);

    /** 빈 DB 를 채울 첫 값. 그림 파일 하나에 이름과 설명. */
    private record Seed(String file, String name, String description) {
    }

    private static final List<Seed> SEEDS = List.of(
            new Seed("ex-harin-1.jpg", "하린",
                    "국밥집 창가 자리가 자기 자리인 줄 안다. 누구에게나 잘 웃는데 "
                    + "정작 자기 얘기는 안 한다."),
            new Seed("ex-riseel-1.jpg", "리스엘",
                    "성을 내려다보는 발코니가 그의 자리다. 아래 도시 이름을 전부 "
                    + "외우는데, 그중 하나는 곧 사라진다."),
            new Seed("ex-seoyun-1.jpg", "서윤",
                    "밤에 문 닫는 헌책방을 혼자 지킨다. 묻는 말에는 답하지만 "
                    + "먼저 묻는 법이 없다."),
            new Seed("ex-ruda-1.jpg", "루다",
                    "싸우고 온 걸 숨기려고 더 크게 웃는다. 팔에 난 자국은 "
                    + "넘어져서 그런 거라고 한다."),
            new Seed("ex-seojin-1.jpg", "서진",
                    "밤에만 움직이는 해결사. 받은 일은 끝내고, 끝낸 일은 "
                    + "말하지 않는다."),
            new Seed("ex-dogyeong-1.jpg", "도경",
                    "사무실에서 제일 조용한 사람. 웃는 걸 본 사람이 아직 없다."),
            new Seed("ex-ihyeon-1.jpg", "이현",
                    "약속 시간에 늘 십 분 늦고, 늦은 이유는 매번 다르다."),
            new Seed("ex-rozel-1.png", "로젤",
                    "다과회에서 제일 먼저 웃고 제일 늦게 돌아간다. 그날 오간 말을 "
                    + "하나도 안 잊는다."),
            new Seed("ex-sea-1.jpg", "세아",
                    "연습실 불을 마지막으로 끄는 사람. 거울 앞에서만 표정을 바꾼다."),
            new Seed("ex-daon-1.jpg", "다온",
                    "과제는 늘 산더미고 잠은 늘 부족하다. 그래도 창밖은 꼭 본다."),
            new Seed("ex-yungyeom-1.jpg", "윤겸",
                    "왕궁 문서를 나르는 일을 한다. 오늘 전할 두루마리 내용을 "
                    + "이미 읽어 버렸다."),
            new Seed("ex-mongi-2.jpg", "몽이",
                    "로판 악역 영애로 태어난 강아지. 구두를 물어뜯은 것이 "
                    + "첫 번째 악행이다."));

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
     *   <li>저장소 원본 {@code webtoon/ai/assets/samples} (로컬 개발 — 되풀이 켤
     *       때마다 최신 그림을 바로 본다)</li>
     *   <li>jar 리소스({@code webtoon/character-samples} — {@code sync-harness.sh}
     *       가 빌드마다 뜬 사본)를 임시 폴더로 풀어서 쓴다. <b>배포 서버가 이
     *       길이다</b> — 서버엔 저장소 원본이 없다(#274 — 그래서 운영 캐릭터
     *       탭이 통째로 비어 있었다).</li>
     * </ol>
     */
    private static Path resolveSamplesDir(String samples) {
        if (samples != null && !samples.isBlank()) {
            return Path.of(samples).toAbsolutePath().normalize();
        }
        Path repo = Path.of("webtoon/ai/assets/samples").toAbsolutePath().normalize();
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
            return Path.of("webtoon/ai/assets/samples").toAbsolutePath().normalize();
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
        reportStrays();
    }

    /**
     * {@code SEEDS} 에 없는 기본 제공 캐릭터를 <b>알리기만 한다.</b>
     *
     * 전에는 여기서 지웠다. 그런데 무엇이 "빠진 것" 인지 이름으로 가리기
     * 때문에, <b>DB 에서 이름을 고치는 순간 그 줄이 빠진 것으로 보인다</b> —
     * 예시 캐릭터를 DB 에서 다듬으라고 열어 두고는 다듬으면 다음 기동에
     * 지워 버리는 셈이었다.
     *
     * 이름 말고 기댈 만한 표시가 없어서(심을 때 매기는 번호는 랜덤이고,
     * 시드를 가리키는 칸은 표에 없다) 가리는 쪽이 아니라 지우는 쪽을
     * 그만둔다. 정말 뺄 때는 사람이 DB 에서 지운다 — 어차피 예시를 바꾸는
     * 일은 드물고, 그때 한 줄 지우는 것이 매번 덮어쓰이는 것보다 낫다.
     */
    private void reportStrays() {
        Set<String> known = SEEDS.stream().map(Seed::name).collect(Collectors.toSet());
        /* **우리가 심은 것만 본다.** 주인이 빈 것을 다 훑으면 로그인 없이
         * 만든 캐릭터까지 걸린다 — 캐릭터 만들기는 로그인을 안 따져서
         * (`CreditGate.currentUser()` 가 그냥 null 이 된다) 그 줄도 주인이
         * 비어 있다. 심은 표시(`source = BUILTIN`)로 좁힌다. */
        List<String> strays = characters.findBySource(CharacterSource.BUILTIN).stream()
                .map(WebtoonCharacter::getName)
                .filter(name -> !known.contains(name))
                .toList();
        if (strays.isEmpty()) {
            return;
        }
        log.info("시드 목록에 없는 기본 제공 캐릭터가 있습니다(지우지 않습니다): {}"
                + " — DB 에서 고친 것이면 그대로 두고, 정말 뺄 것이면 DB 에서 지우세요",
                strays);
    }

}
