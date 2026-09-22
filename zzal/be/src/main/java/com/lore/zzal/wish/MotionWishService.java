package com.lore.zzal.wish;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.pet.PetService;
import com.lore.zzal.pet.ZzalRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        petService.get(userId, petId);

        long today = wishRepository.countByPetIdAndCreatedAtGreaterThanEqual(petId, startOfDay(now));
        if (today >= ZzalRules.MOTION_WISH_DAILY_LIMIT) {
            log.info("동작 요청 하루 상한 — userId={} petId={} 오늘 {}줄", userId, petId, today);
            throw new BusinessException(ErrorCode.ZZAL_MOTION_WISH_DAILY_LIMIT);
        }

        return wishRepository.save(ZzalMotionWish.of(userId, petId, text.trim(), now));
    }

    /**
     * 한국 시각 자정.
     *
     * ★ 다른 하루 상한(부화·놀이)과 <b>같은 경계</b>여야 한다. 경계가 갈리면 사용자에게는
     *   "어떤 것은 자정에, 어떤 것은 아홉 시에 풀린다" 로 보인다.
     */
    private static Instant startOfDay(Instant now) {
        return now.atZone(ZzalRules.ZONE).toLocalDate().atStartOfDay(ZzalRules.ZONE).toInstant();
    }
}
