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
    private final ApplicationEventPublisher events;

    public PetService(ZzalPetRepository petRepository,
                      GenJobRepository jobRepository,
                      GenStepRecordRepository stepRepository,
                      StepLabels labels,
                      UserRepository userRepository,
                      S3Service s3Service,
                      HatchService hatchService,
                      ApplicationEventPublisher events) {
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
        return petRepository.findById(petId)
                .filter(p -> p.isOwnedBy(userId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public List<ZzalPet> list(Long userId) {
        return petRepository.findByUserIdOrderByIdDesc(userId);
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
