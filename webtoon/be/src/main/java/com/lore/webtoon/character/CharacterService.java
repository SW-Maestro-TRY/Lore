package com.lore.webtoon.character;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.CreditGate;
import com.lore.webtoon.PrivateArt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * 캐릭터를 만들고 고르는 일.
 *
 * <h2>값은 하루 몫이 먼저다</h2>
 *
 * 하루 몇 개까지는 <b>공짜</b>이고, 그 뒤로 1크레딧이다. 캐릭터를 못 만들면
 * 웹툰 자체를 못 만드는 자리라 여기서 막히면 안 되고, 그렇다고 공짜로 열어
 * 두면 한 장에 실측 75원이 무한히 나간다 — "마음에 안 드네, 다시" 를 열 번
 * 하면 웹툰 한 편 값이다.
 *
 * <h2>올린 사진은 남기지 않는다</h2>
 *
 * 사진은 외모를 <b>글로 적을 때만</b> 쓰고 그림이 나오면 지운다. 보관하는 것은
 * AI 가 그린 그림뿐이다 — 사람 얼굴이 서버에 남지 않는다.
 */
@Service
public class CharacterService {

    private static final Logger log = LoggerFactory.getLogger(CharacterService.class);

    /** 사람이 "오늘" 이라고 부르는 날과 같아야 한다. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private static final int MAX_PHOTO_BYTES = 6 * 1024 * 1024;

    private final WebtoonCharacterRepository characters;
    private final CharacterMaker maker;
    private final PrivateArt art;
    private final CreditGate credits;
    private final Path workDir;
    private final int freePerDay;
    private final int cost;
    private final Clock clock;

    /* 생성자가 둘이다(아래 하나는 검사에서 시계를 갈아 끼우려고 둔 것) — 표시가
       없으면 스프링이 인자 없는 생성자를 찾다가 서버가 아예 안 뜬다. 이 저장소에서
       GuestGate · SpendGuard · CreditService 가 같은 자리에서 걸렸다. 검사만으로는
       안 잡힌다: 검사는 이 클래스를 손으로 만들어서 스프링이 고를 일이 없다. */
    @Autowired
    public CharacterService(WebtoonCharacterRepository characters, CharacterMaker maker,
                            PrivateArt art, CreditGate credits,
                            @Value("${lore.webtoon.character.work-dir:}") String workDir,
                            @Value("${lore.webtoon.character.free-per-day:5}") int freePerDay,
                            @Value("${lore.webtoon.character.credit-cost:1}") int cost) {
        this(characters, maker, art, credits, workDir, freePerDay, cost, Clock.system(ZONE));
    }

    CharacterService(WebtoonCharacterRepository characters, CharacterMaker maker,
                     PrivateArt art, CreditGate credits, String workDir,
                     int freePerDay, int cost, Clock clock) {
        this.characters = characters;
        this.maker = maker;
        this.art = art;
        this.credits = credits;
        this.workDir = Path.of(workDir == null || workDir.isBlank()
                ? "haeun/landing/characters" : workDir).toAbsolutePath().normalize();
        this.freePerDay = freePerDay;
        this.cost = cost;
        this.clock = clock;
    }

    /** 이 사람이 고를 수 있는 것 — 내 것과 기본 제공. */
    @Transactional(readOnly = true)
    public List<WebtoonCharacter> pickable(Long userId) {
        return characters.pickableBy(userId);
    }

    @Transactional(readOnly = true)
    public WebtoonCharacter byPublicId(String publicId, Long userId) {
        WebtoonCharacter one = characters.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 캐릭터가 없습니다"));
        if (!one.isBuiltin() && !one.ownedBy(userId)) {
            // 있는 것을 "권한 없음" 으로 알리면 남의 번호를 하나씩 찔러 볼 수 있다.
            throw new BusinessException(ErrorCode.NOT_FOUND, "그런 캐릭터가 없습니다");
        }
        return one;
    }

    /** 오늘 이 사람이 몇 개 더 공짜로 만들 수 있나. */
    @Transactional(readOnly = true)
    public int freeLeft(Long userId) {
        if (userId == null || freePerDay <= 0) {
            return 0;
        }
        Instant since = LocalDate.now(clock).atStartOfDay(ZONE).toInstant();
        return (int) Math.max(0, freePerDay - characters.madeSince(userId, since));
    }

