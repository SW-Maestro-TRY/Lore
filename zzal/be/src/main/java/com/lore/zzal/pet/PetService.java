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
        long alive = petRepository.countByUserIdAndPhaseNot(userId, PetPhase.FAILED);
        if (alive >= user.getPetSlots()) {
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
        if (pet.isComplete()) {
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
        if (pet.isComplete()) {
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
