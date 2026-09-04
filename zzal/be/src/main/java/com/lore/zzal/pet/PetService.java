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
import com.lore.zzal.generation.StepLabels;
import com.lore.zzal.generation.HatchService;
import com.lore.zzal.generation.PetHatchRequested;
import com.lore.zzal.generation.PipelineRegistry;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionOutcome;
import com.lore.zzal.motion.MotionStatus;
import com.lore.zzal.motion.MotionStartRequested;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 펫 생성·조회. */
@Service
public class PetService {

    private final ZzalPetRepository petRepository;
    private final GenJobRepository jobRepository;
    private final GenStepRecordRepository stepRepository;
    private final StepLabels labels;
    private final UserRepository userRepository;
    private final S3Service s3Service;
    private final HatchService hatchService;
    private final ZzalMotionRepository motionRepository;
    private final MotionCatalog catalog;
    private final PipelineRegistry registry;
    private final ApplicationEventPublisher events;

    public PetService(ZzalPetRepository petRepository,
                      GenJobRepository jobRepository,
                      GenStepRecordRepository stepRepository,
                      StepLabels labels,
                      UserRepository userRepository,
                      S3Service s3Service,
                      HatchService hatchService,
                      ZzalMotionRepository motionRepository,
                      MotionCatalog catalog,
                      PipelineRegistry registry,
                      ApplicationEventPublisher events) {
        this.petRepository = petRepository;
        this.jobRepository = jobRepository;
        this.stepRepository = stepRepository;
        this.labels = labels;
        this.userRepository = userRepository;
        this.s3Service = s3Service;
        this.hatchService = hatchService;
        this.motionRepository = motionRepository;
        this.catalog = catalog;
        this.registry = registry;
        this.events = events;
    }

    /**
     * 펫을 만들고 부화를 시작한다.
     *
     * ★ 기다리지 않고 즉시 돌아온다. 생성은 2분 넘게 걸리는데, 그걸 HTTP 응답으로 붙들고 있으면
     *   브라우저와 ALB 가 먼저 연결을 끊어 **다 만들어 놓고도 사용자는 실패로 본다.**
     *   여기서는 표에 기록만 하고, 실제 굽기는 커밋 후 다른 스레드에서 시작된다.
     *
     * 검사 순서에 이유가 있다 — 싼 검사부터 한다. 부화 중인지(조회 1회) → 자리가 있는지 →
     * 이미지 키가 유효한지(가장 비싸고, 통과하면 키를 소모한다).
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
        // ★ 자리를 차지하는 것은 HATCHING·ALIVE 뿐이다. 떠난 아이(DEAD)와 태어나지 못한
        //   알(FAILED)의 행은 남겨 두되 자리는 비워 준다 — 안 그러면 펫을 보내고도
        //   "자리 없음" 으로 막혀 다른 그림으로 다시 시작할 방법이 사라진다.
        long occupied = petRepository.countByUserIdAndPhaseIn(userId, PetPhase.OCCUPYING_SLOT);
        if (occupied >= user.getPetSlots()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_LIMIT_REACHED);
        }

        // 남의 키·가짜 키·이미 쓴 키를 여기서 막는다. 통과하면 그 키는 소모된 것으로 표시된다.
        s3Service.consume(userId, imageKey, now);

        ZzalPet pet = petRepository.save(ZzalPet.hatch(userId, name, note, imageKey, now));

        // 어느 파이프라인 버전으로 구울지 여기서 정해 기록에 남긴다. 나중에 결과가
        // 이상할 때 "어떤 조합으로 만들어졌나" 를 답할 수 있어야 한다.
        String version = hatchService.currentVersion();
        GenJob job = jobRepository.save(GenJob.start(pet.getId(), GenKind.HATCH, 1, version, now));

        // 저장이 확정된 뒤에 부화가 시작되도록 알림만 띄운다(PetHatchListener 참고).
        events.publishEvent(new PetHatchRequested(job.getId(), pet.getId(), version));
        return pet;
    }

    /**
     * 내 펫 하나.
     *
     * ★ 남의 펫이면 403 이 아니라 404 를 준다. 403 은 "그 번호의 펫이 존재한다" 는 사실을
     *   알려주는 셈이라, 번호를 1부터 훑으면 남이 몇 마리 키우는지 셀 수 있게 된다.
     */
    @Transactional(readOnly = true)
    public ZzalPet get(Long userId, Long petId) {
        return findMine(userId, petId);
    }

