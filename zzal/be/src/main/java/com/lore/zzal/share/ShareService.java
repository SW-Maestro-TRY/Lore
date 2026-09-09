package com.lore.zzal.share;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.motion.MotionCatalog;
import com.lore.zzal.motion.MotionSpec;
import com.lore.zzal.pet.ZzalPetRepository;
import com.lore.zzal.pet.ZzalPet;
import com.lore.zzal.share.dto.ShareResponses;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShareService {

    private final ZzalShareRepository shareRepository;
    private final ZzalPetRepository petRepository;
    private final MotionCatalog catalog;
    private final String baseUrl;

    public ShareService(ZzalShareRepository shareRepository, ZzalPetRepository petRepository,
                        MotionCatalog catalog,
                        @Value("${app.zzal.share.base-url}") String baseUrl) {
        this.shareRepository = shareRepository;
        this.petRepository = petRepository;
        this.catalog = catalog;
        this.baseUrl = baseUrl;
    }

    /**
     * 링크를 낸다. 이미 있으면 그것을 그대로 준다.
     *
     * ★ 열린 동작인지는 부르는 쪽({@code PetService.share})이 이미 봤다. 여기서 또 보면
     *   같은 규칙이 두 곳에 생겨 한쪽만 고쳐지는 날이 온다.
     */
    @Transactional
    public ShareResponses.Issued issue(Long petId, String motionKey, Instant now) {
        ZzalShare share = shareRepository.findByPetIdAndMotionKey(petId, motionKey)
                .orElseGet(() -> shareRepository.save(ZzalShare.issue(petId, motionKey, now)));
        return new ShareResponses.Issued(share.getToken(), url(share.getToken()));
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
        String imageKey = "images/zzal/pets/%d/basic/%s.webp".formatted(pet.getId(), spec.key());
        return new ShareResponses.Public(pet.getName(), spec.label(), imageKey, share.getCreatedAt());
    }

    private String url(String token) {
        return baseUrl.endsWith("/") ? baseUrl + token : baseUrl + "/" + token;
    }
}
