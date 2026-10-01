package com.lore.webtoon.job;

import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.work.WorkLedger;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.webtoon.safety.SafetyGuard;
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
import java.util.Collection;
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
            "pages", "장면 나누기 · 페이지 그림",
            "bind", "검수 · 합본");

    private static final String DEFAULT_STYLE = WebtoonStyles.DEFAULT_STYLE;

    private final WebtoonJobRepository jobs;
    private final SafetyGuard safety;
    private final JobStore store;
    private final JobQueue queue;
    private final JobRunner runner;
    private final JobProgress progress;
    private final StoryStore stories;
    private final WorkLedger works;
    private final JobNotice notice;
    private final CharacterService characters;
    private final CharacterOwner owner;
    private final PrivateArt art;
    private final RunArt runArt;
    private final WebtoonCastSheetRepository castSheets;
    private final S3Service uploads;
    private final S3Storage storage;
    private final Path jobsDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public JobService(WebtoonJobRepository jobs, JobStore store, JobQueue queue,
                      JobRunner runner,
                      JobProgress progress, StoryStore stories, WorkLedger works,
                      JobNotice notice, CharacterService characters, CharacterOwner owner, PrivateArt art,
                      S3Service uploads, S3Storage storage, SafetyGuard safety,
                      WebtoonCastSheetRepository castSheets, RunArt runArt,
                      @Value("${lore.webtoon.python.jobs-dir:}") String jobsDir) {
        this.castSheets = castSheets;
        this.runArt = runArt;
        this.jobs = jobs;
        this.safety = safety;
        this.works = works;
        this.notice = notice;
        this.characters = characters;
        this.owner = owner;
        this.art = art;
        this.uploads = uploads;
        this.storage = storage;
        this.store = store;
        this.queue = queue;
        this.runner = runner;
        this.progress = progress;
        this.stories = stories;
        this.jobsDir = Path.of(jobsDir == null || jobsDir.isBlank()
                ? "webtoon/ai/work/jobs" : jobsDir).toAbsolutePath().normalize();
    }

    /** 「만들고 싶은 내용」 상한(#548). 단편 소설 한 편 분량. 화면 `wizardData.OWN_STORY_MAX` 와 같다. */
    static final int OWN_STORY_MAX = 20_000;

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
        /* 글은 만들기 전에 거른다(#80). 파이썬까지 가서 모델이 거절하면 돈은 이미 나갔고
           사람은 "만들기가 안 된다" 로만 안다. 사진은 아직 안 본다(safety.md). */
        safety.checkText("webtoon-create", form.name(), form.character(), form.genre(), form.story(),
                form.photoNote(), form.settings(), form.title(), form.episode(),
                form.fields() == null ? null : String.join("\n", form.fields().values()));
        /* 어느 길인가(#548). own(만들고 싶은 내용이 있어요)은 적은 내용이 있어야 하고
           확인 자리가 항상 있다. 확인하며 가는 길(own, 또는 quick 의 확인하고 만들기)은
           며칠 뒤에 돌아와 이어서 할 수 있어야 해서 로그인한 사람만 받는다 — 게스트는
           브라우저가 바뀌면 작업을 못 찾는다. */
        boolean own = "own".equalsIgnoreCase(form.mode());
        boolean checkpoints = own || form.checkpoints() == null || form.checkpoints();
        if (own && !notBlank(form.story())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "만들고 싶은 내용을 적어 주세요 — 짧은 아이디어 한 줄도 괜찮아요.");
        }
        if (form.story() != null && form.story().length() > OWN_STORY_MAX) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "내용이 너무 길어요. " + OWN_STORY_MAX + "자까지 적을 수 있어요.");
        }
        if (checkpoints && userId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "확인하며 만들기는 로그인한 뒤에 할 수 있어요. 나중에 돌아와 이어서 하려면 계정이 필요해요.");
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
               data URL 도 계속 받는다 — 화면이 한 번에 갈아타지 않아도 된다.
               게스트도 이제 presign 을 쓸 수 있다(guestKey 로 묶은 티켓 —
               2026-09-17, WAF 의 SizeRestrictions_BODY 가 게스트의 큰 사진
               요청을 403 으로 막던 것을 고치며 추가). 둘 다 오면 키가 이긴다. */
            List<Path> photos = form.photoKeys() != null && !form.photoKeys().isEmpty()
                    ? pullPhotos(dir, form.photoKeys(), userId, guestKey)
                    : savePhotos(dir, form.photosData());
            WebtoonCharacter picked = pickedCharacter(form.characterId(), userId, form.uid());
            Path fromCharacter = characterArt(dir, picked);
            /* 캐릭터를 골라 왔으면 그 그림을 참조로 붙인다.
             *
             * 화면은 **번호만** 보낸다. 그림은 S3 의 안 열리는 자리에 있고,
             * 브라우저가 그것을 내려받아 base64 로 다시 올리면 같은 그림이
             * 두 번 오간다 — 게다가 그 주소는 CORS 가 안 열려 있다. */
            if (fromCharacter != null) {
                photos = new ArrayList<>(photos);
                photos.add(fromCharacter);
            }
            writeCharacter(dir, form, photos, picked);
        } catch (IOException e) {
            log.error("만들기 준비에 실패했습니다 (job={})", publicId, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "만들기를 시작하지 못했습니다");
        }

        /* 그림체는 화면 키(romance)로도, 하네스 이름(romance_fantasy)으로도 받는다.
           「캐릭터 만들어보기」의 카드는 하네스 이름을 들고 있어서 — 한 컷을 그린
           그 그림체 그대로 1화를 그려야 같은 캐릭터로 읽힌다. */
        String asked = blank(form.style());
        String style = STYLE.containsValue(asked) ? asked : STYLE.getOrDefault(asked, DEFAULT_STYLE);
        String quality = WebtoonQuality.normalize(form.quality());
        String language = WebtoonLanguage.normalize(form.language());
        WebtoonJob job = jobs.save(WebtoonJob.queued(
                publicId, userId, browserUid, guestKey, style, quality, language,
                checkpoints, own ? "own" : "quick",
                inputOf(form), Instant.now()));

        /* **장부에도 적는다.**
         *
         * 프록시 길은 하네스 응답을 보고 적는데(옛 프록시), 이 길은
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
    /**
     * 이 사람이 만들던 것들 — 아직 안 끝난 작업. 첫 화면이 「만들던 웹툰 · 7/12장」
     * 알약을 띄우고, 눌러서 돌아간다. 새로고침하거나 기기를 바꿔도 하던 데로
     * 돌아올 수 있어야 해서 주소나 화면 상태가 아니라 서버가 센다.
     */
    @Transactional(readOnly = true)
    public List<JobView> activeOf(Long userId, Collection<String> uids) {
        return jobs.activeOf(userId, uids, List.of(JobStatus.QUEUED, JobStatus.RUNNING,
                        JobStatus.AWAITING_SHEET, JobStatus.AWAITING_PICK, JobStatus.AWAITING_CAST,
                        JobStatus.AWAITING_SCENES)).stream()
                .map(job -> view(job.getPublicId()))
                .toList();
    }

    /**
     * 만드는 중 카드(#548) — 마이페이지가 작업마다 캐릭터 이름과 마지막으로 손댄 때를 같이 보여 준다.
     * {@link #activeOf} 와 같은 순서로 {id, name, created_at, updated_at}.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeCardsOf(Long userId, Collection<String> uids) {
        return jobs.activeOf(userId, uids, List.of(JobStatus.QUEUED, JobStatus.RUNNING,
                        JobStatus.AWAITING_SHEET, JobStatus.AWAITING_PICK, JobStatus.AWAITING_CAST,
                        JobStatus.AWAITING_SCENES)).stream()
                .map(job -> {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("id", job.getPublicId());
                    one.put("name", str(inputOf(job).get("name")));
                    one.put("created_at", job.getCreatedAt() == null ? null : job.getCreatedAt().toString());
                    one.put("updated_at", job.getUpdatedAt() == null ? null : job.getUpdatedAt().toString());
                    return one;
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public String runOf(String publicId) {
        return store.byPublicId(publicId).getRunId();
    }

    @Transactional(readOnly = true)
    public JobView view(String publicId) {
        WebtoonJob job = store.byPublicId(publicId);
        JobProgress.Snapshot now = progress.of(job.getId());
        JobQueue.Spot spot = queue.spotOf(job);
        JobStatus at = job.getStatus();
        /* own 길(#548)은 이야기 확인(AWAITING_PICK)과 장면 확인(AWAITING_SCENES) 두 자리에서 멈추고,
           두 화면 다 인물·주인공 카드·본문·시트·내가 적은 것을 보여 준다. */
        boolean ownPause = job.isOwn() && (at == JobStatus.AWAITING_PICK || at == JobStatus.AWAITING_SCENES);
        boolean castPause = at == JobStatus.AWAITING_CAST || at == JobStatus.AWAITING_SCENES || ownPause;
        return JobView.of(job, now,
                store.directionsOf(job.getId()),
                castPause ? runner.castOf(job.getRunId()) : null,
                at == JobStatus.AWAITING_CAST ? runner.castKind(job.getRunId()) : null,
                castPause ? runner.personaOf(job.getRunId()) : null,
                at == JobStatus.AWAITING_SCENES ? runner.scenesOf(job.getId(), job.getRunId()) : null,
                ownPause ? runner.storyOf(job.getRunId()) : null,
                runner.sheetReady(job.getRunId()),
                runArt.sheetVersions(job.getRunId()),
                at == JobStatus.AWAITING_SCENES || ownPause ? castSheetsOf(job) : null,
                at == JobStatus.AWAITING_SCENES || ownPause ? inputOf(job) : null,
                WebtoonStyles.labelOf(job.getStyle()),
                STAGE_LABEL.getOrDefault(job.getStage().wire(), job.getStage().wire()),
                spot,
                notice.addressOf(job), queue.etaOf(job, now, spot));
    }

    /**
     * 다 되면 이 주소로 알린다 — <b>게스트가 적어 넣는 자리.</b>
     *
     * 로그인한 사람은 안 불러도 계정 주소로 간다. 그래도 막지는 않는다 —
     * 다른 주소로 받고 싶을 수 있다.
     *
     * 빈 값을 보내면 <b>안 받겠다</b>는 뜻이라 적어 둔 주소를 지운다.
     * 주소처럼 안 생겼으면 거절한다 — 담아 두고 보낸 척하면, 화면에는
     * 「보낼게요」가 떠 있는데 영영 아무것도 안 온다.
     *
     * @return 화면이 그대로 적을, 지금 보낼 주소 (없으면 {@code null})
     */
    public String notifyTo(String publicId, String email) {
        WebtoonJob job = store.byPublicId(publicId);
        boolean clearing = email == null || email.isBlank();
        String clean = clearing ? null : JobNotice.clean(email);
        if (!clearing && clean == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이메일 주소를 다시 확인해 주세요");
        }
        /* 이미 보낸 뒤면 store 가 안 바꾼다(거짓을 준다). 그때도 오류가
           아니다 — 바꿔 봐야 그 메일은 이미 나갔을 뿐이다. 어느 쪽이든
           **지금 실제로 보낼 주소**를 돌려준다. 화면은 그것만 적으면 된다. */
        store.notifyTo(job.getId(), clean);
        return notice.addressOf(store.byPublicId(publicId));
    }

    /** 사람이 이야기를 골랐다. */
    public void pick(String publicId, int n) {
        pick(publicId, n, null, null);
    }

    /**
     * 사람이 이야기를 고르면서 본문을 직접 고쳐 보냈을 수도 있다.
     *
     * <b>고친 내용은 실제로 다음 단계(장면 나누기)의 재료가 된다.</b>
     * 화면에서만 보여주고 끝나면 "고쳐도 그만 안 고쳐도 그만"이라는 말이
     * 거짓이 된다 — 그래서 {@link JobRunner#overwriteDirectionBody}로
     * 실제 `directions.json`의 본문을 덮어쓴 뒤에야 다음 단계로 넘어간다.
     * 비어 있거나 원래 본문과 같으면 아무것도 안 건드린다.
     */
    /**
     * 인물 단계에 답한다(#534). 현대 로맨스에서 새 인물 중 상대를 고르면 n(1~),
     * 사용자가 적은 인물을 확인하고 이대로 가면 0. 그 뒤 이야기 후보 넷을 짓는다.
     */
    /** 조연 시트를 뽑을 수 있는 자리인가(#548) — 인물 단계가 끝나 cast.json 이 있는 멈춤들. */
    private static final java.util.Set<JobStatus> CAST_SHEET_OK = java.util.Set.of(
            JobStatus.AWAITING_SCENES, JobStatus.AWAITING_PICK, JobStatus.AWAITING_SHEET);

    /**
     * 조연 시트 한 장(#548). 크레딧은 부르는 쪽(컨트롤러)이 먼저 받고, 못 그리면 {@code onFail} 로
     * 돌려준다. 그리는 동안 작업 상태는 그대로다 — 끝나면 그림을 창고에 올리고 줄을 남긴다.
     */
    public void castSheet(String publicId, String name, Long userId, Runnable onFail) {
        WebtoonJob job = store.byPublicId(publicId);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "로그인하면 조연 시트를 뽑을 수 있어요");
        }
        if (!CAST_SHEET_OK.contains(job.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 조연 시트를 뽑을 수 없습니다");
        }
        String who = name == null ? "" : name.trim();
        boolean known = runner.castOf(job.getRunId()).stream()
                .map(c -> String.valueOf(c.get("name")).trim())
                .anyMatch(n -> n.equals(who) || n.split(" ")[0].equals(who));
        if (who.isEmpty() || !known) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "그런 인물이 없습니다");
        }
        if (castSheets.existsByJobIdAndName(publicId, who)
                || runner.castSheetsPending(job.getId()).contains(who)
                || runner.castSheetsDrawn(job.getRunId()).stream().anyMatch(d -> d.equals(who) || d.split(" ")[0].equals(who))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 뽑은 인물입니다");
        }
        Long jobId = job.getId();
        java.util.concurrent.Future<Path> drawn = runner.drawCastSheet(jobId, who);
        runner.afterCastSheet(drawn, () -> {
            try {
                Path png = drawn.get();
                String key = art.upload(Files.readAllBytes(png), "image/png", false);
                castSheets.save(new WebtoonCastSheet(publicId, who, key, Instant.now()));
            } catch (Exception e) {              // noqa: 올리기·적기 실패 — 그림은 폴더에 남아 있다
                log.warn("조연 시트를 창고에 못 올렸습니다 (job={}, name={})", publicId, who, e);
                castSheets.save(new WebtoonCastSheet(publicId, who, null, Instant.now()));
            }
        }, () -> {
            log.warn("조연 시트를 그리지 못해 크레딧을 돌려줍니다 (job={}, name={})", publicId, who);
            onFail.run();
        });
    }

    /** 조연 시트 목록(#548) — 그려진 것은 ready=true, 그리는 중은 false. */
    List<Map<String, Object>> castSheetsOf(WebtoonJob job) {
        java.util.LinkedHashMap<String, Boolean> seen = new java.util.LinkedHashMap<>();
        for (String name : runner.castSheetsDrawn(job.getRunId())) {
            seen.put(name, true);
        }
        for (WebtoonCastSheet row : castSheets.findByJobIdOrderByCreatedAtAsc(job.getPublicId())) {
            seen.putIfAbsent(row.getName(), true);
        }
        for (String name : runner.castSheetsPending(job.getId())) {
            seen.put(name, false);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        seen.forEach((name, ready) -> {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", name);
            one.put("ready", ready);
            out.add(one);
        });
        return out;
    }

    public void pickCast(String publicId, int n) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_CAST) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 인물을 고를 차례가 아닙니다");
        }
        List<Map<String, Object>> cast = runner.castOf(job.getRunId());
        boolean confirm = "confirm".equals(runner.castKind(job.getRunId()));
        if (confirm ? n != 0 : (n < 1 || n > cast.size())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "그런 인물이 없습니다");
        }
        runner.resumeAfterCast(job.getId(), n);
    }

    public void pick(String publicId, int n, String editedBody, String editedTitle) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_PICK) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 고를 차례가 아닙니다");
        }
        List<Map<String, Object>> got = store.directionsOf(job.getId());
        if (n < 1 || n > got.size()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "그런 이야기가 없습니다");
        }
        if (job.isOwn()) {
            /* own 길의 이야기 확인(#548) — 본문·제목을 고쳤으면 적고(하네스 --own-save, pick.json 제목까지
               맞춘다) 장면 나누기로 간다. 후보는 하나라 n 은 1 이다. */
            safety.checkText("webtoon-scenes", editedBody, editedTitle);
            if (notBlank(editedBody) || notBlank(editedTitle)) {
                runner.saveScenes(job.getId(), List.of(), editedBody, editedTitle);
                List<Map<String, Object>> fresh = runner.directionsOf(job.getRunId());
                store.directions(job.getId(), fresh);
                stories.replace(job.getRunId(), fresh);
            }
            store.pick(job.getId(), 1);
            stories.choose(job.getRunId(), 1);
            runner.resumeAfterPick(job.getId());
            return;
        }
        String clean = editedBody == null ? "" : editedBody.strip();
        if (!clean.isEmpty()) {
            Object original = got.stream()
                    .filter(one -> Integer.valueOf(n).equals(one.get("n")))
                    .findFirst().map(one -> one.get("body")).orElse(null);
            if (!clean.equals(original)) {
                runner.overwriteDirectionBody(job.getRunId(), n, clean);
            }
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
    /** own 길의 「1화 다시 만들기」는 첫 번째 무료, 그다음부터 1크레딧(#548). quick 길 후보 다시 만들기는 무료 그대로. */
    public int restoryCost(String publicId) {
        WebtoonJob job = store.byPublicId(publicId);
        return job.isOwn() && runner.storyRedraws(job.getRunId()) >= 1 ? 1 : 0;
    }

    public void retryPick(String publicId, String note) {
        retryPick(publicId, note, () -> { });
    }

    public void retryPick(String publicId, String note, Runnable onFail) {
        WebtoonJob job = store.byPublicId(publicId);
        /* own 길(#548)은 장면 확인 자리에서도 1화를 다시 만들 수 있다 — 끝나면 이야기 확인으로 돌아가
           새 1화를 보고 장면을 다시 나눈다. */
        boolean ownScenes = job.isOwn() && job.getStatus() == JobStatus.AWAITING_SCENES;
        if (job.getStatus() != JobStatus.AWAITING_PICK && !ownScenes) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 고를 차례가 아닙니다");
        }
        if (ownScenes && runner.scenesOf(job.getId(), job.getRunId()).stream().anyMatch(s -> Boolean.TRUE.equals(s.get("busy")))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "장면을 다시 뽑는 중입니다");
        }
        safety.checkText("webtoon-scenes", note);
        runner.retryDirections(job.getId(), note == null ? "" : note.trim(), onFail);
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
        JobStatus at = job.getStatus();
        /* 장면 확인 자리(#548)에서도 시트를 다시 만들 수 있다. 끝나면 있던 자리로 돌아온다. */
        if (at != JobStatus.AWAITING_SHEET && at != JobStatus.AWAITING_SCENES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 확인할 차례가 아닙니다");
        }
        clearSheet(job.getRunId());
        runner.redrawSheet(job.getId(), note == null ? "" : note.trim(), at);
    }

    /* ---- 장면 초안(#548) ---- */

    /** 고친 장면 글(과 본문)을 적는다. 멈춤은 그대로다. */
    public void saveScenes(String publicId, List<Map<String, Object>> scenes, String body, String title) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_SCENES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 장면을 고칠 차례가 아닙니다");
        }
        if (scenes != null) {
            for (Map<String, Object> one : scenes) {
                Object text = one.get("text");
                safety.checkText("webtoon-scenes", text == null ? null : text.toString());
            }
        }
        safety.checkText("webtoon-scenes", body, title);
        /* 본문·제목은 own 길의 것이다 — quick 길에서는 고른 후보가 넷 중 하나라 바꾸지 않는다. */
        boolean own = job.isOwn();
        runner.saveScenes(job.getId(), scenes == null ? List.of() : scenes,
                own ? body : null, own ? title : null);
        if (own && (notBlank(body) || notBlank(title))) {
            stories.replace(job.getRunId(), runner.directionsOf(job.getRunId()));
            stories.choose(job.getRunId(), 1);
        }
    }

    /** 인물 카드 고치기(#548) — 이야기 확인·장면 확인 자리에서만, own 길만. */
    public void savePerson(String publicId, String who, Map<String, Object> fields) {
        WebtoonJob job = store.byPublicId(publicId);
        if (!job.isOwn() || (job.getStatus() != JobStatus.AWAITING_PICK && job.getStatus() != JobStatus.AWAITING_SCENES)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 인물을 고칠 차례가 아닙니다");
        }
        if (fields != null) {
            for (Object v : fields.values()) {
                safety.checkText("webtoon-scenes", v == null ? null : v.toString());
            }
        }
        try {
            runner.savePerson(job.getRunId(), who, fields);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("인물을 적지 못했습니다", e);
        }
    }

    /**
     * 내가 적은 것(#548) — 장면 확인 화면이 보여 준다. 만들 때 남긴 {@code input_json}
     * 에서 글만 꺼내고(사진은 장 수만), 그림체·화질·언어는 작업 줄에서.
     */
    private Map<String, Object> inputOf(WebtoonJob job) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> saved = Map.of();
        try {
            if (notBlank(job.getInputJson())) {
                saved = mapper.readValue(job.getInputJson(), new TypeReference<Map<String, Object>>() { });
            }
        } catch (IOException e) {
            log.warn("만들 때 적은 것을 못 읽었습니다 (job={})", job.getPublicId(), e);
        }
        out.put("name", str(saved.get("name")));
        out.put("description", str(saved.get("character")));
        out.put("genre", str(saved.get("genre")));
        out.put("story", str(saved.get("story")));
        out.put("episode", str(saved.get("episode")));
        out.put("settings", str(saved.get("settings")));
        out.put("title", str(saved.get("title")));
        Object photos = saved.get("photos");
        out.put("photos", photos instanceof Number n ? n.intValue() : 0);
        out.put("style", job.getStyle());
        out.put("quality", WebtoonQuality.normalize(job.getQuality()));
        out.put("language", WebtoonLanguage.normalize(job.getLanguage()));
        return out;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    /** 「이대로 웹툰 만들기」 — 장면 확인을 끝내고 그림으로. */
    public void continueScenes(String publicId) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_SCENES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 장면을 고칠 차례가 아닙니다");
        }
        runner.resumeAfterScenes(job.getId());
    }

    /** 화면이 보내는 이유 코드(#548) — 하네스 {@code own.RESCENE_REASONS} 와 같다. */
    private static final java.util.Set<String> RESCENE_REASONS = java.util.Set.of(
            "awkward", "character", "stranger", "offstory", "pacing");

    /**
     * 장면 하나만 다시 짓기(#548). 로그인한 사람만. 멈춤은 그대로고, 돌아가는 동안 그 장면은
     * {@code busy} 다. 전체 다시 나누기({@link #retryScenes})와 따로다.
     */
    /** 장면마다 첫 다시 뽑기는 무료, 그다음부터 1크레딧(#548). 이번 다시 뽑기에 드는 크레딧. */
    public int resceneCost(String publicId, int n) {
        WebtoonJob job = store.byPublicId(publicId);
        return runner.sceneRedraws(job.getRunId(), n) >= 1 ? 1 : 0;
    }

    public void retryScene(String publicId, int n, List<String> reasons, String note, Long userId, Runnable onFail) {
        WebtoonJob job = store.byPublicId(publicId);
        if (userId == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "로그인하면 장면을 다시 지을 수 있어요");
        }
        if (job.getStatus() != JobStatus.AWAITING_SCENES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 장면을 고칠 차례가 아닙니다");
        }
        boolean known = runner.scenesOf(job.getRunId()).stream().anyMatch(s -> Integer.valueOf(n).equals(s.get("n")));
        if (!known) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "그런 장면이 없습니다");
        }
        List<String> picked = reasons == null ? List.of()
                : reasons.stream().filter(r -> r != null && RESCENE_REASONS.contains(r.trim())).map(String::trim).toList();
        safety.checkText("webtoon-scenes", note);
        try {
            runner.rescene(job.getId(), n, picked, note == null ? "" : note.trim(), onFail);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    /** 장면 하나를 이전 판으로 되돌린다(#548) — 장면 확인 자리에서만, 다시 짓는 중이 아닐 때. */
    public void restoreScene(String publicId, int n, int v) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_SCENES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 장면을 고칠 차례가 아닙니다");
        }
        if (runner.rescening(job.getId(), n)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이 장면을 다시 짓는 중입니다");
        }
        try {
            runner.restoreScene(job.getRunId(), n, v);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, e.getMessage());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("장면을 되돌리지 못했습니다", e);
        }
    }

    /* ---- 시트 판(#548) ---- */

    /** 시트를 되돌릴 수 있는 자리 — 시트를 다시 그릴 수 있는 멈춤들과 같다. */
    private static final java.util.Set<JobStatus> SHEET_RESTORE_OK = java.util.Set.of(
            JobStatus.AWAITING_SHEET, JobStatus.AWAITING_SCENES, JobStatus.AWAITING_PICK);

    /** 보관한 옛 시트 판을 지금 시트로. 지금 것도 보관한 뒤 바꾼다. */
    public void restoreSheet(String publicId, int v) {
        WebtoonJob job = store.byPublicId(publicId);
        if (!SHEET_RESTORE_OK.contains(job.getStatus())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금은 시트를 되돌릴 수 없습니다");
        }
        if (runArt.sheetVersion(job.getRunId(), v) == null) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "그런 시트 판이 없습니다");
        }
        try {
            runArt.restoreSheet(job.getRunId(), v);
        } catch (IOException e) {
            throw new IllegalStateException("시트를 되돌리지 못했습니다", e);
        }
    }

    /** 「장면 다시 나누기」 — 본문·인물·시트는 두고 장면만 다시. 고친 글은 사라진다. */
    public void retryScenes(String publicId, String note) {
        WebtoonJob job = store.byPublicId(publicId);
        if (job.getStatus() != JobStatus.AWAITING_SCENES) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "지금 장면을 고칠 차례가 아닙니다");
        }
        safety.checkText("webtoon-scenes", note);
        runner.rescenes(job.getId(), note == null ? "" : note.trim());
    }

    /**
     * 시트를 다시 그리려면 먼저 치운다. 그림과 사양은 지우지 않고 판으로 보관한다
     * ({@link RunArt#archiveSheet}) — 다시 그린 것이 더 못하면 되돌릴 수 있게. 못 치운 것이 있어도 계속 간다.
     */
    private void clearSheet(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        Path dir = runner.runDir(runId);
        try {
            runArt.archiveSheet(runId);
        } catch (IOException e) {
            log.warn("옛 시트를 보관하지 못했습니다 — 지우고 갑니다 (run={})", runId, e);
        }
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
     * 못 가져와도 만들기는 안 막는다. 이름과 설명은 이미 폼에 실려 왔으므로
     * 그것만으로도 그릴 수 있다 — 여기서 막으면 S3 가 잠깐 흔들릴 때 만들기가
     * 통째로 죽는다.
     */
    private Path characterArt(Path dir, WebtoonCharacter one) {
        if (one == null) {
            return null;
        }
        try {
            byte[] bytes = art.read(one.getArtKey());
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            Path out = dir.resolve("charart.png");
            Files.write(out, bytes);
            return out;
        } catch (Exception e) {                     // noqa: 못 붙여도 만들기는 간다
            log.warn("고른 캐릭터의 그림을 못 붙였습니다 (character={})", one.getPublicId(), e);
            return null;
        }
    }

    /**
     * 골라 온 캐릭터. 없거나 남의 것이면 {@code null}.
     *
     * <b>남의 캐릭터는 안 쓴다.</b> 내 것이거나 기본 제공만 — 안 그러면 번호를
     * 찍어 넣어 남의 캐릭터로 웹툰을 만들 수 있다(그 기능은 #259 에서 따로 다룬다).
     *
     * 그림({@link #characterArt})과 카드({@link #writeCharacter})가 같은 캐릭터를
     * 쓰도록 여기서 한 번만 찾는다. 못 찾아도 만들기는 막지 않는다 — 이름과
     * 설명은 이미 폼에 실려 왔다.
     */
    private WebtoonCharacter pickedCharacter(String characterId, Long userId, String uid) {
        if (characterId == null || characterId.isBlank()) {
            return null;
        }
        try {
            /* 브라우저도 같이 넘긴다 — 로그인 안 하고 만든 캐릭터는 계정이
               아니라 이 값으로만 자기 것임을 말할 수 있다. 안 넘기면 방금
               자기가 만든 캐릭터로 웹툰을 만들려는 순간 "그런 캐릭터가
               없습니다" 가 뜬다. */
            return characters.byPublicId(characterId, userId, owner.uidsOf(userId, uid));
        } catch (Exception e) {                     // noqa: 못 찾아도 만들기는 간다
            log.warn("고른 캐릭터를 못 찾았습니다 (character={})", characterId, e);
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
    private List<Path> pullPhotos(Path dir, List<String> keys, Long userId, String guestKey) throws IOException {
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
            // 로그인했으면 계정으로, 게스트면 GuestGate 가 준 열쇠(IP 해시)로 —
            // 이 티켓이 정말 이 사람이 방금 받은 것인지 확인한다.
            if (userId != null) {
                uploads.consume(userId, key, Instant.now());
            } else {
                uploads.consumeGuest(guestKey, key, Instant.now());
            }
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
    private void writeCharacter(Path dir, CreateRequest form, List<Path> photos,
                                WebtoonCharacter picked) throws IOException {
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
        /* **사진에 붙인 한 마디를 빠뜨리지 않는다.** 하네스는 사진을 읽을 때
           이 값을 같이 본다 — 안 적어 주면 사람이 "왼쪽이 주인공이에요" 라고
           썼는데 그 말이 어디에도 안 닿는다(화면은 받아서 보내고 있었다). */
        doc.put("photo_note", blank(form.photoNote()));
        doc.put("genre", blank(form.genre()));
        doc.put("world", Map.of("preset", picked == null ? "" : blank(picked.getWorld()), "text", ""));
        doc.put("story", blank(form.story()));
        // 「만들고 싶은 내용이 있어요」(#548) — 더 적은 설정과 제목. 하네스가 own 길에서 읽는다.
        doc.put("settings", blank(form.settings()));
        doc.put("title", blank(form.title()));
        doc.put("episode", blank(form.episode()));
        doc.put("mode", "own".equalsIgnoreCase(form.mode()) ? "own" : "quick");
        if (picked != null) {
            doc.put("card", cardOf(picked));
        }
        if (photos.size() == 1) {
            doc.put("photo", photos.get(0).toString());
        } else if (!photos.isEmpty()) {
            doc.put("photo", photos.stream().map(Path::toString).toList());
        }
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(dir.resolve("character.json").toFile(), doc);
    }

    /**
     * 고른 캐릭터 카드 — 사람이 카드 화면에서 본 그대로(#458).
     *
     * 전에는 카드를 골라도 이름과 {@code description}(처음 만들 때 적은 원래
     * 설명)만 하네스에 갔다. 카드에 보이는 세계·종·이 세계에서의 자리·운명은
     * 한 줄도 안 가서, 「마법대륙의 검은여우」를 고른 사람이 「노란 후드티
     * 대학생」 이야기를 받았다(2026-09-27 로컬 확인). 하네스가 이것을 읽어
     * 「고른 캐릭터 카드」로 프롬프트에 넣는다.
     */
    private Map<String, Object> cardOf(WebtoonCharacter one) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("world", blank(one.getWorld()));
        card.put("world_label", blank(one.getWorldLabel()));
        card.put("genre", blank(one.getGenre()));
        card.put("species", blank(one.getSpecies()));
        card.put("role", blank(one.getRoleName()));
        card.put("role_tier", blank(one.getRoleTier()));
        card.put("twist", blank(one.getTwist()));
        card.put("quote", blank(one.getQuote()));
        card.put("fate", one.fateLines());
        return card;
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
        /* **사진에 붙인 한 마디를 빠뜨리지 않는다.** 하네스는 사진을 읽을 때
           이 값을 같이 본다 — 안 적어 주면 사람이 "왼쪽이 주인공이에요" 라고
           썼는데 그 말이 어디에도 안 닿는다(화면은 받아서 보내고 있었다). */
        doc.put("photo_note", blank(form.photoNote()));
        doc.put("genre", blank(form.genre()));
        doc.put("story", blank(form.story()));
        doc.put("settings", blank(form.settings()));
        doc.put("title", blank(form.title()));
        doc.put("episode", blank(form.episode()));
        doc.put("mode", "own".equalsIgnoreCase(form.mode()) ? "own" : "quick");
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
                                String characterId,
                                /* 사진에 대해 사람이 덧붙인 한 마디("왼쪽이 주인공" 등).
                                   하네스가 사진을 읽을 때 그대로 붙여 준다
                                   (new_harness/run.py 의 "첨부한 사진 n장을 보라(…)"). */
                                @JsonProperty("photo_note") @JsonAlias("photoNote")
                                String photoNote,
                                /* 얼마나 촘촘히 그릴까 — wave · surf · swell.
                                   안 보내면 기본(파도)이다. */
                                String quality,
                                /* 어느 언어로 만들까 — ko · en · ja. 안 보내면 기본(ko)이다.
                                   webtoon/fe 가 지금 화면 언어(lib/i18n.tsx 의 lang)를 그대로
                                   보낸다. */
                                String language,
                                /* 어느 길인가(#548): quick(기본) | own(만들고 싶은 내용이 있어요). */
                                String mode,
                                /* own 길의 「설정 더 적기」와 제목. 없을 수 있다. */
                                String settings,
                                String title,
                                /* own 길의 「1화에서 보여줄 것」 — 적은 내용 가운데 이번 화에 넣을 부분. 없을 수 있다. */
                                String episode) {

        public CreateRequest {
            agreeIp = agreeIp != null && agreeIp;
        }
    }
}
