package com.lore.zzal.pet;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.common.user.User;
import com.lore.common.user.UserRepository;
import com.lore.zzal.generation.GenJob;
import com.lore.zzal.generation.GenJobRepository;
import com.lore.zzal.generation.GenKind;
import com.lore.zzal.generation.GenStatus;
import com.lore.zzal.generation.GenStepRecordRepository;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.PetHatchRequested;
import com.lore.zzal.generation.StepLabels;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSeeder;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.night.NightPlanner;
import com.lore.zzal.text.Josa;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 펫 생성·조회·돌봄·잠.
 *
 * <h3>★ 시각은 두 벌이다 — 실제 시각과 펫의 시각</h3>
 * 컨트롤러가 주는 {@code realNow} 는 서버 시계다. 펫에는 dev 오프셋이 걸릴 수 있어({@link ZzalPet#now})
 * 모든 규칙은 <b>펫의 시각</b>으로 판정한다. 여기서 한 번 변환하고 아래로는 펫의 시각만 흐른다.
 * 응답의 {@code serverNow} 도 펫의 시각이다(화면은 그 시계만 본다).
 *
 * <h3>"할 수 있나" 는 여기서 묻고, 결과는 엔티티가 적는다</h3>
 * 서버는 "무엇을 눌렀다" 만 받고 결과는 서버가 정한다. 브라우저가 보낸 수치를 믿으면 개발자도구로
 * 게이지를 채울 수 있다. 거절 이유는 사용자 말로 답한다({@link ErrorCode}).
 */
@Service
public class PetService {

    private final ZzalPetRepository petRepository;
    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final StepLabels labels;
    private final UserRepository userRepository;
    private final S3Service s3Service;
    private final HatchService hatchService;
    private final ApplicationEventPublisher events;
    private final MotionCatalog catalog;
    private final ZzalMotionRepository motionRepository;
    private final MotionSeeder motionSeeder;
    private final NightPlanner nightPlanner;
    private final com.lore.zzal.scene.SceneService sceneService;

    public PetService(ZzalPetRepository petRepository,
                      GenJobRepository jobRepository,
                      GenStepRecordRepository stepRepository,
                      StepLabels labels,
                      UserRepository userRepository,
                      S3Service s3Service,
                      HatchService hatchService,
                      ApplicationEventPublisher events,
                      MotionCatalog catalog,
                      ZzalMotionRepository motionRepository,
                      MotionSeeder motionSeeder,
                      NightPlanner nightPlanner,
                      com.lore.zzal.scene.SceneService sceneService) {
        this.catalog = catalog;
        this.motionRepository = motionRepository;
        this.motionSeeder = motionSeeder;
        this.nightPlanner = nightPlanner;
        this.sceneService = sceneService;
        this.petRepository = petRepository;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.labels = labels;
        this.userRepository = userRepository;
        this.s3Service = s3Service;
        this.hatchService = hatchService;
        this.events = events;
    }

    /**
     * 펫을 만들고 부화를 시작한다. 기다리지 않고 즉시 돌아온다 — 생성은 2분 넘게 걸리고,
     * 그걸 HTTP 응답으로 붙들면 브라우저와 ALB 가 먼저 끊어 다 만들어 놓고도 실패로 보인다.
     * 검사는 싼 것부터 — 부화 중인지 → 자리 → 이미지 키(가장 비싸고 통과하면 소모).
     */
    @Transactional
    public ZzalPet create(Long userId, String name, String note, String imageKey, Instant now) {
        petRepository.findFirstByUserIdAndPhase(userId, PetPhase.HATCHING)
                .ifPresent(hatching -> {
                    throw new BusinessException(ErrorCode.ZZAL_PET_ALREADY_HATCHING,
                            "%s 부화 중이에요".formatted(Josa.nameSubject(hatching.getName())));
                });

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        // 자리를 차지하는 것은 HATCHING·ALIVE 뿐. 떠난 아이(DEAD)·태어나지 못한 알(FAILED)은 자리를 비운다.
        long occupied = petRepository.countByUserIdAndPhaseIn(userId, PetPhase.OCCUPYING_SLOT);
        if (occupied >= user.getPetSlots()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_LIMIT_REACHED);
        }

        s3Service.consume(userId, imageKey, now);

        ZzalPet pet = petRepository.save(ZzalPet.hatch(userId, name, note, imageKey, now));
        String version = hatchService.currentVersion();
        pet.setHatchPipelineVersion(version);
        GenJob job = jobRepository.save(GenJob.start(pet.getId(), GenKind.HATCH, 1, version, now));
        events.publishEvent(new PetHatchRequested(job.getId(), pet.getId(), version));
        return pet;
    }

    /** 내 펫 하나. 남의 펫이면 403 이 아니라 404 — 403 은 "그 번호의 펫이 있다" 를 알려준다. */
    @Transactional(readOnly = true)
    public ZzalPet get(Long userId, Long petId) {
        // 읽기 전용 — 잠그지 않는다(읽기 트랜잭션에서 FOR UPDATE 는 뜻이 없다).
        return petRepository.findById(petId)
                .filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
    }

    /**
     * 내 펫을 <b>잠그고</b> 꺼낸다. 상태를 바꾸는 모든 길(정산·돌봄·잠·dev 시계)이 여기를 지난다.
     *
     * <h3>★★ 왜 잠그는가 — 같은 펫에 요청 둘이 겹치면 하나가 사라진다</h3>
     * 두 요청이 같은 행을 각자 읽어 각자 저장하면 나중 저장이 먼저 것을 덮어쓴다. 리뷰 실측: FEED 와 SNACK 을
     * 동시에 보내면 둘 다 200 인데 FEED 가 소실됐다(3/3). 놀이 시작({@code GameService.start})과 같은 방식으로
     * {@code SELECT … FOR UPDATE} 를 걸어 같은 펫의 요청을 직렬화한다. 다른 펫끼리는 안 기다린다.
     *
     * ★ 같은 클래스 안에서 자기 메서드를 부르면 프록시를 안 거쳐 {@code @Transactional} 이 무시된다.
     *   그래서 아래 메서드들은 {@link #get} 이 아니라 이것을 부른다(2026-09-02 에 이 함정으로
     *   "부화 완료" 로그는 찍히는데 DB 는 QUEUED 인 일이 있었다). 잠금은 트랜잭션 안에서만 뜻이 있다.
     */
    private ZzalPet findMine(Long userId, Long petId) {
        return petRepository.findByIdForUpdate(petId)
                .filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<ZzalPet> list(Long userId) {
        return petRepository.findByUserIdOrderByIdDesc(userId);
    }

    /**
     * 그 펫의 동작 행(seq → 행). v2 부화 펫은 18행, v1 펫은 옛 seq(0부터) 행이라 카탈로그 seq 와 안 겹쳐 비어 보인다.
     * 심화 행동 상태(`motions[].advanced`)의 재료. 읽기만 한다.
     */
    @Transactional
    public Map<Integer, ZzalMotion> motionRows(Long petId) {
        Map<Integer, ZzalMotion> rows = rowsOf(petId);
        // ★ 자가 치유(#218 리뷰) — markPetAlive 는 커밋됐는데 18행 저장이 실패하면 "ALIVE 인데 행 0개" 로 영구 고착된다
        //   (CHECK 제약 사고가 그 모양). ALIVE 펫의 행이 18 미만이면 멱등 seed 로 채운다(PR-5 전 부화한 펫도 여기서 따라온다).
        if (rows.size() < catalog.all().size()) {
            petRepository.findById(petId)
                    .filter(p -> p.isAlive() && p.getHatchedAt() != null)
                    .ifPresent(p -> {
                        int made = motionSeeder.seed(petId, p.getHatchedAt());
                        if (made > 0) {
                            org.slf4j.LoggerFactory.getLogger(PetService.class)
                                    .warn("동작 행 자가 치유 — petId={} {}행 채움(부화 완료 때 빠졌던 것)", petId, made);
                        }
                    });
            return rowsOf(petId);
        }
        return rows;
    }

    private Map<Integer, ZzalMotion> rowsOf(Long petId) {
        return motionRepository.findByPetIdOrderBySeqAsc(petId).stream()
                .filter(m -> m.getLayer() != null)
                .collect(Collectors.toMap(ZzalMotion::getSeq, m -> m, (a, b) -> a));
    }

    // ── 조회 = 정산 ───────────────────────────────────────────────────────

    /**
     * 조회하면서 흐른 시간을 반영한다. 읽기 전용이 아니다 — 조회용 계산과 행동용 반영을 따로 두면
     * 언젠가 두 식이 어긋나고, 그때는 화면과 판정이 다른 값을 말한다.
     * 그날 처음 열었으면 함께한 날 +1(정본 3장). 떠남 예고 취소는 PR-11.
     */
    @Transactional
    public ZzalPet refresh(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = findMine(userId, petId);
        touch(pet, realNow);
        return pet;
    }

    @Transactional
    public List<ZzalPet> refreshAll(Long userId, Instant realNow) {
        List<ZzalPet> pets = petRepository.findByUserIdOrderByIdDesc(userId);
        pets.forEach(p -> touch(p, realNow));
        return pets;
    }

    /** 정산 + 방문(그날 처음이면 함께한 날 +1) + 아침 공개. 조회든 행동이든 펫을 만지는 모든 길이 여기를 지난다. */
    private void touch(ZzalPet pet, Instant realNow) {
        if (!pet.isAlive()) {
            return;
        }
        Instant now = pet.now(realNow);
        pet.settle(now);
        // ★★ 순서가 중요하다 — 부재 장면은 <b>방문 기록 전에</b> 정산해야 한다.
        //   {@code visit} 이 "부재는 여기서 끝" 이라며 부재 시계를 0으로 끊기 때문에,
        //   뒤로 미루면 방금 비운 시간이 통째로 사라진다.
        int made = sceneService.recordAbsence(pet, now) + sceneService.recordNight(pet);
        if (made > 0) {
            pet.markSceneMade();
        }
        pet.visit(now);
        reveal(pet, now);
        openPieces(pet, now);
    }

    /**
     * 조각 4칸 등장 — 2층 8종이 다 열린 뒤 <b>처음 맞는 기상</b>(정본 6·16장).
     *
     * ★ 여기(서비스)에서 판정하는 이유 — "2층 8종이 다 열렸나" 는 카탈로그와 해금 규칙을 알아야 답할 수 있고,
     *   엔티티는 그 둘을 모른다. 엔티티는 "언제 다 열렸는지" 와 "언제 열어 줬는지" 만 기억한다.
     */
    private void openPieces(ZzalPet pet, Instant now) {
        if (pet.isPiecesEnabled()) {
            return;
        }
        int layerTwoTotal = (int) catalog.basic().stream()
                .filter(spec -> spec.layer() == com.lore.zzal.motion.MotionLayer.BASIC_2).count();
        if (UnlockRules.openedLayerTwo(pet, catalog) >= layerTwoTotal) {
            pet.markLayerTwoDone(now);
        }
        if (pet.readyForPieces(now)) {
            pet.enablePieces(now);
        }
    }

    /** 그 펫의 혼자 논 장면(최근 것부터, 최대 3). */
    @Transactional(readOnly = true)
    public List<com.lore.zzal.scene.ZzalScene> scenes(Long petId) {
        return sceneService.recent(petId);
    }

    /**
     * 아침 공개 — 검수를 통과한(OPEN) 동작을 <b>펫이 깨어 있는 첫 정산</b>에 도착시킨다(정본 2장 "기상 첫 화면").
     *
     * <h3>★ 왜 시각이 아니라 "깨어 있는 첫 정산" 인가</h3>
     * "아침 7시에 준다" 로 못 박으면 두 가지가 어긋난다 — (1) 판정이 10:00 을 넘기면 그날은 못 준다.
     * 정본 16장은 그 경우 <b>낮에 도착</b>하라고 한다. (2) 늦잠 자는 펫에게 자는 동안 도착하면
     * "일어나 보니 이미 알고 있던 일" 이 된다. 그래서 <b>깨어 있는 첫 정산</b> 하나로 둘 다 만족시킨다.
     * 자는 동안에는 아무것도 안 찍히고, 깨는 순간(사용자가 깨우든 10:00 자동이든) 그 정산에서 도착한다.
     *
     * ★ 도착 시각({@code revealedAt})이 곧 "사용자가 볼 수 있다" 의 판정이다 — {@code advancedImageKey()} 가
     *   그 전에는 null 을 준다. 검수 대기 중인 그림이 화면에 새는 길을 여기 한 곳으로 모았다.
     */
    private void reveal(ZzalPet pet, Instant now) {
        if (pet.isSleeping()) {
            return;
        }
        List<ZzalMotion> arrived = motionRepository.findByPetIdAndStatusAndRevealedAtIsNull(
                pet.getId(), MotionStatus.OPEN);
        arrived.forEach(m -> m.reveal(now));
        if (!arrived.isEmpty()) {
            // ★ 자연 발병은 심화 행동이 열린 뒤에만 예약된다(정본 16장). 1·2층 기간엔 방치 발병만 있다.
            //   "받은 순간" 을 기준으로 삼는 이유 — 검수 통과 시각은 사용자가 모르는 서버 사정이다.
            pet.scheduleNaturalSickness();
        }
    }

    /**
     * "배워왔어요" 를 확인했다 — {@code learnedToday} 에서 빠진다.
     *
     * ★ 도착하지 않은 동작에는 못 찍는다({@code ZZAL_MOTION_NOT_OPEN}) — 안 그러면 화면이
     *   아직 오지도 않은 것을 미리 지워 버릴 수 있다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Action markSeen(Long userId, Long petId, int seq, Instant realNow) {
        ZzalPet pet = alive(userId, petId, realNow);
        Instant now = pet.now(realNow);
        ZzalMotion row = motionRepository.findByPetIdAndSeq(petId, seq)
                .filter(ZzalMotion::isRevealed)
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_MOTION_NOT_OPEN));
        return withUnlockDiff(pet, () -> row.markSeen(now));
    }

    /**
     * 행동 결과 — 펫의 새 상태, 이번 행동으로 열린 2층 동작(seq), 그리고 <b>방금 나았나</b>.
     *
     * ★★ 거절({@link BusinessException})이 나도 <b>정산은 되돌리지 않는다</b>(위 메서드들의 {@code noRollbackFor}).
     *   모든 행동은 먼저 흐른 시간을 반영하고(settle) 그다음에 "할 수 있나" 를 묻는다. 기본값대로 롤백하면
     *   거절 한 번에 그 정산이 통째로 사라져 <b>화면이 말하는 상태와 DB 가 한 요청 동안 어긋난다</b>
     *   (영구 손실은 아니지만 다음 조회에서 시간이 되감긴 것처럼 보인다 — #225 리뷰 하-1).
     *   거절은 검사 단계에서 나므로 중간까지 바뀐 값이 남을 자리가 없다.
     *
     * ★ {@code justHealed} 를 응답에 싣는 이유 — 정본 5장의 "나은 동작(기쁜 자세 + 반짝) 1회" 는
     *   <b>한 번만</b> 나와야 한다. 상태(안 아픔)로는 "방금 나은 것" 과 "원래 안 아팠던 것" 을 못 가른다.
     */
    public record Action(ZzalPet pet, List<Integer> justUnlocked, boolean justHealed) {

        public Action(ZzalPet pet, List<Integer> justUnlocked) {
            this(pet, justUnlocked, false);
        }

        Action healed() {
            return new Action(pet, justUnlocked, true);
        }
    }


    /** 행동 전후의 열린 동작을 비교해 새로 열린 seq 를 얻는다(폭죽). 저장하지 않고 계산한다(UnlockRules). 채팅·게임도 이걸 쓴다. */
    public Action withUnlockDiff(ZzalPet pet, Runnable action) {
        Set<String> before = Set.copyOf(UnlockRules.unlockedKeys(pet, catalog));
        action.run();
        List<Integer> opened = UnlockRules.unlockedKeys(pet, catalog).stream()
                .filter(k -> !before.contains(k))
                .map(k -> catalog.byKey(k).orElseThrow().seq())
                .sorted()
                .toList();
        return new Action(pet, opened);
    }

    // ── 돌봄 6종 (정본 4·5장) ─────────────────────────────────────────────

    @Transactional(noRollbackFor = BusinessException.class)
    public Action care(Long userId, Long petId, CareAction action, Instant realNow) {
        ZzalPet pet = awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        boolean wasSick = pet.isSick();
        Action result = withUnlockDiff(pet, () -> doCare(pet, action, now));
        // 약을 먹고 나은 그 응답에만 "나은 동작" 연출이 실린다(정본 5장).
        return wasSick && !pet.isSick() ? result.healed() : result;
    }

    private void doCare(ZzalPet pet, CareAction action, Instant now) {
        switch (action) {
            case FEED -> {
                if (pet.getFood() <= 0) {
                    throw new BusinessException(ErrorCode.ZZAL_NO_FOOD);
                }
                if (pet.getFullness() >= ZzalRules.GAUGE_MAX) {
                    throw new BusinessException(ErrorCode.ZZAL_CARE_NOT_NEEDED,
                            "%s 배가 불러요".formatted(Josa.nameTopic(pet.getName())));
                }
                pet.feed(now);
            }
            // ★ 간식은 행복이 가득이어도 받는다(상훈님 2026-09-05 결정 — 원조도 간식은 항상 먹고 과다 시 병).
            //   밥만 가득이면 거절. 연속 5개 배탈은 PR-8.
            case SNACK -> {
                if (pet.isSick()) {
                    throw new BusinessException(ErrorCode.ZZAL_SICK_REFUSES);
                }
                pet.snack(now);
            }
            // 쓰다듬기는 거절이 없다 — 하루 3회를 넘어도 반응 동작은 나온다(16장). 친밀도만 안 오른다.
            case PET -> pet.pet(now);
            case CLEAN -> {
                if (pet.getTrash() <= 0) {
                    throw new BusinessException(ErrorCode.ZZAL_CARE_NOT_NEEDED, "바닥이 이미 깨끗해요");
                }
                pet.clean(now);
            }
            case BATH -> {
                if (pet.isTodayBathDone()) {
                    throw new BusinessException(ErrorCode.ZZAL_BATH_DONE_TODAY);
                }
                pet.bath(now);
            }
            case MEDICINE -> {
                if (!pet.isSick()) {
                    throw new BusinessException(ErrorCode.ZZAL_CARE_NOT_NEEDED,
                            "%s 아프지 않아요".formatted(Josa.nameTopic(pet.getName())));
                }
                pet.medicine(now);
            }
        }
    }

    // ── 잠 (정본 2·12장) ──────────────────────────────────────────────────

    /**
     * 재운다. 19:00~23:00 밤잠, 아기 60분 안에는 낮잠 한 번.
     * 창 밖이면 {@code ZZAL_NOT_SLEEP_TIME} — 화면은 "저녁 7시가 되면 재워 주세요" 를 띄운다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Action sleep(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        if (pet.sleepKindAvailable(now) == null) {
            throw new BusinessException(ErrorCode.ZZAL_NOT_SLEEP_TIME, "저녁 7시가 되면 재워 주세요");
        }
        Action a = withUnlockDiff(pet, () -> pet.sleep(now));
        // ★ 재우는 그 응답에 밤 연습 장면이 실리게 한다(touch 는 잠들기 전에 돌았다).
        if (sceneService.recordNight(pet) > 0) {
            pet.markSceneMade();
        }
        // ★ 밤잠에 든 순간 = 굽기 큐 등록(정본 2장 "잠드는 순간 하는 일"). 23:00 자동 취침은 스위프가 같은 일을 한다.
        if (pet.getSleepKind() == SleepKind.NIGHT) {
            nightPlanner.plan(pet, AwakeClock.dateOf(now));
        }
        return a;
    }

    /**
     * 깨운다. 밤잠은 07:00~10:00, 낮잠은 5분 뒤.
     *
     * ★ 먼저 정산한다 — 10:00 이 지났으면 정산 중에 저절로 깨어 있어 {@code ZZAL_PET_NOT_SLEEPING} 이 된다.
     *   그게 맞다. "깨웠다" 는 보상(친밀도 +10)은 창 안에서 사용자가 눌렀을 때만.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Action wake(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = findMine(userId, petId);
        if (!pet.isAlive()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_ALIVE);
        }
        Instant now = pet.now(realNow);
        touch(pet, realNow);
        if (!pet.isSleeping()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_SLEEPING);
        }
        if (!pet.canWake(now)) {
            throw new BusinessException(ErrorCode.ZZAL_NOT_WAKE_TIME,
                    pet.getSleepKind() == SleepKind.NAP ? "조금만 더 재워 주세요" : "아침 7시에 깨워 주세요");
        }
        Action action = withUnlockDiff(pet, () -> pet.wake(now));
        // ★★ 깨우는 그 응답에 아침 도착이 실려야 한다(#224 리뷰 상-2). touch 는 이 메서드 앞에서 돌았고
        //   그때는 자는 중이라 아무것도 안 찍혔다. 여기서 안 부르면 "깨웠다" 응답에는 안 오고 다음 조회에서야 온다 —
        //   "행동 응답 = 최신 상태" 원칙을 어기고, 화면은 깨우자마자 새로고침해야 배워 온 것을 본다.
        reveal(pet, now);
        return action;
    }

    // ── 성격·배경·공유 (정본 6·10·15장) ───────────────────────────────────

    /** 성격·세계관. 언제든, 자는 중에도(정본 10장 "언제든 변경"). */
    @Transactional(noRollbackFor = BusinessException.class)
    public Action choosePersonality(Long userId, Long petId, Personality personality, String world, Instant realNow) {
        ZzalPet pet = alive(userId, petId, realNow);
        return withUnlockDiff(pet, () -> pet.choosePersonality(personality, world));
    }

    /** 배경 바꾸기 — 2층 4종이 열린 뒤(정본 6장). 값은 검증하지 않는다(해석 6). */
    @Transactional(noRollbackFor = BusinessException.class)
    public Action changeBackground(Long userId, Long petId, String background, Instant realNow) {
        ZzalPet pet = alive(userId, petId, realNow);
        if (UnlockRules.openedLayerTwo(pet, catalog) < ZzalRules.BACKGROUND_UNLOCK_LAYER2_OPEN) {
            throw new BusinessException(ErrorCode.ZZAL_FEATURE_LOCKED,
                    "동작을 %d개 더 배우면 배경을 바꿀 수 있어요".formatted(
                            ZzalRules.BACKGROUND_UNLOCK_LAYER2_OPEN - UnlockRules.openedLayerTwo(pet, catalog)));
        }
        return withUnlockDiff(pet, () -> pet.changeBackground(background));
    }

    /**
     * 다운로드·공유 기록. 대상 = 지금 열린 동작 어느 것이든 — 기본 행동(해금)과 <b>도착한 심화 행동</b> 둘 다(16장).
     * 모르는 key 도 "안 열린 동작" 으로 답한다 — 카탈로그 밖 이름을 구분해 주면 key 목록을 훑는 수단이 된다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public Action share(Long userId, Long petId, String motionKey, Instant realNow) {
        ZzalPet pet = alive(userId, petId, realNow);
        Map<Integer, ZzalMotion> rows = rowsOf(petId);
        boolean open = catalog.byKey(motionKey)
                // 기본 행동은 해금 규칙으로, 심화 행동(선물 포함)은 "도착했나" 로 판정한다(정본 16장).
                .map(spec -> UnlockRules.isUnlocked(pet, spec, catalog)
                        || (rows.get(spec.seq()) != null && rows.get(spec.seq()).isRevealed()))
                .orElse(false);
        if (!open) {
            throw new BusinessException(ErrorCode.ZZAL_MOTION_NOT_OPEN);
        }
        return withUnlockDiff(pet, pet::share);
    }

    // ── 개발용 시계 (DevClockController 만 부른다) ─────────────────────────

    /**
     * 이 펫의 시계를 {@code by} 만큼 앞으로 민다. 규칙은 한 글자도 안 바뀌고 기다림만 사라진다.
     * 남의 펫은 못 건드린다 — 돌봄 API 와 같은 {@link #findMine} 을 탄다.
     */
    @Transactional
    public ZzalPet advanceClock(Long userId, Long petId, Duration by, Instant realNow) {
        ZzalPet pet = findMine(userId, petId);
        pet.advanceDevClock(by);
        touch(pet, realNow);
        return pet;
    }

    /**
     * dev — 그 자리의 심화 행동을 가짜 그림으로 즉시 검수 통과시킨다(아침 도착 화면 확인용).
     *
     * ★ 도착까지 건너뛰지는 않는다. {@code revealedAt} 은 {@link #touch} 가 규칙대로 찍는다 —
     *   그래야 "자는 동안에는 안 온다 / 낮에 판정되면 낮에 온다" 를 여기서 실제로 확인할 수 있다.
     */
    @Transactional
    public ZzalPet forceOpen(Long userId, Long petId, int seq, Instant realNow) {
        ZzalPet pet = alive(userId, petId, realNow);
        Instant now = pet.now(realNow);
        ZzalMotion row = motionRepository.findByPetIdAndSeq(petId, seq)
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_MOTION_NOT_OPEN));
        row.toReview("images/zzal/pets/%d/motions/%d/motion.webp".formatted(petId, row.getId()),
                com.lore.zzal.motion.MotionSource.API,
                com.lore.zzal.motion.GateVerdict.REVIEW, "dev force-open", "dev");
        row.approve(now);
        // 깨어 있으면 이 자리에서 바로 도착한다(자는 중이면 깨어난 뒤 첫 정산).
        reveal(pet, now);
        return pet;
    }

    /** 이 펫의 지금을 {@code target} 으로 맞춘다. */
    @Transactional
    public ZzalPet setClock(Long userId, Long petId, Instant target, Instant realNow) {
        ZzalPet pet = findMine(userId, petId);
        pet.setDevClock(target, realNow);
        touch(pet, realNow);
        return pet;
    }

    // ── 보내기 ────────────────────────────────────────────────────────────

    /**
     * 보낸다(놓아주기). 자리가 비어 다른 그림으로 새로 시작할 수 있다.
     * 부화 중에는 막는다(굽는 작업이 주인을 잃는다). 이미 떠난 아이면 조용히 넘어간다. 행은 지우지 않는다.
     */
    @Transactional
    public ZzalPet release(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = findMine(userId, petId);
        if (pet.isHatching()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_RELEASE_NOT_ALLOWED,
                    "%s 아직 부화 중이에요".formatted(Josa.nameSubject(pet.getName())));
        }
        touch(pet, realNow);
        pet.release(pet.now(realNow));
        return pet;
    }

    /**
     * 지금 뭔가를 할 수 있는 상태인지 확인하고, 흐른 시간을 반영해 돌려준다.
     * 돌봄·재우기가 공통으로 거치는 문. (여행 중 거절은 PR-11)
     */
    /** 잠그고·정산하고·방문하고·깨어 있는지 확인. 채팅 답·게임 시작이 같은 문을 쓴다(트랜잭션 안에서 부를 것). */
    public ZzalPet awake(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = alive(userId, petId, realNow);
        if (pet.isSleeping()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_SLEEPING,
                    "%s 자고 있어요".formatted(Josa.nameSubject(pet.getName())));
        }
        return pet;
    }

    /** ALIVE 인지 확인하고 잠그고 정산·방문한다. 자는 중에도 되는 것(성격·배경·공유·채팅 조회)이 거치는 문. */
    public ZzalPet alive(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = findMine(userId, petId);
        if (!pet.isAlive()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_ALIVE);
        }
        touch(pet, realNow);
        return pet;
    }

    /** 지금 하는 일을 사람 말로. 부화 중이 아니면 비어 있다. */
    @Transactional(readOnly = true)
    public String currentStepLabel(Long petId) {
        return jobRepository.findFirstByPetIdOrderByIdDesc(petId)
                .flatMap(job -> stepRepository.findByJobIdOrderBySeqAsc(job.getId()).stream()
                        .filter(s -> s.getStatus() == GenStatus.RUNNING)
                        .findFirst())
                .map(s -> labels.label(s.getName()))
                .orElse(null);
    }
}
