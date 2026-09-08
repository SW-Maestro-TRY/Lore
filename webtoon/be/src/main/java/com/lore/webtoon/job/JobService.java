package com.lore.webtoon.job;

import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.harness.WebtoonController;
import com.lore.webtoon.work.WorkLedger;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.character.CharacterOwner;
import com.lore.webtoon.character.CharacterService;
import com.lore.webtoon.character.WebtoonCharacter;
import com.lore.webtoon.story.StoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 만들기 — 받고, 줄 세우고, 보여 준다.
 *
 * 파이썬 서버의 {@code NHRunner} 와 {@code /api/nh/*} 가 하던 일이다.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /** 사진은 이만큼까지. 원본 그대로 받으면 폼 하나가 수십 MB 가 된다. */
    private static final int MAX_PHOTOS = 4;
    private static final int MAX_PHOTO_BYTES = 6 * 1024 * 1024;
    private static final int PHOTO_WIDTH = 1400;

    /** 그림체 고른 값 -> 하네스가 아는 이름. 파이썬 쪽 STYLE_CHOICES 와 같아야 한다. */
    /* 그림체 표는 여기 두지 않는다 — 둘러보기·완성본도 같은 값을 읽어야 해서
       한 곳({@link WebtoonStyles})으로 모았다. */
    private static final Map<String, String> STYLE = WebtoonStyles.STYLE;

    private static final Map<String, String> STAGE_LABEL = Map.of(
            "story", "이야기 짓기",
            "sheet", "캐릭터 시트",
            "board", "장면 나누기",
            "pages", "페이지 그림");

    private static final String DEFAULT_STYLE = WebtoonStyles.DEFAULT_STYLE;

    private final WebtoonJobRepository jobs;
    private final JobStore store;
    private final JobRunner runner;
    private final JobProgress progress;
    private final StoryStore stories;
    private final WorkLedger works;
    private final CharacterService characters;
    private final CharacterOwner owner;
    private final PrivateArt art;
    private final S3Service uploads;
    private final S3Storage storage;
    private final Path jobsDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobService(WebtoonJobRepository jobs, JobStore store, JobRunner runner,
                      JobProgress progress, StoryStore stories, WorkLedger works,
                      CharacterService characters, CharacterOwner owner, PrivateArt art,
                      S3Service uploads, S3Storage storage,
                      @Value("${lore.webtoon.python.jobs-dir:}") String jobsDir) {
        this.jobs = jobs;
        this.works = works;
        this.characters = characters;
        this.owner = owner;
        this.art = art;
        this.uploads = uploads;
        this.storage = storage;
        this.store = store;
        this.runner = runner;
        this.progress = progress;
        this.stories = stories;
        this.jobsDir = Path.of(jobsDir == null || jobsDir.isBlank()
                ? "haeun/landing/jobs_spring" : jobsDir).toAbsolutePath().normalize();
    }

    /**
     * 만들기를 받는다.
     *
     * <b>여기서 검사한 것만 파이썬에 넘어간다.</b> 사진이 너무 크거나 못 여는
     * 것이면 여기서 막는다 — 파이썬까지 가서 죽으면 사람은 "만들기가 안 된다"
     * 로만 알게 된다.
     */
    @Transactional
    public String create(CreateRequest form, Long userId, String browserUid,
                         String guestKey) {
        if (!form.agreeIp()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "저작권 확인에 동의해야 만들 수 있습니다");
        }
        boolean known = notBlank(form.name()) || notBlank(form.character())
                || (form.fields() != null && form.fields().values().stream().anyMatch(this::notBlank))
                || (form.photosData() != null && !form.photosData().isEmpty());
        if (!known) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "캐릭터를 알 수 있는 것이 하나는 필요합니다 — 이름 · 설명 · 항목 · 사진 중 아무거나요.");
        }

        String publicId = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Path dir = jobsDir.resolve(publicId);
        try {
            Files.createDirectories(dir);
            /* **키가 오면 그쪽을 쓴다.** presign 으로 올리면 사진이 요청 본문에
               안 실리므로 넷이면 20MB 넘던 create 가 몇백 바이트가 된다.
               data URL 도 계속 받는다 — 화면이 한 번에 갈아타지 않아도 되고,
               게스트는 계정이 없어서 티켓을 못 받는다(presign 은 로그인이
               필요하다). 둘 다 오면 키가 이긴다. */
            List<Path> photos = form.photoKeys() != null && !form.photoKeys().isEmpty()
                    ? pullPhotos(dir, form.photoKeys(), userId)
                    : savePhotos(dir, form.photosData());
            Path fromCharacter = characterArt(dir, form.characterId(), userId, form.uid());
            /* 캐릭터를 골라 왔으면 그 그림을 참조로 붙인다.
             *
             * 화면은 **번호만** 보낸다. 그림은 S3 의 안 열리는 자리에 있고,
             * 브라우저가 그것을 내려받아 base64 로 다시 올리면 같은 그림이
             * 두 번 오간다 — 게다가 그 주소는 CORS 가 안 열려 있다. */
            if (fromCharacter != null) {
                photos = new ArrayList<>(photos);
                photos.add(fromCharacter);
            }
            writeCharacter(dir, form, photos);
        } catch (IOException e) {
            log.error("만들기 준비에 실패했습니다 (job={})", publicId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "만들기를 시작하지 못했습니다");
        }

        String style = STYLE.getOrDefault(blank(form.style()), DEFAULT_STYLE);
        WebtoonJob job = jobs.save(WebtoonJob.queued(
                publicId, userId, browserUid, guestKey, style,
                form.checkpoints() == null || form.checkpoints(),
                inputOf(form), Instant.now()));

        /* **장부에도 적는다.**
         *
         * 프록시 길은 하네스 응답을 보고 적는데(WebtoonController), 이 길은
         * 그 응답을 안 지나간다. 그래서 여기로 만든 작품이 장부에 한 줄도 안
         * 남았고, 만든 사람이 마이페이지에서 자기 작품을 못 봤다 — 비용도
         * 그림도 다 남았는데 <b>주인만 없었다.</b> */
        works.started(publicId, userId, browserUid);

        /* **커밋된 뒤에 그리기 시작한다.**
         *
         * 그리는 쪽은 다른 실타래에서 자기 트랜잭션으로 이 작업을 다시 읽는다
         * (JobStore 의 REQUIRES_NEW). 여기서 바로 시작시키면 아직 커밋이 안 끝나
         * 그 줄이 안 보이고, 방금 만든 작업을 "그런 작업이 없다" 로 읽는다 —
         * 사람에게는 만들자마자 실패로 뜬다. 실제로 그랬다.
         *
         * 트랜잭션 밖(검사 등)에서 불릴 수도 있으니, 붙을 곳이 없으면 그냥
         * 바로 시작한다. */
        Long id = job.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            runner.enqueue(id, dir);
                        }
                    });
        } else {
            runner.enqueue(id, dir);
        }
        return publicId;
    }

    /** 이 작업이 만들고 있는 run 번호. 첫 단계가 끝나야 생기므로 없을 수 있다. */
    @Transactional(readOnly = true)
    public String runOf(String publicId) {
        return store.byPublicId(publicId).getRunId();
    }

    @Transactional(readOnly = true)
    public JobView view(String publicId) {
        WebtoonJob job = store.byPublicId(publicId);
        return JobView.of(job, progress.of(job.getId()),
                store.directionsOf(job.getId()),
                WebtoonStyles.labelOf(job.getStyle()),
                STAGE_LABEL.getOrDefault(job.getStage().wire(), job.getStage().wire()));
    }

    /** 사람이 이야기를 골랐다. */
    public void pick(String publicId, int n) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_PICK) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 고를 차례가 아닙니다");
        }
        List<Map<String, Object>> got = store.directionsOf(job.getId());
        if (n < 1 || n > got.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "그런 이야기가 없습니다");
        }
        store.pick(job.getId(), n);
        stories.choose(job.getRunId(), n);       // 무엇을 골랐는지도 DB 에 남는다
        runner.resumeAfterPick(job.getId());
    }

    /**
     * 넷 다 마음에 안 든다 — 후보를 다시 짓는다.
     *
     * <b>고르는 차례일 때만 된다.</b> 그리는 중에 이걸 받으면 같은 작품이 줄에
     * 두 번 서서, 하네스가 같은 폴더를 동시에 고쳐 쓴다(파이썬 쪽
     * {@code _require} 가 막던 것과 같은 자리다).
     */
    public void retryPick(String publicId, String note) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_PICK) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 고를 차례가 아닙니다");
        }
        runner.retryDirections(job.getId(), note == null ? "" : note.trim());
    }

    /**
     * 그만둔다.
     *
     * <b>끝난 것은 그냥 둔다.</b> 화면이 다 만들어진 순간에 취소를 눌렀을 수
     * 있는데(0.8초마다 묻는 사이), 그때 「취소했습니다」로 덮으면 다 나온
     * 작품이 실패로 보인다.
     */
    public void cancel(String publicId) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus().isOver()) {
            return;
        }
        runner.cancel(job.getId());
    }

    /** 사람이 캐릭터 시트를 확인했다. */
    public void approveSheet(String publicId) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_SHEET) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 확인할 차례가 아닙니다");
        }
        runner.resumeAfterSheet(job.getId());
    }

    /**
     * 시트를 <b>다시 그린다.</b> 사람이 적어 보낸 말은 그리는 프롬프트 뒤에 붙는다.
     *
     * 그리기 전에 이미 있는 시트를 지운다 — {@code run.py} 의 {@code stage_sheet}
     * 이 "사양·그림이 이미 있으면 다시 안 그린다"로 정해 놨기 때문이다. 안 지우면
     * 다시 만들기를 눌러도 같은 그림이 그대로 있고, 사람은 눌렀는데 아무 일도
     * 안 일어난 것으로 본다. (파이썬 쪽 `_clear_sheet` 과 같은 규칙이다.)
     */
    public void retrySheet(String publicId, String note) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_SHEET) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 확인할 차례가 아닙니다");
        }
        clearSheet(job.getRunId());
        runner.redrawSheet(job.getId(), note == null ? "" : note.trim());
    }

    /** 시트를 다시 그리려면 먼저 지운다. 못 지운 것이 있어도 계속 간다. */
    private void clearSheet(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        Path dir = runner.runDir(runId);
        for (String name : new String[]{"sheet.png", "sheet_spec.json",
                                        "sheet_prompt.txt", "sheet_spec_prompt.txt"}) {
            try {
                Files.deleteIfExists(dir.resolve(name));
            } catch (IOException e) {         // noqa: 하나 못 지워도 나머지를 지운다
                log.warn("시트를 못 지웠습니다 ({})", name, e);
            }
        }
    }

    /* ---- 준비 ------------------------------------------------------------- */

    /**
     * 올라온 사진을 저장한다.
     *
     * 폭을 줄여 둔다 — 원본 그대로 넘기면 모델에 보내는 값이 커져서 느리고
     * 비싸다. 못 여는 사진은 여기서 막는다(아이폰 HEIC 등).
     */
    /**
     * 골라 온 캐릭터의 그림을 작업 폴더에 내려놓는다. 없으면 {@code null}.
     *
     * <b>남의 캐릭터는 안 붙인다.</b> 내 것이거나 기본 제공만 — 안 그러면 번호를
     * 찍어 넣어 남의 캐릭터로 웹툰을 만들 수 있다(그 기능은 #259 에서 따로 다룬다).
     *
     * 못 가져와도 만들기는 안 막는다. 이름과 설명은 이미 폼에 실려 왔으므로
     * 그것만으로도 그릴 수 있다 — 여기서 막으면 S3 가 잠깐 흔들릴 때 만들기가
     * 통째로 죽는다.
     */
    private Path characterArt(Path dir, String characterId, Long userId, String uid) {
        if (characterId == null || characterId.isBlank()) {
            return null;
        }
        try {
            /* 브라우저도 같이 넘긴다 — 로그인 안 하고 만든 캐릭터는 계정이
               아니라 이 값으로만 자기 것임을 말할 수 있다. 안 넘기면 방금
               자기가 만든 캐릭터로 웹툰을 만들려는 순간 "그런 캐릭터가
               없습니다" 가 뜬다. */
            WebtoonCharacter one = characters.byPublicId(
                    characterId, userId, owner.uidsOf(userId, uid));
            byte[] bytes = art.read(one.getArtKey());
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            Path out = dir.resolve("charart.png");
            Files.write(out, bytes);
            return out;
        } catch (Exception e) {                     // noqa: 못 붙여도 만들기는 간다
            log.warn("고른 캐릭터의 그림을 못 붙였습니다 (character={})", characterId, e);
            return null;
        }
    }

    /**
     * presign 으로 S3 에 올라간 사진을 작업 폴더로 내린다.
     *
     * <b>티켓을 먼저 태운다</b>({@code S3Service.consume}) — 남의 키를 적어 보내거나
     * 같은 키를 두 번 쓰는 것을 그 안에서 막는다. 태우지 않고 내려받으면 키만 알면
     * 남이 올린 사진을 자기 작업에 붙일 수 있다.
     *
     * 내린 뒤에는 {@code photo1.png} … 로 두어 예전 길과 같은 모양이 되게 한다 —
     * 하네스는 그 이름만 안다.
     */
    private List<Path> pullPhotos(Path dir, List<String> keys, Long userId) throws IOException {
        List<Path> saved = new ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            return saved;
        }
        if (keys.size() > MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "사진은 " + MAX_PHOTOS + "장까지 올릴 수 있습니다");
        }
        int i = 0;
        for (String key : keys) {
            i++;
            if (key == null || key.isBlank()) {
                continue;
            }
            uploads.consume(userId, key, Instant.now());
            Path raw = dir.resolve("upload" + i);
            storage.download(key, raw);
            BufferedImage image = ImageIO.read(raw.toFile());
            if (image == null) {
                Files.deleteIfExists(raw);
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        i + "번째 사진을 열지 못했습니다. 아이폰 사진(HEIC)이면 JPG 나 PNG 로 바꿔서 올려 주세요.");
            }
            Path to = dir.resolve("photo" + i + ".png");
            ImageIO.write(shrink(image), "png", to.toFile());
            Files.deleteIfExists(raw);
            saved.add(to);
        }
        return saved;
    }

    private List<Path> savePhotos(Path dir, List<String> dataUrls) throws IOException {
        List<Path> saved = new ArrayList<>();
        if (dataUrls == null) {
            return saved;
        }
        if (dataUrls.size() > MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "사진은 " + MAX_PHOTOS + "장까지 올릴 수 있습니다");
        }
        int i = 0;
        for (String url : dataUrls) {
            i++;
            if (url == null || !url.startsWith("data:")) {
                continue;
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(url.substring(url.indexOf(',') + 1));
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        i + "번째 사진을 읽지 못했습니다");
            }
            if (raw.length > MAX_PHOTO_BYTES) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        i + "번째 사진이 너무 큽니다 (6MB 까지)");
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null) {
                throw new BusinessException(ErrorCode.INVALID_INPUT,
                        i + "번째 사진을 열지 못했습니다. 아이폰 사진(HEIC)이면 JPG 나 PNG 로 바꿔서 올려 주세요.");
            }
            Path to = dir.resolve("photo" + i + ".png");
            ImageIO.write(shrink(image), "png", to.toFile());
            saved.add(to);
        }
        return saved;
    }

    private static BufferedImage shrink(BufferedImage src) {
        if (src.getWidth() <= PHOTO_WIDTH) {
            return src;
        }
        int height = Math.round(src.getHeight() * (float) PHOTO_WIDTH / src.getWidth());
        BufferedImage out = new BufferedImage(PHOTO_WIDTH, height, BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.drawImage(src.getScaledInstance(PHOTO_WIDTH, height, java.awt.Image.SCALE_SMOOTH),
                0, 0, null);
        g.dispose();
        return out;
    }

    /**
     * 폼을 파이썬이 읽는 캐릭터 파일로.
     *
     * <b>빈 칸은 빈 칸으로 둔다.</b> 코드가 기본값을 채우면 사람이 준 것과
     * 코드가 지어낸 것이 섞인다 — 하네스가 하지 않기로 한 일이다.
     */
    private void writeCharacter(Path dir, CreateRequest form, List<Path> photos)
            throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", blank(form.name()));
        doc.put("character", blank(form.character()));
        Map<String, String> fields = new LinkedHashMap<>();
        if (form.fields() != null) {
            form.fields().forEach((k, v) -> {
                if (notBlank(v)) {
                    fields.put(k, v.trim());
                }
            });
        }
        doc.put("fields", fields);
        doc.put("genre", blank(form.genre()));
        doc.put("world", Map.of("preset", "", "text", ""));
        doc.put("story", blank(form.story()));
        if (photos.size() == 1) {
            doc.put("photo", photos.get(0).toString());
        } else if (!photos.isEmpty()) {
            doc.put("photo", photos.stream().map(Path::toString).toList());
        }
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("character.json").toFile(), doc);
    }

    /**
     * 사람이 넣은 것을 DB 에 남길 모양으로.
     *
     * <b>사진은 뺀다.</b> 사람 얼굴이 들어올 수 있는 값이고, 여기 쌓을 것이
     * 아니다 — 몇 장을 올렸는지만 적는다.
     */
    private String inputOf(CreateRequest form) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", blank(form.name()));
        doc.put("character", blank(form.character()));
        doc.put("genre", blank(form.genre()));
        doc.put("story", blank(form.story()));
        doc.put("style", blank(form.style()));
        doc.put("fields", form.fields() == null ? Map.of() : form.fields());
        doc.put("photos", form.photosData() == null ? 0 : form.photosData().size());
        try {
            return mapper.writeValueAsString(doc);
        } catch (IOException e) {
            log.warn("입력을 남기지 못했습니다 (job 은 그대로 진행합니다)", e);
            return null;
        }
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String blank(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 화면이 보내는 것.
     *
     * <h2>이름을 자바 식으로 바꾸지 않는다</h2>
     *
     * 화면은 프로토타입에서 옮겨 온 것이라 파이썬이 받던 이름을 그대로
     * 보낸다 — {@code photos_data} · {@code agree_ip}. 자바 쪽만 camelCase 로
     * 적어 두면 <b>그 두 칸이 통째로 안 들어온다.</b> 실제로 그랬다: 저작권에
     * 동의하고 눌러도 "동의해야 합니다" 로 막혔고, 사진도 같이 버려졌다.
     *
     * 그래서 <b>줄 위의 이름은 파이썬 것</b>으로 두고, 자바 이름은 별명으로
     * 같이 받는다(옛 호출을 안 깨뜨리려고).
     *
     * <h2>동의 칸은 {@code Boolean} 이다</h2>
     *
     * {@code boolean} 으로 두면 그 칸이 <b>없을 때</b> Jackson 이 본문 전체를
     * 거절한다 — 사람에게는 "입력값이 올바르지 않습니다" 라는, 무엇을 고쳐야
     * 하는지 알 수 없는 말만 남는다. 없으면 안 한 것으로 보고, 그 다음
     * {@code create()} 가 <b>왜</b> 안 되는지 한글로 말하게 둔다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)   // 화면이 안 읽히는 칸을 하나 더 보낸다(photo_note)
    public record CreateRequest(String name, String character, String genre, String story,
                                String style, Map<String, String> fields,
                                @JsonProperty("photos_data") @JsonAlias("photosData")
                                List<String> photosData,
                                /* presign 으로 올린 사진의 키. photos_data 대신 이것을
                                   보내면 본문에 사진이 안 실린다 — 넷이면 20MB 가 넘던
                                   요청이 몇백 바이트가 된다. 둘 다 오면 키를 먼저 쓴다. */
                                @JsonProperty("photo_keys") @JsonAlias("photoKeys")
                                List<String> photoKeys,
                                @JsonProperty("agree_ip") @JsonAlias("agreeIp")
                                Boolean agreeIp,
                                Boolean checkpoints, String uid,
                                /* 캐릭터 탭에서 골라 온 것. 있으면 이름·설명·그림을
                                   여기서 붙인다 — 화면이 그림을 내려받아 다시 올릴
                                   이유가 없다. */
                                @JsonProperty("character_id") @JsonAlias("characterId")
                                String characterId) {

        public CreateRequest {
            agreeIp = agreeIp != null && agreeIp;
        }
    }
}