    /**
     * 내 펫을 꺼낸다. 트랜잭션을 열지 않는다.
     *
     * ★ 아래 돌봄 메서드들이 {@link #get} 을 부르지 않고 이것을 부르는 이유 —
     *   같은 클래스 안에서 자기 메서드를 부르면 프록시를 안 거쳐 {@code @Transactional} 이
     *   통째로 무시된다. get 의 readOnly 가 안 먹는 것에 <b>기대어</b> 저장이 되는 코드는,
     *   나중에 누가 get 을 별도 빈으로 옮기는 순간 조용히 저장이 멈춘다.
     *   (2026-09-02 에 이 함정으로 "부화 완료" 로그가 찍히는데 DB 는 QUEUED 인 일이 있었다)
     */
    private ZzalPet findMine(Long userId, Long petId) {
        return petRepository.findById(petId)
                .filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<ZzalPet> list(Long userId) {
        return petRepository.findByUserIdOrderByIdDesc(userId);
    }

    // ── 돌보기 (#133) ─────────────────────────────────────────────────────

    /**
     * 조회하면서 흐른 시간을 반영한다.
     *
     * ★ 읽기 전용이 아닌 이유 — 조회용 계산과 행동용 반영을 따로 두면 언젠가 두 식이
     *   어긋나고, 그때는 화면이 말하는 값과 서버가 판정하는 값이 달라진다. 한 벌로 둔다.
     */
    @Transactional
    public ZzalPet refresh(Long userId, Long petId, Instant now) {
        ZzalPet pet = findMine(userId, petId);
        pet.applyElapsed(now);
        return pet;
    }

    @Transactional
    public List<ZzalPet> refreshAll(Long userId, Instant now) {
        List<ZzalPet> pets = petRepository.findByUserIdOrderByIdDesc(userId);
        pets.forEach(p -> p.applyElapsed(now));
        return pets;
    }

    /**
     * 다 모으면 몇 개인가. <b>이 판정의 정본은 설정(app.zzal.motions)뿐이다.</b>
     *
     * 화면이 쓰는 총 개수와 서버가 쓰는 완주 판정이 같은 곳에서 나와야 한다. 예전에는
     * {@code ZzalRules.TOTAL_MOTIONS = 13} 이 따로 있어서, 목록에 2개만 넣어도 완주가
     * 13개를 요구했다 — 예외도 로그도 없이 "다 모았다" 만 영영 안 뜨는 종류의 버그다.
     */
    public int totalMotions() {
        return catalog.total();
    }

    /**
     * 첫날 순서(튜토리얼)를 끝냈다고 알린다. <b>이 순간부터 수치가 흐르기 시작한다.</b>
     *
     * ★ 이미 끝난 상태면 에러 대신 지금 상태를 그대로 돌려준다 — 마지막 칸에서 두 번 눌렀거나
     *   새로고침 뒤 다시 알리는 것은 정상적인 화면 동작이다. 여기서 409 를 던지면 화면은
     *   "실패" 를 띄우는데 서버 상태는 이미 원하던 그대로라, 사용자가 무엇을 더 해야 하는지 알 수 없다.
     *   (도메인의 {@code completeTutorial} 도 두 번째 호출에 아무 일도 하지 않아 앵커가 안 밀린다)
     */
    @Transactional
    public ZzalPet completeTutorial(Long userId, Long petId, Instant now) {
        ZzalPet pet = findMine(userId, petId);
        if (!pet.isAlive()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_ALIVE);
        }
        pet.completeTutorial(now);
        pet.applyElapsed(now);
        return pet;
    }

