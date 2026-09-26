package com.lore.zzal.wish;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.pet.ZzalRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * "이런 동작도 보고 싶어요" — 한 번 부를 때마다 한 줄.
 *
 * <h3>★ 소유권은 펫 API 와 같은 자리에서 판정한다</h3>
 * {@link PetService#get} 을 그대로 부른다(후기·도감과 같은 방식). 여기서 자체 검사를 새로 짜면
 * 한쪽만 고쳐질 수 있고, 그 순간 남의 펫에 요청을 심을 수 있게 된다. 남의 펫이면 403 이 아니라
 * <b>404</b>({@code ZZAL_PET_NOT_FOUND}) 다 — 403 은 "그 번호의 펫이 존재한다" 를 알려주는 셈이라
 * 번호를 훑어 남이 몇 마리 키우는지 셀 수 있게 된다.
 *
 * <h3>★ 하루 상한은 사용자를 막으려는 것이 아니다</h3>
 * 막으려는 것은 <b>눌린 채 굴러가는 화면</b>이다. 그래서 상한이 넉넉하고(하루 20줄), 넘으면
 * 조용히 버리지 않고 {@link ErrorCode#ZZAL_MOTION_WISH_DAILY_LIMIT} 로 분명히 답한다 —
 * 남긴 줄 알았는데 안 남는 쪽이 사용자에게 훨씬 나쁘다.
 *
 * <h3>★★ "하루" 는 취침 기준이다 — 자정이 아니다 (2026-09-22 상훈님 결정)</h3>
 * 정본 16장은 <b>"하루의 경계 = 밤잠 드는 순간"</b> 이고 다른 상한(놀이 3판·간식·쓰다듬·목욕)이
 * 전부 그 경계를 쓴다. 이 상한만 한국 시각 자정이었다(연결 감사 J10) — 사용자에게는
 * "어떤 것은 자정에, 어떤 것은 기상에 풀린다" 로 보이고, 자정을 낀 늦은 밤에는 <b>한 번의
 * 깨어 있는 시간에 상한이 두 번 풀렸다.</b> 지금은 {@link ZzalPet#dayStartedAt()} 한 곳을 본다.
 */
@Service
public class MotionWishService {

    private static final Logger log = LoggerFactory.getLogger(MotionWishService.class);

    private final ZzalMotionWishRepository wishRepository;
    private final PetService petService;

    public MotionWishService(ZzalMotionWishRepository wishRepository, PetService petService) {
        this.wishRepository = wishRepository;
        this.petService = petService;
    }

    /**
     * 요청 한 줄을 남긴다.
     *
     * <h3>★ 앞뒤 공백을 떼고 저장한다</h3>
     * 안 그러면 {@code "구르기"} 와 {@code " 구르기 "} 가 서로 다른 글로 쌓여, 같은 말을 몇 명이
     * 했는지 셀 때마다 답이 달라진다. 공백뿐인 글은 {@code @NotBlank} 가 받는 자리에서 이미 막았다.
     *
     * <h3>★ 길이는 <b>받은 그대로</b>를 잰다(다듬기 전)</h3>
     * 검증이 먼저 돌고 여기서 다듬으므로, 앞뒤에 공백을 잔뜩 붙여 60자를 넘긴 글은 400 이다.
     * 상한을 다듬은 뒤로 옮기면 "몇 자까지 되나" 의 답이 보이지 않는 공백에 따라 달라진다.
     */
    @Transactional
    public ZzalMotionWish submit(Long userId, Long petId, String text, Instant now) {
        // 소유권 판정이 먼저다. 통과하지 못하면 아무것도 읽지도 쓰지도 않는다.
        ZzalPet pet = petService.get(userId, petId);

        long today = wishRepository.countByPetIdAndCreatedAtGreaterThanEqual(petId, dayStart(pet, now));
        if (today >= ZzalRules.MOTION_WISH_DAILY_LIMIT) {
            log.info("동작 요청 하루 상한 — userId={} petId={} 오늘 {}줄", userId, petId, today);
            throw new BusinessException(ErrorCode.ZZAL_MOTION_WISH_DAILY_LIMIT);
        }

        return wishRepository.save(ZzalMotionWish.of(userId, petId, text.trim(), now));
    }

    /**
     * 이 펫의 하루가 시작된 <b>실제</b> 시각 — 저장된 줄의 {@code createdAt} 과 견줄 수 있게.
     *
     * <h3>★★ 펫 시계와 실제 시계를 섞으면 상한이 조용히 사라진다</h3>
     * {@link ZzalPet#dayStartedAt()} 은 <b>펫 시계</b>의 시각이고 {@code zzal_motion_wish.created_at}
     * 은 <b>실제</b> 시각이다. dev 시계로 앞당긴 펫에서는 펫 시각이 실제보다 미래라, 그대로 견주면
     * 모든 줄이 경계 앞으로 떨어져 <b>세는 줄이 0 이 되고 상한이 없어진다.</b> 그래서 "하루가
     * 시작된 뒤로 흐른 시간" 만큼 실제 시각을 되돌린다.
     *
     * ★ 운영에서는 오프셋이 0 이라 {@code dayStartedAt()} 과 같은 값이다.
     */
    private static Instant dayStart(ZzalPet pet, Instant now) {
        return now.minus(Duration.between(pet.dayStartedAt(), pet.now(now)));
    }
}
