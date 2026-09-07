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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** 카드에도 외모를 읽는 데도 이만하면 넘친다. */
    private static final int ART_WIDTH = 768;

    private final WebtoonCharacterRepository characters;
    private final CharacterMaker maker;
    private final PrivateArt art;
    private final CreditGate credits;
    private final Path workDir;
    private final int freePerDay;
    private final int cost;
    /** 공개 그림을 내주는 앞자리. PageStore 와 같은 값을 본다. */
    private final String cdn;
    private final Clock clock;
    /* 한 줄로 세운다 — 그림 호출을 한꺼번에 여러 개 띄우면 값이 몰려 나간다. */
    private final ExecutorService line =
            Executors.newSingleThreadExecutor(r -> Thread.ofVirtual()
                    .name("webtoon-character").unstarted(r));

    /* 생성자가 둘이다(아래 하나는 검사에서 시계를 갈아 끼우려고 둔 것) — 표시가
       없으면 스프링이 인자 없는 생성자를 찾다가 서버가 아예 안 뜬다. 이 저장소에서
       GuestGate · SpendGuard · CreditService 가 같은 자리에서 걸렸다. 검사만으로는
       안 잡힌다: 검사는 이 클래스를 손으로 만들어서 스프링이 고를 일이 없다. */
    @Autowired
    public CharacterService(WebtoonCharacterRepository characters, CharacterMaker maker,
                            PrivateArt art, CreditGate credits,
                            @Value("${lore.webtoon.character.work-dir:}") String workDir,
                            @Value("${lore.webtoon.character.free-per-day:5}") int freePerDay,
                            @Value("${lore.webtoon.character.credit-cost:1}") int cost,
                            @Value("${lore.webtoon.cdn-base:}") String cdn) {
        this(characters, maker, art, credits, workDir, freePerDay, cost, cdn,
             Clock.system(ZONE));
    }

    CharacterService(WebtoonCharacterRepository characters, CharacterMaker maker,
                     PrivateArt art, CreditGate credits, String workDir,
                     int freePerDay, int cost, String cdn, Clock clock) {
        this.characters = characters;
        this.maker = maker;
        this.art = art;
        this.credits = credits;
        this.workDir = Path.of(workDir == null || workDir.isBlank()
                ? "haeun/landing/characters" : workDir).toAbsolutePath().normalize();
        this.freePerDay = freePerDay;
        this.cost = cost;
        this.cdn = cdn == null ? "" : cdn.replaceAll("/+$", "");
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
        boolean hasPhoto = photoDataUrl != null && !photoDataUrl.isBlank();
        if (!hasPhoto && (description == null || description.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "어떤 캐릭터인지 한 줄만 적어 주세요 — 사진은 없어도 됩니다.");
        }
        /* **이름은 안 물어도 된다.** 이름부터 요구하면 "뭐라고 부르지" 에서
           멈춘다. 안 적었으면 여기서 임시로 두고, 그리는 쪽이 사양을 쓰면서
           지어 준 이름으로 바꿔 준다(사양에 name 칸이 있다). */
        String called = name == null ? "" : name.trim();
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
        Path photo;
        try {
            Files.createDirectories(dir);
            photo = hasPhoto ? savePhoto(dir, photoDataUrl) : null;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("캐릭터 준비에 실패했습니다 (user={})", userId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "캐릭터를 만들지 못했습니다");
        }

        Instant now = Instant.now(clock);
        WebtoonCharacter saved = characters.save(WebtoonCharacter.drawing(
                publicId, userId, called.isEmpty() ? "이름 없는 캐릭터" : called,
                description, now));

        if (!free) {
            credits.charge(userId, cost, "character:" + publicId, "캐릭터 만들기");
        }

        /* **곧바로 돌려주고 뒤에서 그린다.**
         *
         * 그리는 데 1분쯤 걸리는데, 그동안 요청을 붙들고 있으면 배포에서
         * 끊긴다 — CloudFront 의 기본 응답 대기가 30초다. 로컬 개발 프록시가
         * 먼저 `socket hang up` 으로 알려 줬다: 그림은 다 그려졌고 DB 에도
         * 들어갔는데 화면에는 500 이 떴다.
         *
         * 커밋된 뒤에 시작한다 — 그리는 쪽은 다른 실타래에서 이 줄을 다시
         * 읽는다(웹툰 만들기가 같은 자리에서 걸렸다). */
        Path finalPhoto = photo;
        Long id = saved.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            line.submit(() -> draw(id, called, description, finalPhoto,
                                    style, dir));
                        }
                    });
        } else {
            line.submit(() -> draw(id, called, description, finalPhoto, style, dir));
        }
        return saved;
    }

    /** 뒤에서 그린다. 여기서 죽어도 줄이 멈추면 안 된다. */
    private void draw(Long id, String name, String description, Path photo,
                      String style, Path dir) {
        Path drawn = dir.resolve("art.png");
        try {
            CharacterMaker.Made made = maker.make(name, description, photo, style, drawn);
            String key = uploadArt(made.art());
            // 사람이 이름을 안 적었으면 사양이 지어 준 것을 쓴다.
            finish(id, key, made.source(), null,
                    name.isBlank() ? made.named() : null);
        } catch (Exception e) {                    // noqa: 사유는 로그에, 사람에겐 한 줄
            log.error("캐릭터를 못 그렸습니다 (id={}, name={})", id, name, e);
            finish(id, null, null, "캐릭터를 그리지 못했습니다. 다시 시도해 주세요.", null);
        } finally {
            // **어떻게 끝나든 올린 사진은 지운다.** 외모를 글로 적는 데만 쓰고,
            // 그 뒤로는 다시 안 쓴다. 사람 얼굴을 서버에 둘 이유가 없다.
            dropPhoto(photo);
        }
    }

    @Transactional
    protected void finish(Long id, String key, CharacterSource source, String why, String named) {
        characters.findById(id).ifPresent(one -> {
            Instant now = Instant.now(clock);
            if (why != null) {
                one.failed(why, now);
            } else {
                if (named != null && !named.isBlank()) {
                    one.rename(named, one.getDescription(), now);
                }
                one.drewArt(key, source == null ? CharacterSource.PROMPT : source, now);
            }
            characters.save(one);
        });
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

    /**
     * 화면이 그림을 볼 주소. 없으면 {@code null}.
     *
     * <b>공개 자리에 있는 것은 CDN 주소를 그대로 준다.</b> 예전에는 무엇이든
     * 서명 주소(presigned)로 만들었는데 그게 셋을 망쳤다:
     *
     * <ul>
     *   <li>서명 주소는 <b>시간이 지나면 만료된다.</b> 화면을 열어 둔 채로
     *       두면 그림이 사라진다</li>
     *   <li>CDN 을 건너뛰고 S3 에서 바로 받는다 — 누구나 봐도 되는 그림에
     *       매번 S3 대역폭을 쓴다</li>
     *   <li>S3 를 못 잡으면 <b>주소가 아예 null 이 되어 그림이 통째로
     *       사라진다.</b> 정작 그림은 CDN 에 멀쩡히 있는데도 그렇다</li>
     * </ul>
     *
     * 가르는 규칙은 {@code PageStore.urlOf} 와 같다 — 비공개 자리는 CloudFront
     * 가 안 내주므로 그때만 잠깐 열리는 주소를 만든다. 둘 중 하나를 고치면
     * 다른 쪽도 같이 본다.
     */
    @Transactional(readOnly = true)
    public String artUrl(WebtoonCharacter one) {
        String key = one.getArtKey();
        if (key == null || key.isBlank()) {
            return null;
        }
        if (!PrivateArt.isPrivate(key)) {
            return cdn.isEmpty() ? "/" + key : cdn + "/" + key;
        }
        return art.ready() ? art.temporaryUrl(key) : null;
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

    /**
     * 그린 것을 S3 로. 못 올려도 캐릭터는 만들어진 것으로 친다(파일은 남아 있다).
     *
     * <b>줄여서 올린다.</b> 모델이 주는 원본은 1024px PNG 라 한 장에 1.5~2.7MB 다.
     * 카드에는 200px 남짓으로 보이는데, 목록에 아홉 장이 있으면 20MB 를 받는
     * 셈이라 폰에서는 아무것도 안 뜬 채로 한참 기다린다(실제로 그랬다).
     *
     * 웹툰 페이지처럼 여러 폭을 만들지는 않는다 — 캐릭터 그림이 쓰이는 곳은
     * 카드와 (웹툰 만들 때) 외모를 읽는 자리 둘뿐이고, 둘 다 768px 이면 넘친다.
     */
    private String uploadArt(Path drawn) {
        try {
            byte[] body = shrink(Files.readAllBytes(drawn));
            return art.upload(body, body.length > 1 && body[0] == (byte) 0x89
                    ? "image/png" : "image/jpeg", false);
        } catch (IOException | RuntimeException e) {
            log.error("캐릭터 그림을 못 올렸습니다 ({})", drawn, e);
            return null;
        }
    }

    /** 긴 변을 {@value #ART_WIDTH}px 로. 못 줄이면 원본 그대로 올린다. */
    static byte[] shrunk(byte[] original) {
        return shrink(original);
    }

    private static byte[] shrink(byte[] original) {
        try {
            BufferedImage full = ImageIO.read(new ByteArrayInputStream(original));
            if (full == null || Math.max(full.getWidth(), full.getHeight()) <= ART_WIDTH) {
                return original;
            }
            double k = ART_WIDTH / (double) Math.max(full.getWidth(), full.getHeight());
            int w = Math.max(1, (int) Math.round(full.getWidth() * k));
            int h = Math.max(1, (int) Math.round(full.getHeight() * k));
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            var g = small.createGraphics();
            try {
                g.drawImage(full.getScaledInstance(w, h, Image.SCALE_SMOOTH), 0, 0, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(small, "jpg", out);
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            log.warn("캐릭터 그림을 못 줄였습니다 — 원본을 올립니다", e);
            return original;
        }
    }
}
