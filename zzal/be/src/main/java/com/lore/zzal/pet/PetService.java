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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

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

    public PetService(ZzalPetRepository petRepository,
                      GenJobRepository jobRepository,
                      GenStepRecordRepository stepRepository,
                      StepLabels labels,
                      UserRepository userRepository,
                      S3Service s3Service,
                      HatchService hatchService,
                      ApplicationEventPublisher events,
                      MotionCatalog catalog) {
        this.catalog = catalog;
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
                            "%s이가 부화 중이에요".formatted(hatching.getName()));
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

    /** 정산 + 방문(그날 처음이면 함께한 날 +1). 조회든 행동이든 펫을 만지는 모든 길이 여기를 지난다. */
    private void touch(ZzalPet pet, Instant realNow) {
        if (!pet.isAlive()) {
            return;
        }
        Instant now = pet.now(realNow);
        pet.settle(now);
        pet.visit(now);
    }

    /** 행동 결과 — 펫의 새 상태와, 이번 행동으로 열린 2층 동작(seq). */
    public record Action(ZzalPet pet, List<Integer> justUnlocked) {
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

    @Transactional
    public Action care(Long userId, Long petId, CareAction action, Instant realNow) {
        ZzalPet pet = awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        return withUnlockDiff(pet, () -> doCare(pet, action, now));
    }

    private void doCare(ZzalPet pet, CareAction action, Instant now) {
        switch (action) {
            case FEED -> {
                if (pet.getFood() <= 0) {
                    throw new BusinessException(ErrorCode.ZZAL_NO_FOOD);
                }
                if (pet.getFullness() >= ZzalRules.GAUGE_MAX) {
                    throw new BusinessException(ErrorCode.ZZAL_CARE_NOT_NEEDED,
                            "%s이는 배가 불러요".formatted(pet.getName()));
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
                            "%s이는 아프지 않아요".formatted(pet.getName()));
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
    @Transactional
    public Action sleep(Long userId, Long petId, Instant realNow) {
        ZzalPet pet = awake(userId, petId, realNow);
        Instant now = pet.now(realNow);
        if (pet.sleepKindAvailable(now) == null) {
            throw new BusinessException(ErrorCode.ZZAL_NOT_SLEEP_TIME, "저녁 7시가 되면 재워 주세요");
        }
        return withUnlockDiff(pet, () -> pet.sleep(now));
    }

    /**
     * 깨운다. 밤잠은 07:00~10:00, 낮잠은 5분 뒤.
     *
     * ★ 먼저 정산한다 — 10:00 이 지났으면 정산 중에 저절로 깨어 있어 {@code ZZAL_PET_NOT_SLEEPING} 이 된다.
     *   그게 맞다. "깨웠다" 는 보상(친밀도 +10)은 창 안에서 사용자가 눌렀을 때만.
     */
    @Transactional
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
        return withUnlockDiff(pet, () -> pet.wake(now));
    }

    // ── 성격·배경·공유 (정본 6·10·15장) ───────────────────────────────────

    /** 성격·세계관. 언제든, 자는 중에도(정본 10장 "언제든 변경"). */
    @Transactional
    public Action choosePersonality(Long userId, Long petId, Personality personality, String world, Instant realNow) {
        ZzalPet pet = alive(userId, petId, realNow);
        return withUnlockDiff(pet, () -> pet.choosePersonality(personality, world));
    }

    /** 배경 바꾸기 — 2층 4종이 열린 뒤(정본 6장). 값은 검증하지 않는다(해석 6). */
    @Transactional
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
     * 다운로드·공유 기록. 대상 = 지금 열린 동작 어느 것이든(16장). 심화 행동(OPEN)은 PR-7 에서 여기에 더한다.
     * 모르는 key 도 "안 열린 동작" 으로 답한다 — 카탈로그 밖 이름을 구분해 주면 key 목록을 훑는 수단이 된다.
     */
    @Transactional
    public Action share(Long userId, Long petId, String motionKey, Instant realNow) {
        ZzalPet pet = alive(userId, petId, realNow);
        boolean open = catalog.byKey(motionKey)
                .map(spec -> UnlockRules.isUnlocked(pet, spec, catalog))
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
                    "%s이가 아직 부화 중이에요".formatted(pet.getName()));
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
                    "%s이가 자고 있어요".formatted(pet.getName()));
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
