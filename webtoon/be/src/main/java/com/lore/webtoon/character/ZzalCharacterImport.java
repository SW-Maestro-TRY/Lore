package com.lore.webtoon.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.safety.SafetyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 짤(키우기)에서 키운 캐릭터를 웹툰 「내 캐릭터」로 가져온다.
 *
 * <h2>무엇을 가져오나</h2>
 * 이름, 짤에서 적은 설정(성격·말투·세계관·직업·좋아하는 것·싫어하는 것·그 밖에), 그리고 그림 한 장이다.
 * 그림은 사용자가 올린 원본 그림과 짤이 만든 캐릭터 시트 중에서 고른다. 가져온 캐릭터는
 * 「원본 등록」({@link CharacterService#importOriginal})과 같은 종류(UPLOAD)라 AI 호출도 크레딧도 없다.
 *
 * <h2>왜 짤 API 를 부르지 않고 표를 읽나</h2>
 * 짤의 내 캐릭터 조회는 부를 때마다 시간을 정산한다(그날 첫 조회면 함께한 날이 늘고 잠·기상도 바뀐다).
 * 웹툰에서 고르려고 열었을 뿐인데 짤 캐릭터의 하루가 움직이면 안 된다. 그래서 같은 DB 의
 * {@code zzal_pet} 을 <b>읽기만</b> 한다. 짤 코드와 짤 표는 하나도 바꾸지 않는다.
 * 짤이 칸 이름을 바꾸면 여기도 같이 고쳐야 한다(읽는 칸은 아래 {@link #COLUMNS} 한 곳).
 *
 * <h2>그림은 복사한다</h2>
 * 짤 그림 키를 그대로 가리키지 않고 웹툰 비공개 자리로 복사한다. 웹툰 캐릭터를 지우거나 그림을
 * 바꿀 때 웹툰이 자기 그림을 치우는데, 그게 짤 그림이면 짤 화면이 깨진다.
 */
@Service
public class ZzalCharacterImport {

    private static final Logger log = LoggerFactory.getLogger(ZzalCharacterImport.class);

    /** 짤 캐릭터 시트를 그대로 가져온 캐릭터의 sheet_mode. 체험에서도 이 시트를 심는다(CharacterService.seedSheet). */
    public static final String SHEET_MODE = "zzal";

    /** 하네스가 받는 사진 상한과 같다(CharacterService.MAX_PHOTO_BYTES). */
    private static final long MAX_BYTES = 6L * 1024 * 1024;

    /** 짤에서 읽는 칸 전부. */
    private static final String COLUMNS = "id, name, note, authored_persona, personality, personality_extra, "
            + "world, tone, genre, source_image_key, sheet_image_key";

    /** 짤 성격 값 → 짤 화면이 쓰는 이름(zzal Personality 의 주석과 같다). */
    private static final Map<String, String> PERSONALITY = Map.of(
            "GENTLE", "온순", "LIVELY", "활발", "SHY", "수줍음", "CLINGY", "응석", "COOL", "시크");

    /** 짤 작성 칸 → 웹툰 설정 글의 머리말. 순서도 이대로 적는다. */
    private static final List<String[]> FIELDS = List.of(
            new String[]{"persona", "성격"},
            new String[]{"world", "세계관"},
            new String[]{"job", "직업"},
            new String[]{"likes", "좋아하는 것"},
            new String[]{"dislikes", "싫어하는 것"},
            new String[]{"extra", "그 밖에"});

    /** 가져올 수 있는 짤 캐릭터 한 마리. 그림 주소는 화면에 미리 보여 주는 용도다. */
    public record Pick(long petId, String name, String description,
                       String imageKey, String sheetKey) { }

    /** 스프링 빈으로 받지 않는다 — 이 앱에는 잭슨 2 ObjectMapper 빈이 없다(다른 웹툰 코드도 직접 만든다). */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final S3Storage storage;
    private final PrivateArt art;
    private final WebtoonCharacterRepository characters;
    private final ObjectProvider<SafetyGuard> safety;
    private final boolean presignLocally;
    private final Path workDir;

    public ZzalCharacterImport(JdbcTemplate jdbc, S3Storage storage, PrivateArt art,
                               WebtoonCharacterRepository characters, ObjectProvider<SafetyGuard> safety,
                               @Value("${lore.webtoon.presign-locally:false}") boolean presignLocally,
                               @Value("${lore.webtoon.character.work-dir:}") String workDir) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.art = art;
        this.characters = characters;
        this.safety = safety;
        this.presignLocally = presignLocally;
        this.workDir = workDir == null || workDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "lore-webtoon-zzal-import")
                : Path.of(workDir);
    }

    /** 내가 짤에서 키운 캐릭터 중 가져올 수 있는 것. 부화를 마친 것만 — 시트가 있어야 짤 캐릭터다. */
    @Transactional(readOnly = true)
    public List<Pick> mine(Long userId) {
        if (userId == null) {
            return List.of();
        }
        return jdbc.query("select " + COLUMNS + " from zzal_pet where user_id = ? and phase in ('ALIVE', 'DEAD') "
                        + "and sheet_image_key is not null order by id desc",
                (rs, i) -> new Pick(rs.getLong("id"), rs.getString("name"),
                        describe(rs.getString("authored_persona"), rs.getString("personality"),
                                rs.getString("personality_extra"), rs.getString("world"), rs.getString("tone"),
                                rs.getString("genre"), rs.getString("note")),
                        rs.getString("source_image_key"), rs.getString("sheet_image_key")),
                userId);
    }

    /**
     * 짤 캐릭터 하나를 웹툰 내 캐릭터로 만든다.
     *
     * @param useSheet true 면 캐릭터 시트를, false 면 원본 그림을 캐릭터 그림으로 쓴다
     */
    @Transactional
    public WebtoonCharacter importOne(Long userId, long petId, boolean useSheet) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "로그인이 필요합니다");
        }
        Pick pick = mine(userId).stream().filter(p -> p.petId() == petId).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "짤 캐릭터를 찾을 수 없습니다"));
        String from = useSheet ? pick.sheetKey() : pick.imageKey();
        if (from == null || from.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "가져올 그림이 없습니다");
        }
        String name = pick.name() == null || pick.name().isBlank() ? "짤 캐릭터" : pick.name().trim();
        SafetyGuard guard = safety.getIfAvailable();
        if (guard != null) {
            guard.checkText("character-import", name, pick.description());
        }
        String key = copyArt(from);
        Instant now = Instant.now();
        WebtoonCharacter one = WebtoonCharacter.drawing(UUID.randomUUID().toString().replace("-", ""),
                userId, null, name, pick.description(), now);
        one.drewArt(key, CharacterSource.UPLOAD, now);
        if (useSheet) {
            /* 짤 캐릭터 시트를 그대로 가져왔으면 그게 이 캐릭터의 시트다 — 작품마다 시트를 새로 그리지 않고
               이걸 심는다(CharacterService.seedSheet). 사양(sheet_spec)은 없어서 하네스가 글로만 적는다. */
            one.keepSheet(key, null, SHEET_MODE, now);
        }
        log.info("짤 캐릭터를 웹툰 캐릭터로 가져왔습니다 (user={}, pet={}, sheet={})", userId, petId, useSheet);
        return characters.save(one);
    }

    /** 화면이 미리 볼 주소. 웹툰 캐릭터 그림과 같은 규칙이다(CharacterService.artUrl). */
    public String previewUrl(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (!PrivateArt.isPrivate(key) && !presignLocally) {
            return "/" + key;
        }
        return art.ready() ? art.temporaryUrl(key) : null;
    }

    /** 짤 그림을 받아 웹툰 비공개 자리에 올린다. JPG·PNG 만 받는다(하네스가 읽는 형식). */
    private String copyArt(String from) {
        Path temp = null;
        try {
            Files.createDirectories(workDir);
            temp = Files.createTempFile(workDir, "zzal-", ".img");
            storage.download(from, temp);
            if (Files.size(temp) > MAX_BYTES) {
                throw new BusinessException(ErrorCode.INVALID_INPUT, "그림이 너무 큽니다 (6MB 까지)");
            }
            byte[] bytes = Files.readAllBytes(temp);
            String mime = mimeOf(bytes);
            if (mime == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        "이 그림 형식은 가져올 수 없습니다. 캐릭터 시트로 가져와 주세요");
            }
            String key = art.upload(bytes, mime, false);
            if (key == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "그림을 저장하지 못했습니다. 다시 시도해 주세요");
            }
            return key;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "짤 그림을 읽지 못했습니다");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("짤 그림 임시파일 정리 실패", e);
                }
            }
        }
    }

    private static String mimeOf(byte[] bytes) throws IOException {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            String format = readers.next().getFormatName().toLowerCase();
            return switch (format) {
                case "png" -> "image/png";
                case "jpeg", "jpg" -> "image/jpeg";
                default -> null;
            };
        }
    }

    /**
     * 짤 설정을 웹툰 설정 글로 옮긴다. 짤에 적은 말을 고치거나 덧붙이지 않고 머리말만 단다.
     * 새 온보딩으로 만든 캐릭터는 작성 원문(authored_persona)을, 그 전 캐릭터는 예전 칸을 읽는다.
     * 말투는 두 경우 모두 예전 칸에만 있다.
     */
    String describe(String authored, String personality, String personalityExtra,
                    String world, String tone, String genre, String note) {
        List<String> out = new ArrayList<>();
        JsonNode fields = parseFields(authored);
        if (fields != null) {
            for (String[] f : FIELDS) {
                JsonNode field = find(fields, f[0]);
                String text = field == null ? null : fieldText(field);
                if (text != null) {
                    out.add(f[1] + ": " + text);
                }
            }
        } else {
            String traits = traits(personality, personalityExtra);
            if (traits != null) {
                out.add("성격: " + traits);
            }
            if (present(world)) {
                out.add("세계관: " + world.trim());
            }
            if (present(genre)) {
                out.add("장르: " + genre.trim());
            }
            if (present(note)) {
                out.add("그 밖에: " + note.trim());
            }
        }
        if (present(tone)) {
            out.add("말투: " + tone.trim());
        }
        return String.join("\n", out);
    }

    private JsonNode parseFields(String authored) {
        if (!present(authored)) {
            return null;
        }
        try {
            JsonNode fields = JSON.readTree(authored).path("fields");
            return fields.isArray() ? fields : null;
        } catch (IOException e) {
            log.warn("짤 작성 원문을 읽지 못해 예전 칸으로 옮깁니다", e);
            return null;
        }
    }

    private static JsonNode find(JsonNode fields, String id) {
        for (JsonNode f : fields) {
            if (id.equals(f.path("fieldId").asText())) {
                return f;
            }
        }
        return null;
    }

    /** 칩은 쉼표로 잇고, 직접 쓴 글은 그 뒤에 붙인다. 건너뛴 칸이나 빈 칸은 null. */
    private static String fieldText(JsonNode field) {
        if (field.path("skipped").asBoolean(false)) {
            return null;
        }
        List<String> chips = new ArrayList<>();
        for (JsonNode c : field.path("chips")) {
            String chip = c.asText("").trim();
            if (!chip.isEmpty()) {
                chips.add(chip);
            }
        }
        String raw = field.path("rawText").asText("").trim();
        String joined = String.join(", ", chips);
        if (joined.isEmpty()) {
            return raw.isEmpty() ? null : raw;
        }
        return raw.isEmpty() ? joined : joined + ". " + raw;
    }

    private static String traits(String personality, String extra) {
        List<String> names = new ArrayList<>();
        if (present(personality)) {
            names.add(PERSONALITY.getOrDefault(personality.trim(), personality.trim()));
        }
        if (present(extra)) {
            for (String e : extra.split(",")) {
                if (!e.isBlank()) {
                    names.add(PERSONALITY.getOrDefault(e.trim(), e.trim()));
                }
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