    /**
     * 만든다.
     *
     * @param photoDataUrl 있으면 그것을 읽어 외모를 적는다. 없으면 이름·설명만으로
     * @return 만든 캐릭터
     */
    @Transactional
    public WebtoonCharacter create(Long userId, String name, String description,
                                   String photoDataUrl, String style) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "캐릭터를 만들려면 로그인해 주세요 — 만든 캐릭터는 계정에 남습니다.");
        }
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "캐릭터 이름을 적어주세요");
        }
        boolean hasPhoto = photoDataUrl != null && !photoDataUrl.isBlank();
        if (!hasPhoto && (description == null || description.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "사진이 없으면 설명이 있어야 합니다 — 어떤 캐릭터인지 알려주세요.");
        }
        if (!maker.ready()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "지금은 캐릭터를 만들 수 없습니다");
        }

        // **값은 만들기 전에 본다.** 그린 뒤에 모자라다고 하면 돈은 이미 나갔다.
        boolean free = freeLeft(userId) > 0;
        if (!free) {
            String blocked = credits.whyBlocked(userId, cost);
            if (blocked != null) {
                throw new BusinessException(CreditGate.notEnough(), blocked);
            }
        }

        String publicId = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Path dir = workDir.resolve(publicId);
        Path photo = null;
        Path drawn = dir.resolve("art.png");
        CharacterMaker.Made made;
        try {
            Files.createDirectories(dir);
            if (hasPhoto) {
                photo = savePhoto(dir, photoDataUrl);
            }
            made = maker.make(name.trim(), description, photo, style, drawn);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {                    // noqa: 사유는 로그에, 사람에겐 한 줄
            log.error("캐릭터를 못 만들었습니다 (user={}, name={})", userId, name, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "캐릭터를 그리지 못했습니다. 다시 시도해 주세요.");
        } finally {
            // **어떻게 끝나든 올린 사진은 지운다.** 외모를 글로 적는 데만 쓰고,
            // 그 뒤로는 다시 안 쓴다. 사람 얼굴을 서버에 둘 이유가 없다.
            dropPhoto(photo);
        }

        String key = uploadArt(made.art());
        Instant now = Instant.now(clock);
        WebtoonCharacter saved = characters.save(WebtoonCharacter.of(
                publicId, userId, name.trim(), description, made.source(), now));
        if (key != null) {
            saved.drewArt(key, now);
            characters.save(saved);
        }

        if (!free) {
            credits.charge(userId, cost, "character:" + publicId, "캐릭터 만들기");
        }
        return saved;
    }

    @Transactional
    public WebtoonCharacter rename(String publicId, Long userId, String name, String description) {
        WebtoonCharacter one = byPublicId(publicId, userId);
        if (!one.ownedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "기본 캐릭터는 고칠 수 없습니다");
        }
        one.rename(name, description, Instant.now(clock));
        return characters.save(one);
    }

    /**
     * 지운다.
     *
     * <b>그림은 S3 에 그대로 둔다.</b> 이 캐릭터로 이미 만든 웹툰이 그 그림을
     * 참조하고 있을 수 있어서, 여기서 지우면 남의(내) 완성작에 구멍이 난다.
     * 안 쓰이는 그림을 치우는 것은 따로 할 일이다.
     */
    @Transactional
    public void remove(String publicId, Long userId) {
        WebtoonCharacter one = byPublicId(publicId, userId);
        if (!one.ownedBy(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "기본 캐릭터는 지울 수 없습니다");
        }
        characters.delete(one);
    }

    /** 화면이 그림을 볼 주소. 없으면 {@code null}. */
    @Transactional(readOnly = true)
    public String artUrl(WebtoonCharacter one) {
        String key = one.getArtKey();
        if (key == null || key.isBlank() || !art.ready()) {
            return null;
        }
        return art.temporaryUrl(key);
    }

    int cost() {
        return cost;
    }

    int freePerDay() {
        return freePerDay;
    }

    // ---- 안쪽 -------------------------------------------------------------

    private Path savePhoto(Path dir, String dataUrl) throws IOException {
        String body = dataUrl.contains(",") ? dataUrl.substring(dataUrl.indexOf(',') + 1) : dataUrl;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(body.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "사진을 읽지 못했습니다");
        }
        if (bytes.length > MAX_PHOTO_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "사진이 너무 큽니다 (6MB 까지)");
        }
        Path out = dir.resolve("photo.png");
        Files.write(out, bytes);
        return out;
    }

    private void dropPhoto(Path photo) {
        if (photo == null) {
            return;
        }
        try {
            Files.deleteIfExists(photo);
        } catch (IOException e) {
            log.error("올린 사진을 못 지웠습니다 ({}) — 사람이 치워야 합니다", photo, e);
        }
    }

    /** 그린 것을 S3 로. 못 올려도 캐릭터는 만들어진 것으로 친다(파일은 남아 있다). */
    private String uploadArt(Path drawn) {
        try {
            return art.upload(Files.readAllBytes(drawn), "image/png", false);
        } catch (IOException | RuntimeException e) {
            log.error("캐릭터 그림을 못 올렸습니다 ({})", drawn, e);
            return null;
        }
    }
}
