package com.lore.webtoon.character;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.webtoon.art.PageStore;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.credit.CreditGate;
import com.lore.webtoon.credit.GuestGate;
import com.lore.webtoon.usage.SpendGuard;
import com.lore.webtoon.usage.UsageService;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.safety.SafetyGuard;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 캐릭터를 만들고 고르는 일.
 *
 * <h2>값은 하루 몫이 먼저다</h2>
 *
 * 하루 몇 개까지는 <b>공짜</b>이고, 그 뒤로 2크레딧이다. 캐릭터를 못 만들면
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

    /** 웹툰 만들기 쪽(wizardData.ts 의 MAX_PHOTOS)과 같은 값이다. */
    private static final int MAX_PHOTOS = 4;

    private final WebtoonCharacterRepository characters;
    private final CharacterMaker maker;
    private final CharacterOwner owner;
    private final PrivateArt art;
    private final CreditGate credits;
    private final ShareReward shareReward;
    /** 글 거르기(#80). 테스트용 생성자로 만들면 비어 있고, 그때는 안 거른다. */
    private SafetyGuard safety;
    private final Path workDir;
    private final int freePerDay;
    private final int cost;
    /** 로컬에서만 켠다 — 서명 주소로 준다. PageStore 와 같은 값. */
    private final boolean presignLocally;
    /** 호출·원가를 쌓는 곳(#444). 테스트용 생성자로 만들면 비어 있고, 그때는 안 쌓는다. */
    private UsageService usage;
    /** 하루 돈 상한(#444). 테스트용 생성자로 만들면 비어 있고, 그때는 안 막는다. */
    private SpendGuard spend;
    /**
     * 그린 뒤 작업 폴더를 남기는가.
     *
     * 로컬은 남긴다 — 무엇을 넣고 무엇을 불러 얼마가 나갔는지(input.json · meta.json ·
     * 프롬프트 · 그림)를 폴더에서 바로 본다. 배포 서버는 그림이 S3 에, 호출과 원가가
     * DB 에 있으니 서버 디스크에 쌓을 이유가 없다(#157 과 같은 원칙).
     * 판단은 {@link #keepsFiles} 참고. 테스트용 생성자는 언제나 남긴다(안 지운다).
     */
    private boolean keepFiles = true;
    private final Clock clock;
    /* 한 줄로 세운다 — 그림 호출을 한꺼번에 여러 개 띄우면 값이 몰려 나간다. */
    private final ExecutorService line =
            Executors.newSingleThreadExecutor(r -> Thread.ofVirtual()
                    .name("webtoon-character").unstarted(r));

    /* 생성자가 둘이다(아래 하나는 검사에서 시계를 갈아 끼우려고 둔 것) */
    @Autowired
    public CharacterService(WebtoonCharacterRepository characters, CharacterMaker maker,
                            CharacterOwner owner, PrivateArt art, CreditGate credits,
                            ShareReward shareReward, SafetyGuard safety,
                            @Value("${lore.webtoon.character.work-dir:}") String workDir,
                            @Value("${lore.webtoon.character.free-per-day:3}") int freePerDay,
                            @Value("${lore.webtoon.character.credit-cost:2}") int cost,
                            @Value("${lore.webtoon.presign-locally:false}") boolean presignLocally,
                            UsageService usage, SpendGuard spend,
                            @Value("${app.s3.content-bucket:}") String bucket,
                            @Value("${app.s3.endpoint:}") String endpoint,
                            @Value("${lore.webtoon.character.keep-files:}") String keepFiles) {
        this(characters, maker, owner, art, credits, shareReward, workDir, freePerDay, cost,
             presignLocally, Clock.system(ZONE));
        this.safety = safety;
        this.usage = usage;
        this.spend = spend;
        this.keepFiles = keepsFiles(bucket, endpoint, keepFiles);
        log.info("캐릭터 작업 폴더: {}", this.keepFiles
                ? "남깁니다(로컬)" : "원가를 DB 에 적은 뒤 치웁니다(배포)");
    }

    /**
     * 작업 폴더를 남길지. 설정이 있으면 그대로, 없으면 로컬인지로 정한다.
     *
     * 로컬 판단은 #157(작품 폴더, {@code RunFiles})과 같다 — 버킷이 없거나 창고 주소가
     * 이 기계(localhost · 127.0.0.1)면 로컬이다. 노트북에서 MinIO 를 붙여 띄우면 버킷이
     * 생기므로 버킷만 보면 틀린다.
     */
    static boolean keepsFiles(String bucket, String endpoint, String keepFiles) {
        if (keepFiles != null && !keepFiles.isBlank()) {
            return Boolean.parseBoolean(keepFiles.trim());
        }
        if (bucket == null || bucket.isBlank()) {
            return true;
        }
        if (endpoint == null || endpoint.isBlank()) {
            return false;
        }
        try {
            String host = java.net.URI.create(endpoint.trim()).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1")
                    || host.equals("::1") || host.equals("[::1]"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 하루 돈 상한에 걸려 캐릭터를 못 만든다. 컨트롤러가 429 로 돌려준다(웹툰 만들기와 같은 응답). */
    public static class SpendCapped extends RuntimeException {
        public SpendCapped(String why) {
            super(why);
        }
    }

    CharacterService(WebtoonCharacterRepository characters, CharacterMaker maker,
                     CharacterOwner owner, PrivateArt art, CreditGate credits, ShareReward shareReward,
                     String workDir, int freePerDay, int cost, boolean presignLocally, Clock clock) {
        this.characters = characters;
        this.shareReward = shareReward;
        this.maker = maker;
        this.owner = owner;
        this.art = art;
        this.credits = credits;
        this.workDir = Path.of(workDir == null || workDir.isBlank()
                ? "webtoon/ai/work/characters" : workDir).toAbsolutePath().normalize();
        this.freePerDay = freePerDay;
        this.cost = cost;
        this.presignLocally = presignLocally;
        this.clock = clock;
    }

    /** 이 사람이 고를 수 있는 것 — 내 것과 기본 제공. */
    @Transactional(readOnly = true)
    public List<WebtoonCharacter> pickable(Long userId, Collection<String> uids) {
        return characters.pickableBy(userId, uids);
    }

    @Transactional(readOnly = true)
    public WebtoonCharacter byPublicId(String publicId, Long userId, Collection<String> uids) {
        WebtoonCharacter one = characters.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 캐릭터가 없습니다"));
        if (!one.isBuiltin() && !one.madeBy(userId, uids)) {
            // 있는 것을 "권한 없음" 으로 알리면 남의 번호를 하나씩 찔러 볼 수 있다.
            throw new BusinessException(ErrorCode.NOT_FOUND, "그런 캐릭터가 없습니다");
        }
        return one;
    }

    /**
     * 공유 링크로 열리는 카드 — <b>주인을 안 가린다.</b> 카드는 남에게 보여 주려고
     * 만드는 것이라, 링크를 받은 사람이 로그인 없이 봐야 한다. 카드가 없는
     * 캐릭터(초상 한 장·기본 제공)는 여기로 안 열린다 — 공유할 것이 없다.
     */
    @Transactional(readOnly = true)
    public WebtoonCharacter sharedCard(String publicId) {
        WebtoonCharacter one = characters.findByPublicId(publicId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "그런 카드가 없습니다"));
        if (!one.hasCard()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "그런 카드가 없습니다");
        }
        return one;
    }

    /** 오늘 이 사람이 몇 개 더 공짜로 만들 수 있나. */
    @Transactional(readOnly = true)
    public int freeLeft(Long userId, Collection<String> uids) {
        if (freePerDay <= 0) {
            return 0;
        }
        /* **게스트도 센다.** 전에는 로그인 안 했으면 그냥 0 이었는데, 그건
           "오늘 몫을 다 썼다" 와 화면에서 구별이 안 된다 — 만들 수 있는데도
           못 만드는 줄 안다. 이제 게스트도 브라우저로 세므로 남은 몫을 말할
           수 있다. */
        Instant since = LocalDate.now(clock).atStartOfDay(ZONE).toInstant();
        return dailyLeft(userId, uids, since) + shareReward.unused(userId, uids);
    }

    /** 오늘 몫만. 공유로 돌려받은 것(#332)은 안 더한다. */
    private int dailyLeft(Long userId, Collection<String> uids, Instant since) {
        return (int) Math.max(0, freePerDay - characters.madeSince(userId, uids, since));
    }

    /**
     * 만든다.
     *
     * @param photoDataUrls 있으면 읽어서 외모를 적는다(최대 {@value #MAX_PHOTOS}장 —
     *              넘으면 그만큼만 쓰고 나머지는 버린다). 비었으면 이름·설명만으로
     * @return 만든 캐릭터
     */
    @Transactional
    public WebtoonCharacter create(Long userId, String browserUid, String name,
                                   String description, List<String> photoDataUrls, String style) {
        return start(userId, browserUid, name, description, photoDataUrls, style, null);
    }

    /**
     * 「캐릭터 만들어보기」 — 그 세계관 웹툰의 한 컷과 카드 글.
     *
     * <b>아무것도 안 넣어도 된다.</b> 사진·설명·이름·세계관이 전부 비어도
     * 만든다(하네스가 존재부터 정한다). 직접 만들기({@link #create})와 다른
     * 점은 그것 하나와, 결과가 초상이 아니라 한 컷 + 카드라는 것이다. 값과
     * 하루 몫은 같은 자리에서 같은 규칙으로 센다 — 한 장 그리는 값은 같다.
     *
     * @param world 프리셋 키이거나 사람이 직접 쓴 한 줄. 비우면 무작위
     */
    @Transactional
    public WebtoonCharacter tryOut(Long userId, String browserUid, String name,
                                   String description, List<String> photoDataUrls, String world) {
        return start(userId, browserUid, name, description, photoDataUrls, null,
                     world == null ? "" : world.trim());
    }

    /** @param world {@code null} 이면 초상 한 장, 아니면 한 컷("" 는 세계관 무작위) */
    private WebtoonCharacter start(Long userId, String browserUid, String name,
                                   String description, List<String> photoDataUrls, String style,
                                   String world) {
        /* **로그인은 안 시킨다.** 이 제품은 회원가입 없이 한번 써 보게 하는
           것이 목적이고, 웹툰 만들기가 이미 그렇다 — 캐릭터만 로그인을
           요구하면 "캐릭터로 웹툰 만들기" 로 가는 길이 거기서 끊긴다.

           대신 **누가 만든 것인지는 반드시 적는다.** 계정이 없으면 브라우저
           uid 로 적는다. 이것 없이 만들면 주인 없는 줄이 되어 남의 목록에
           뜨고, 우리가 심은 것을 거두는 자리에 쓸려 지워진다. */
        if (userId == null && (browserUid == null || browserUid.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "브라우저를 알 수 없어 만들 수 없습니다 — 새로고침 후 다시 시도해 주세요.");
        }
        // 글은 그리기 전에 거른다(#80). 사진은 아직 안 본다(safety.md).
        if (safety != null) {
            safety.checkText(world == null ? "character-create" : "character-try", name, description, world);
        }
        List<String> photos = (photoDataUrls == null ? List.<String>of() : photoDataUrls).stream()
                .filter(s -> s != null && !s.isBlank())
                .limit(MAX_PHOTOS)
                .toList();
        boolean hasPhoto = !photos.isEmpty();
        // 한 컷은 빈손도 된다 — 그게 「랜덤으로 만들어보기」다.
        if (world == null && !hasPhoto && (description == null || description.isBlank())) {
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
        /* **하루 돈 상한도 본다(#444).** 캐릭터도 글·그림을 부른다 — 전에는 그 값이
           어디에도 안 적혀서 상한이 캐릭터를 못 셌다. 편수 상한은 웹툰 몫이라 안 본다. */
        if (spend != null) {
            String capped = spend.whyBlockedByMoney();
            if (capped != null) {
                throw new SpendCapped(capped);
            }
        }

        // **값은 만들기 전에 본다.** 그린 뒤에 모자라다고 하면 돈은 이미 나갔다.
        // 컨트롤러(list/freeLeft)와 같은 uid 묶음으로 센다 — 기기를 여럿 이은 사람에게
        // 화면은 "0개 남음" 인데 서버는 공짜로 만들어 주던 어긋남을 없앤다.
        List<String> uids = owner.uidsOf(userId, browserUid);
        boolean free = freePerDay > 0
                && dailyLeft(userId, uids, LocalDate.now(clock).atStartOfDay(ZONE).toInstant()) > 0;
        /* 오늘 몫을 다 썼으면 공유로 돌려받은 것(#332)을 하나 쓴다. 하루 몫보다 뒤에 쓰는 이유는
           하루 몫은 내일 새로 오지만 돌려받은 것은 안 오기 때문이다. */
        if (!free && shareReward.useOne(userId, uids)) {
            free = true;
        }
        if (!free) {
            /* 게스트는 낼 크레딧이 없다 — 계정 쪽 확인은 통과해 버리므로
               여기서 따로 막는다. 없는 잔액을 보고 "모자랍니다" 라고 하면
               충전하러 가라는 말이 되는데, 게스트에게는 갈 곳이 없다. */
            if (userId == null) {
                throw new BusinessException(CreditGate.notEnough(),
                        "오늘 무료로 만들 수 있는 캐릭터를 다 쓰셨어요 — "
                        + "로그인하시면 이어서 만들 수 있어요.");
            }
            String blocked = credits.whyBlocked(userId, cost);
            if (blocked != null) {
                throw new BusinessException(CreditGate.notEnough(), blocked);
            }
        }

        String publicId = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Path dir = workDir.resolve(publicId);
        List<Path> savedPhotos;
        try {
            Files.createDirectories(dir);
            savedPhotos = new ArrayList<>();
            for (int i = 0; i < photos.size(); i++) {
                savedPhotos.add(savePhoto(dir, photos.get(i), i));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("캐릭터 준비에 실패했습니다 (user={})", userId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "캐릭터를 만들지 못했습니다");
        }

        Instant now = Instant.now(clock);
        WebtoonCharacter drawing = WebtoonCharacter.drawing(
                publicId, userId, browserUid, called.isEmpty() ? "이름 없는 캐릭터" : called,
                description, now);
        drawing.asked(called, world);   // 넣은 것을 그대로 — 카드가 무엇을 어떻게 읽었는지 견주려고(#329)
        WebtoonCharacter saved = characters.save(drawing);

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
        List<Path> finalPhotos = savedPhotos;
        Long id = saved.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            line.submit(() -> draw(id, called, description, finalPhotos,
                                    style, world, dir));
                        }
                    });
        } else {
            line.submit(() -> draw(id, called, description, finalPhotos, style, world, dir));
        }
        return saved;
    }

    /** 뒤에서 그린다. 여기서 죽어도 줄이 멈추면 안 된다. */
    private void draw(Long id, String name, String description, List<Path> photos,
                      String style, String world, Path dir) {
        Path drawn = dir.resolve(world == null ? "art.png" : "panel.png");
        boolean drew = false;
        String key = null;
        try {
            CharacterMaker.Made made = world == null
                    ? maker.make(name, description, photos, style, drawn)
                    : maker.makePanel(name, description, photos, world, drawn);
            drew = true;
            key = uploadArt(made.art());
            // 사람이 이름을 안 적었으면 사양이 지어 준 것을 쓴다.
            finish(id, key, made.source(), null,
                    name.isBlank() ? made.named() : null, made.card());
        } catch (Exception e) {                    // noqa: 사유는 로그에, 사람에겐 한 줄
            log.error("캐릭터를 못 그렸습니다 (id={}, name={})", id, name, e);
            finish(id, null, null, "캐릭터를 그리지 못했습니다. 다시 시도해 주세요.", null, null);
        } finally {
            // **어떻게 끝나든 올린 사진은 지운다.** 외모를 글로 적는 데만 쓰고,
            // 그 뒤로는 다시 안 쓴다. 사람 얼굴을 서버에 둘 이유가 없다.
            photos.forEach(this::dropPhoto);
            // **실패해도 적는다** — 그림에서 죽어도 앞서 부른 글 값은 나갔다(#444).
            recordCost(dir);
            tidy(dir, drew, key);
        }
    }

    /** 이 캐릭터를 만들며 부른 호출들을 원가 장부로. 하네스가 폴더에 남긴 meta.json 을 읽는다. */
    private void recordCost(Path dir) {
        if (usage == null) {
            return;
        }
        String runId = UsageService.CHARACTER_PREFIX + dir.getFileName();
        try {
            int saved = usage.ingestMetaFile(runId, dir.resolve("meta.json"));
            if (saved < 0) {
                log.warn("캐릭터 비용 기록이 없습니다 ({})", runId);
            } else {
                log.info("캐릭터 비용을 적었습니다 ({}, {}건)", runId, saved);
            }
        } catch (IOException | RuntimeException e) {
            log.error("캐릭터 비용을 적지 못했습니다 ({})", runId, e);
        }
    }

    /**
     * 배포 서버에서는 작업 폴더를 치운다. 그림은 S3 에, 호출·원가는 DB 에 있다.
     *
     * <b>그렸는데 못 올린 것은 남긴다</b> — 그때는 서버에 있는 파일이 유일한 사본이다
     * (uploadArt 의 "파일은 남아 있다"). 로컬은 언제나 남긴다.
     */
    private void tidy(Path dir, boolean drew, String key) {
        if (keepFiles || (drew && (key == null || key.isBlank()))) {
            return;
        }
        try (Stream<Path> all = Files.walk(dir)) {
            all.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("캐릭터 작업 폴더를 다 못 치웠습니다 ({})", p, e);
                }
            });
        } catch (IOException e) {
            log.warn("캐릭터 작업 폴더를 못 치웠습니다 ({})", dir, e);
        }
    }

    @Transactional
    protected void finish(Long id, String key, CharacterSource source, String why, String named,
                          WebtoonCharacter.Card card) {
        characters.findById(id).ifPresent(one -> {
            Instant now = Instant.now(clock);
            if (why != null) {
                one.failed(why, now);
            } else {
                if (named != null && !named.isBlank()) {
                    one.rename(named, one.getDescription(), now);
                }
                CharacterSource from = source == null ? CharacterSource.PROMPT : source;
                if (card != null) {
                    one.drewPanel(key, from, card, now);
                } else {
                    one.drewArt(key, from, now);
                }
            }
            characters.save(one);
        });
    }

    @Transactional
    public WebtoonCharacter rename(String publicId, Long userId, Collection<String> uids,
                                   String name, String description) {
        WebtoonCharacter one = byPublicId(publicId, userId, uids);
        if (!one.madeBy(userId, uids)) {
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
    public void remove(String publicId, Long userId, Collection<String> uids) {
        WebtoonCharacter one = byPublicId(publicId, userId, uids);
        if (!one.madeBy(userId, uids)) {
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
        // 공개 자리는 언제나 상대경로다 — 도메인을 붙일 자리를 없앴다.
        // 이유는 PageStore.url 주석 참고(2026-09-19, 적어 둔 호스트에 basic auth 가
        // 붙으면서 운영 화면이 그림마다 로그인 팝업을 띄웠다).
        if (!PrivateArt.isPrivate(key) && !presignLocally) {
            return "/" + key;
        }
        // 비공개 자리이거나, 로컬(presign-locally)이면 잠깐 열리는 주소.
        return art.ready() ? art.temporaryUrl(key) : null;
    }

    /**
     * 고를 수 있는 세계관 — 하네스의 프리셋({@code story-harness/worlds.json})을
     * 그대로 내준다. 자바가 목록을 한 벌 더 갖지 않는다: 프리셋을 더하면 여기도
     * 같이 늘어야 하는데, 두 벌이면 반드시 어긋난다.
     *
     * @return {@code [{key, label}]}. 못 읽으면 빈 목록 — 화면은 그러면 직접 쓰기만 보여 준다
     */
    public List<Map<String, String>> worlds() {
        Path file = maker.worldsFile();
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            JsonNode presets = new ObjectMapper().readTree(Files.readString(file)).path("presets");
            List<Map<String, String>> out = new ArrayList<>();
            presets.fieldNames().forEachRemaining(key -> {
                Map<String, String> one = new LinkedHashMap<>();
                one.put("key", key);
                one.put("label", presets.path(key).path("label").asText(key));
                out.add(one);
            });
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("세계관 목록을 못 읽었습니다 ({})", file, e);
            return List.of();
        }
    }

    /**
     * 「랜덤으로 만들어보기」 — 입력 칸을 채울 예시 캐릭터 한 벌. <b>AI 를 안 부른다.</b>
     *
     * {@code new_harness/prompt/random_pool.json} 의 {@code presets} 에서 하나를 고르게
     * 뽑는다. 지금은 손으로 쓴 목데이터이고, 늘리고 줄이는 것은 그 파일만 고치면 된다.
     * 사람은 그 값을 보고 고치거나 다시 뽑거나 그대로 만든다 — 바로 생성으로 넘어가지 않는다.
     *
     * @return {@code {name, description, world, world_label}}. 목록을 못 읽으면 빈 값들
     */
    public Map<String, String> randomSeed() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("name", "");
        out.put("description", "");
        out.put("world", "");
        out.put("world_label", "");
        Path file = maker.randomPoolFile();
        if (file == null || !Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonNode presets = new ObjectMapper().readTree(Files.readString(file)).path("presets");
            if (!presets.isArray() || presets.isEmpty()) {
                return out;
            }
            JsonNode one = presets.get(new java.util.Random().nextInt(presets.size()));
            out.put("name", one.path("name").asText(""));
            out.put("description", one.path("description").asText(""));
            String world = one.path("world").asText("");
            for (Map<String, String> w : worlds()) {
                if (w.get("key").equals(world)) {
                    out.put("world", world);
                    out.put("world_label", w.get("label"));
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            log.warn("랜덤 예시를 못 읽었습니다 ({})", file, e);
            return out;
        }
    }

    int cost() {
        return cost;
    }

    int freePerDay() {
        return freePerDay;
    }

    // ---- 안쪽 -------------------------------------------------------------

    /** @param index 여러 장일 때 파일 이름이 안 겹치게 붙이는 번호(0부터). */
    private Path savePhoto(Path dir, String dataUrl, int index) throws IOException {
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
        Path out = dir.resolve("photo" + index + ".png");
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