    /**
     * 시연·확인용으로 그 펫의 시계를 {@code by} 만큼 당긴다(= 그만큼 시간이 흐른 것으로 만든다).
     *
     * ★★ 이 메서드를 부를 수 있는 입구는 {@code app.zzal.dev-tools} 가 켜졌을 때만 뜨는
     *    {@code DevClockController} 하나뿐이다(꺼져 있으면 그 컨트롤러가 빈으로 올라오지 않는다).
     *    주소에 {@code /dev/} 가 들어가는 것은 방어가 아니라 이름일 뿐이다.
     *
     * ★ 남의 펫은 못 건드린다 — 돌봄 API 와 <b>같은</b> {@link #findMine} 을 탄다.
     *   판정을 따로 쓰면 한쪽만 고쳐질 수 있고, 그러면 개발 도구가 남의 데이터를 여는 구멍이 된다.
     */
    @Transactional
    public ZzalPet advanceClock(Long userId, Long petId, Duration by, Instant now) {
        ZzalPet pet = findMine(userId, petId);
        pet.rewindClock(by);
        // 앵커만 밀고 수치는 평소 경로로 센다 — 그래야 확인하려는 계산이 실제로 돈다.
        pet.applyElapsed(now);
        return pet;
    }

    /**
     * 돌본다. 밥·쓰다듬·청소.
     *
     * ★ 서버는 "무엇을 눌렀다" 만 받고 결과는 서버가 정한다. 브라우저가 보낸 수치를 그대로
     *   믿으면 개발자도구로 게이지를 채울 수 있다.
     */
    @Transactional
    public ZzalPet care(Long userId, Long petId, CareAction action, Instant now) {
        ZzalPet pet = awake(userId, petId, now);
        switch (action) {
            case FEED -> {
                if (pet.getFood() <= 0) {
                    throw new BusinessException(ErrorCode.ZZAL_NO_FOOD);
                }
                if (pet.getFullness() >= ZzalRules.MAX_GAUGE) {
                    throw new BusinessException(ErrorCode.ZZAL_CARE_NOT_NEEDED,
                            "%s이는 배가 불러요".formatted(pet.getName()));
                }
                pet.feed(now);
            }
            case PET -> {
                if (pet.getHappiness() >= ZzalRules.MAX_GAUGE) {
                    throw new BusinessException(ErrorCode.ZZAL_CARE_NOT_NEEDED,
                            "%s이는 지금 아주 기분이 좋아요".formatted(pet.getName()));
                }
                pet.pet(now);
            }
            case CLEAN -> {
                if (pet.getTrash() <= 0) {
                    throw new BusinessException(ErrorCode.ZZAL_CARE_NOT_NEEDED, "바닥이 이미 깨끗해요");
                }
                pet.clean(now);
            }
        }
        return pet;
    }

    /**
     * 훈련을 시작한다. 즉시 쌓이지 않고 시간이 걸린다 — 도는 동안 밥·쓰다듬·청소는 계속 된다.
     */
    @Transactional
    public ZzalPet train(Long userId, Long petId, Instant now) {
        ZzalPet pet = awake(userId, petId, now);
        if (pet.isComplete(catalog.total())) {
            throw new BusinessException(ErrorCode.ZZAL_ALL_UNLOCKED);
        }
        if (pet.isTraining()) {
            throw new BusinessException(ErrorCode.ZZAL_TRAIN_IN_PROGRESS);
        }
        // 값을 다 치렀는데 또 훈련하면 그만큼이 버려진다. 재우라고 말해 준다.
        if (pet.isTrainPaid()) {
            throw new BusinessException(ErrorCode.ZZAL_TRAIN_ENOUGH,
                    "%s이를 재우면 새로운 걸 배워요".formatted(pet.getName()));
        }
        pet.startTrain(now);
        return pet;
    }

