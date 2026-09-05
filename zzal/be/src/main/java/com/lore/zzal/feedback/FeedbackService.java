package com.lore.zzal.feedback;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.feedback.dto.FeedbackRequests.Tag;
import com.lore.zzal.game.RewardService;
import com.lore.zzal.pet.PetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 후기 — 한 사람이 한 펫에 <b>한 번</b>.
 *
 * <h3>★ 소유권은 펫 API 와 같은 자리에서 판정한다</h3>
 * {@link PetService#get} 을 그대로 부른다(도감의 {@code MotionController} 와 같은 방식).
 * 여기서 자체 검사를 새로 짜면 한쪽만 고쳐질 수 있고, 그 순간 남의 펫에 후기를 심을 수 있게 된다.
 * 남의 펫이면 403 이 아니라 <b>404</b> 다 — 403 은 "그 번호의 펫이 존재한다" 를 알려주는 셈이라
 * 번호를 훑어 남이 몇 마리 키우는지 셀 수 있게 된다.
 *
 * <h3>★ 보상은 여기서 계산하지 않는다</h3>
 * {@link RewardService#forFeedback} 만 부른다. 지금 설정이 {@code NONE} 이라 아무 일도
 * 일어나지 않지만, 무엇을 줄지 정해지면 <b>설정값만 바꿔</b> 미니게임과 함께 붙는다.
 * 여기서 직접 밥이나 행복을 올리면 그때 고칠 곳이 두 군데가 된다.
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private final ZzalFeedbackRepository feedbackRepository;
    private final PetService petService;
    private final RewardService rewardService;

    public FeedbackService(ZzalFeedbackRepository feedbackRepository,
                           PetService petService,
                           RewardService rewardService) {
        this.feedbackRepository = feedbackRepository;
        this.petService = petService;
        this.rewardService = rewardService;
    }

    /**
     * 후기를 남긴다.
     *
     * <h3>★★ 중복을 두 겹으로 막는다</h3>
     * <ol>
     *   <li><b>미리 조회</b> — 흔한 경우(이미 냈는데 또 눌렀다)를 여기서 걸러 제대로 된 말을 돌려준다</li>
     *   <li><b>유니크 제약</b> — 버튼을 빠르게 두 번 누르면 두 요청이 <b>동시에</b> 1번을 통과한다.
     *       그때 실제로 막는 것은 {@code uk_feedback_user_pet} 뿐이다</li>
     * </ol>
     * 1번만 두면 새로고침 연타로 여러 줄이 들어가고, 2번만 두면 흔한 경우에도
     * {@link DataIntegrityViolationException} 이 그대로 올라가 <b>500</b> 이 된다
     * (GlobalExceptionHandler 에 이 예외를 받는 자리가 없어 "그 밖의 모든 예외" 로 떨어진다).
     * 지금은 보상이 없어 중복이 티가 안 나지만, 켜는 순간 <b>같은 후기로 여러 번 받는다.</b>
     *
     * <h3>★ saveAndFlush 인 이유</h3>
     * 그냥 {@code save} 면 실제 INSERT 가 <b>이 메서드가 끝난 뒤</b> 커밋 시점에 나간다.
     * 그러면 제약 위반이 아래 try 밖에서 터져 못 잡고, 잡으려고 만든 catch 가 있으나 마나가 된다.
     * 지금 자리에서 내보내야 지금 자리에서 잡을 수 있다.
     */
    @Transactional
    public ZzalFeedback submit(Long userId, Long petId, int rating, List<Tag> tags, String text, Instant now) {
        // 소유권 판정이 먼저다. 통과하지 못하면 아무것도 읽지도 쓰지도 않는다.
        petService.get(userId, petId);

        feedbackRepository.findByUserIdAndPetId(userId, petId).ifPresent(already -> {
            throw new BusinessException(ErrorCode.ZZAL_FEEDBACK_ALREADY_SUBMITTED);
        });

        ZzalFeedback saved;
        try {
            saved = feedbackRepository.saveAndFlush(
                    ZzalFeedback.of(userId, petId, rating, joinTags(tags), blankToNull(text), now));
        } catch (DataIntegrityViolationException e) {
            // 동시에 두 번 들어온 경우. 사용자 입장에서는 "이미 냈다" 가 맞는 말이다.
            log.info("후기 중복 제출 — userId={} petId={}", userId, petId);
            throw new BusinessException(ErrorCode.ZZAL_FEEDBACK_ALREADY_SUBMITTED);
        }

        // 지금은 NONE 이라 아무 일도 일어나지 않는다. 정해지면 설정값만 바꾸면 붙는다.
        rewardService.forFeedback(petId, now);
        return saved;
    }

    /**
     * 이 펫에 이미 남긴 후기. 없으면 비어 있다.
     *
     * <h3>★ 왜 펫 상태 응답에 얹지 않았나</h3>
     * 펫 상태({@code PetResponses.Detail})는 부화 중 3초마다 폴링하는 응답이다. 거기에
     * "후기를 냈는가" 를 얹으면 알을 품는 내내 같은 값이 실려 나가고, 정작 그 판정이 필요한
     * 순간은 <b>첫 동작을 얻은 뒤 한 번</b>뿐이다. 도감을 따로 둔 것과 같은 이유다.
     */
    @Transactional(readOnly = true)
    public Optional<ZzalFeedback> find(Long userId, Long petId) {
        petService.get(userId, petId);
        return feedbackRepository.findByUserIdAndPetId(userId, petId);
    }

    // ── 안쪽 ──────────────────────────────────────────────────────────────

    /**
     * 칩들을 한 칸에 쉼표로 넣는다.
     *
     * ★ 같은 값이 두 번 와도 한 번만 담는다(LinkedHashSet). 화면이 토글을 잘못 다루면 실제로
     *   그런 목록이 오고, 그대로 저장하면 <b>세는 순간 그 사람 하나가 두 명처럼 잡힌다.</b>
     *   순서는 사용자가 고른 순서 그대로 둔다 — 나중에 "무엇을 먼저 골랐나" 를 볼 여지를 남긴다.
     */
    private static String joinTags(List<Tag> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        return new LinkedHashSet<>(tags).stream().map(Enum::name).collect(Collectors.joining(","));
    }

    /**
     * 공백뿐인 글은 안 쓴 것으로 본다.
     *
     * ★ 안 그러면 {@code ""} 과 {@code null} 과 {@code "   "} 이 뒤섞여 저장되고, "글을 남긴
     *   사람이 몇 명인가" 를 셀 때마다 세는 조건이 달라진다.
     */
    private static String blankToNull(String text) {
        return (text == null || text.isBlank()) ? null : text.trim();
    }
}
