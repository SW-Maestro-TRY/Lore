package com.lore.zzal.share;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.motion.ZzalMotion;
import com.lore.zzal.motion.ZzalMotionRepository;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.share.dto.ShareResponses;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShareService {

    private static final Logger log = LoggerFactory.getLogger(ShareService.class);

    private final ZzalShareRepository shareRepository;
    private final ZzalPetRepository petRepository;
    private final ZzalMotionRepository motionRepository;
    private final MotionCatalog catalog;
    private final String baseUrl;

    public ShareService(ZzalShareRepository shareRepository, ZzalPetRepository petRepository,
                        ZzalMotionRepository motionRepository,
                        MotionCatalog catalog,
                        @Value("${app.zzal.share.base-url}") String baseUrl) {
        this.shareRepository = shareRepository;
        this.petRepository = petRepository;
        this.motionRepository = motionRepository;
        this.catalog = catalog;
        this.baseUrl = baseUrl;
    }

    /**
     * 링크를 낸다. 이미 있으면 그것을 그대로 준다.
     *
     * ★ 열린 동작인지는 부르는 쪽({@code PetService.share})이 이미 봤다. 여기서 또 보면
     *   같은 규칙이 두 곳에 생겨 한쪽만 고쳐지는 날이 온다.
     *
     * <h3>★★ 방어가 두 겹인 이유 — 연타가 가장 흔한 버튼이다</h3>
     * 조회 하나만 두면 조회와 저장 사이에 두 번째 요청이 끼었을 때 둘 다 "없다" 를 통과하고,
     * 늦은 쪽이 {@code uk_zzal_shares_pet_motion}(ZzalShare:32)에 걸린다.
     * {@code DataIntegrityViolationException} 을 받는 자리가 {@code GlobalExceptionHandler} 에 없어
     * 그대로 <b>500</b> 이 나갔다. 공유 버튼은 SNS 로 넘어가기 직전이라 연타가 가장 흔하다.
     *
     * <h3>★ saveAndFlush 인 이유 — {@code FeedbackService}(80~87)와 같다</h3>
     * 그냥 {@code save} 면 실제 INSERT 가 이 메서드가 끝난 뒤 커밋 시점에 나간다. 그러면 제약 위반이
     * 아래 try 밖에서 터져 못 잡고, 잡으려고 만든 catch 가 있으나 마나가 된다.
     *
     * ★ 진 쪽은 이긴 쪽이 넣은 링크를 그대로 받는다 — (petId, motionKey) 한 쌍에 링크 하나이므로
     *   두 요청이 같은 토큰을 받는 것이 맞는 답이다. 누를 때마다 주소가 달라지면 무엇이 얼마나
     *   퍼졌는지 셀 수 없게 된다(ZzalShare 주석).
     */
    @Transactional
    public ShareResponses.Issued issue(Long petId, String motionKey, Instant now) {
        ZzalShare share = shareRepository.findByPetIdAndMotionKey(petId, motionKey)
                .orElseGet(() -> saveOrLoseTheRace(petId, motionKey, now));
        return new ShareResponses.Issued(share.getToken(), url(share.getToken()));
    }

    /** 넣어 보고, 같은 순간에 남이 먼저 넣었으면 그 줄을 가져온다. */
    private ZzalShare saveOrLoseTheRace(Long petId, String motionKey, Instant now) {
        try {
            return shareRepository.saveAndFlush(ZzalShare.issue(petId, motionKey, now));
        } catch (DataIntegrityViolationException e) {
            log.info("공유 링크 동시 발급 — 먼저 들어온 링크를 준다 (petId={} motionKey={})", petId, motionKey);
            return alreadyIssued(petId, motionKey);
        }
    }

    /**
     * 진 쪽이 이긴 쪽의 줄을 찾는다.
     *
     * ★ 이 조회마저 실패하면 <b>404 로 답한다</b> — 500 보다는 낫고, 화면은 다시 누르면 된다.
     *   (flush 가 터진 영속성 컨텍스트에서 다시 읽는 것은 JPA 가 보장하지 않는 자리다.
     *   확실한 멱등을 원하면 저장만 {@code REQUIRES_NEW} 로 떼어내야 하는데, 그건 설계 결정이라 여기서 하지 않는다.)
     */
    private ZzalShare alreadyIssued(Long petId, String motionKey) {
        try {
            return shareRepository.findByPetIdAndMotionKey(petId, motionKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_SHARE_NOT_FOUND));
        } catch (RuntimeException e) {
            if (e instanceof BusinessException be) {
                throw be;
            }
            log.warn("공유 링크 재조회 실패 — petId={} motionKey={}", petId, motionKey, e);
            throw new BusinessException(ErrorCode.ZZAL_SHARE_NOT_FOUND);
        }
    }

    /**
     * 링크를 연 사람에게 보여 줄 것.
     *
     * ★ 없는 토큰도 404 한 가지로만 답한다 — "있는데 못 본다"와 "없다"를 구분해 주면
     *   토큰을 찍어 보는 사람에게 단서가 된다.
     */
    @Transactional
    public ShareResponses.Public open(String token) {
        ZzalShare share = shareRepository.findByToken(token)
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_SHARE_NOT_FOUND));
        ZzalPet pet = petRepository.findById(share.getPetId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_SHARE_NOT_FOUND));
        share.viewed();

        MotionSpec spec = catalog.byKey(share.getMotionKey())
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_SHARE_NOT_FOUND));
        return new ShareResponses.Public(pet.getName(), spec.label(), imageKeyOf(pet, spec), share.getCreatedAt());
    }

    /**
     * 링크가 가리키는 그림 — <b>도착한 심화 행동이면 그 행이 들고 있는 실제 키</b>, 아니면 기본 행동 그림.
     *
     * <h3>★★ 왜 한 가지로 조립하면 안 되나</h3>
     * {@code PetService.share} 는 <b>도착한 심화 행동도 공유를 허락한다</b>({@code isRevealed}).
     * 그런데 여기서 {@code basic/{key}.webp} 한 가지로만 조립하면, 밤새 구워 아침에 받은
     * 가장 자랑하고 싶은 그림이 <b>남에게는 404</b> 로 뜬다. 심화의 실제 키는 그 모션 행에 있다.
     * 서버에도 화면에도 오류가 안 나서 아무도 모른다 — 확산이 유일한 성장 경로인데
     * 제일 좋은 재료에서 끊긴다.
     */
    private String imageKeyOf(ZzalPet pet, MotionSpec spec) {
        String advanced = motionRepository.findByPetIdAndSeq(pet.getId(), spec.seq())
                .map(ZzalMotion::advancedImageKey)
                .orElse(null);
        if (advanced != null && !advanced.isBlank()) {
            return advanced;
        }
        return "images/zzal/pets/%d/basic/%s.webp".formatted(pet.getId(), spec.key());
    }

    private String url(String token) {
        return baseUrl.endsWith("/") ? baseUrl + token : baseUrl + "/" + token;
    }
}