    /** 재운다. 훈련 값을 다 치렀을 때만. */
    @Transactional
    public ZzalPet sleep(Long userId, Long petId, Instant now) {
        ZzalPet pet = awake(userId, petId, now);
        if (pet.isComplete(catalog.total())) {
            throw new BusinessException(ErrorCode.ZZAL_ALL_UNLOCKED);
        }
        if (pet.isTraining()) {
            throw new BusinessException(ErrorCode.ZZAL_TRAIN_IN_PROGRESS);
        }
        if (!pet.isTrainPaid()) {
            throw new BusinessException(ErrorCode.ZZAL_TRAIN_NOT_ENOUGH,
                    "연습이 %d번 더 필요해요".formatted(pet.trainPrice() - pet.getTrainStack()));
        }
        pet.goToSleep(now);

        // ★ 자는 동안 다음 동작을 굽는다. 이 6시간이 곧 생성·재시도·검수 시간이다.
        //   목록이 비어 있으면(아직 무엇을 열지 안 정했으면) 굽지 않고 잠만 잔다.
        String name = catalog.nameAt(pet.getUnlockedCount());
        if (name != null) {
            String version = registry.currentVersion(GenKind.MOTION);
            // 앞서 실패한 자리면 그 행을 다시 쓴다. 새로 만들면 (펫, 순서) 유니크에 걸리고,
            // 무엇보다 "이 자리에서 몇 번 실패했나" 라는 이력이 끊긴다.
            ZzalMotion existing = motionRepository
                    .findByPetIdAndSeq(pet.getId(), pet.getUnlockedCount())
                    .orElse(null);

            if (existing == null) {
                ZzalMotion created = motionRepository.save(ZzalMotion.start(
                        pet.getId(), pet.getUnlockedCount(), name, version));
                events.publishEvent(new MotionStartRequested(created.getId()));
            } else if (!existing.isOpen()) {
                // 앞서 실패했거나 굽다 만 자리다. 그 행을 다시 쓴다 — 새로 만들면
                // (펫, 순서) 유니크에 걸리고, "이 자리에서 몇 번 실패했나" 라는 이력도 끊긴다.
                existing.retry(version);
                events.publishEvent(new MotionStartRequested(existing.getId()));
            }
            // 이미 다 구워져 있으면(OPEN) 다시 굽지 않는다 — 굽는 데 성공했는데 사용자가
            // 깨우지 않고 다시 재운 경우다. 또 구우면 돈만 두 번 나간다.
        }
        return pet;
    }

    /**
     * 깨운다. 다 구워졌으면 하나를 배운다.
     *
     * ★★ <b>깨우기는 언제나 된다.</b> 다 못 구웠다고 막으면, 굽기가 실패했거나 서버가
     *    재시작돼 굽던 것이 사라진 사용자는 <b>영영 못 깨우고 갇힌다.</b>
     *    못 배운 것은 말로 알리고, 치른 연습은 그대로 둬서 다음에 다시 시도한다.
     */
    @Transactional
    public WakeResult wake(Long userId, Long petId, Instant now) {
        ZzalPet pet = findMine(userId, petId);
        if (!pet.isAlive()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_ALIVE);
        }
        if (!pet.isSleeping()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_SLEEPING);
        }
        if (!pet.canWake(now)) {
            throw new BusinessException(ErrorCode.ZZAL_PET_STILL_SLEEPING);
        }

        // 자는 동안 시간이 멈춰 있었으므로, 깨워서 앵커를 민 다음에 흐른 시간을 센다.
        pet.wakeUp(now);
        pet.applyElapsed(now);

        ZzalMotion motion = motionRepository
                .findByPetIdAndSeq(pet.getId(), pet.getUnlockedCount())
                .orElse(null);

        if (motion == null) {
            // 배울 것이 없었다(아직 무엇을 열지 안 정한 상태). 화면에 아무 말도 하지 않는다.
            return new WakeResult(pet, MotionOutcome.nothing());
        }
        if (motion.isOpen()) {
            pet.unlockOne();
            return new WakeResult(pet, MotionOutcome.learned(motion.getName()));
        }
        // 못 배웠으니 연습을 빼앗지 않는다. 다음에 다시 재우면 그 자리를 이어서 굽는다.
        return new WakeResult(pet, motion.getStatus() == MotionStatus.FAILED
                ? MotionOutcome.tooHard()
                : MotionOutcome.stillLearning());
    }

    /**
     * 보낸다(놓아주기). 자리가 비어 다른 그림으로 새로 시작할 수 있게 된다.
     *
     * <h3>왜 필요한가</h3>
     * 지금은 한 사람이 한 마리만 키운다. 되돌릴 방법이 없으면 처음 올린 그림이 마음에 안 들었을 때
     * <b>계정을 새로 파는 것 말고는 길이 없다.</b> 그게 첫 사용자가 가장 먼저 부딪히는 벽이다.
     *
     * <h3>★ 부화 중에는 막는다</h3>
     * 알을 보내 버리면 뒤에서 굽고 있는 생성 작업이 주인 없는 일이 된다 — 돈은 그대로 나가고
     * 결과를 받을 펫은 사라진다. 몇 분만 기다리면 되는 일이라 여기서는 거절하고 이유를 말해 준다.
     *
     * <h3>이미 떠난 아이면 조용히 넘어간다</h3>
     * 두 번 눌렀거나 다른 창에서 먼저 보낸 경우다. 에러를 던지면 화면은 "실패" 를 보여 주는데
     * 실제로는 원하던 상태(떠남)라, 사용자가 무엇을 더 해야 하는지 알 수 없게 된다.
     * 도메인의 {@code release} 가 ALIVE 가 아니면 아무 일도 하지 않으므로 상태도 안 망가진다.
     *
     * <h3>★★ 지우지 않는다</h3>
     * 만들어 둔 움짤·모션 기록은 그대로 둔다. 이미 돈을 쓴 결과물이고, 나중에 "떠난 아이와
     * 재회" 가 붙을 자리다. 단계만 DEAD 로 바뀌고 행은 남는다.
     */
    @Transactional
    public ZzalPet release(Long userId, Long petId, Instant now) {
        ZzalPet pet = findMine(userId, petId);
        if (pet.isHatching()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_RELEASE_NOT_ALLOWED,
                    "%s이가 아직 부화 중이에요".formatted(pet.getName()));
        }
        // 흐른 시간을 먼저 반영한다 — 응답이 다른 API 와 같은 모양이라, 여기서만
        // 계산이 빠지면 화면이 마지막으로 보는 수치가 옛날 값이 된다.
        pet.applyElapsed(now);
        pet.release(now);
        return pet;
    }

    /** 깨운 결과 — 펫의 새 상태와, 이번에 무엇을 배웠는지. */
    public record WakeResult(ZzalPet pet, MotionOutcome outcome) {
    }

    /**
     * 지금 뭔가를 할 수 있는 상태인지 확인하고, 흐른 시간을 반영해 돌려준다.
     * 돌봄·훈련·재우기가 공통으로 거치는 문이다.
     */
    private ZzalPet awake(Long userId, Long petId, Instant now) {
        ZzalPet pet = findMine(userId, petId);
        if (!pet.isAlive()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_NOT_ALIVE);
        }
        if (pet.isSleeping()) {
            throw new BusinessException(ErrorCode.ZZAL_PET_SLEEPING,
                    "%s이가 자고 있어요".formatted(pet.getName()));
        }
        pet.applyElapsed(now);
        return pet;
    }

    /**
     * 지금 하는 일을 사람 말로. 부화 중이 아니면 비어 있다.
     *
     * 단계 이름("grid")이 아니라 화면에 그대로 쓸 문구를 준다 — 프론트가 단계 이름을
     * 문구로 바꾸는 표를 따로 갖게 되면, 단계가 바뀔 때마다 양쪽을 고쳐야 한다.
     */
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
