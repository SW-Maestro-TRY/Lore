package com.lore.zzal.returning;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.pet.*;
import com.lore.zzal.pet.persona.PersonaSourceVersion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Objects;

@Service
public class ReturningGuideService {
    public static final String VERSION = "20261023-required-v2";
    private final ReturningGuideRepository guides;
    private final ZzalPetRepository pets;
    public ReturningGuideService(ReturningGuideRepository guides, ZzalPetRepository pets) {
        this.guides = guides; this.pets = pets;
    }
    /** Eligibility uses existing completion, never changes it or reinterprets old ordinals. */
    public static boolean eligible(ZzalPet pet) {
        if (!pet.isAlive() || pet.getHatchedAt() == null || pet.isInTutorial()) return false;
        if (pet.getFirstIntroStep() == null) return pet.getClockStartedAt() != null;
        return pet.getFirstIntroVersion() < 4 && pet.introAt(FirstIntro.Step.COMPLETE);
    }
    private ZzalPet owned(Long owner, Long petId, boolean lock) {
        if (owner == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return (lock ? pets.findByIdForUpdate(petId) : pets.findById(petId))
                .filter(p -> p.isOwnedBy(owner)).orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
    }
    private ReturningGuideController.State state(ZzalPet pet, ReturningGuideRepository.Stored stored) {
        return new ReturningGuideController.State(VERSION, eligible(pet), stored == null ? 0 : stored.step(),
                stored != null && stored.completedAt() != null, stored == null ? null : stored.petId());
    }
    @Transactional(readOnly=true)
    public ReturningGuideController.State get(Long owner, Long petId) {
        var pet = owned(owner, petId, false);
        return state(pet, guides.find(owner, VERSION, false).orElse(null));
    }
    @Transactional
    public ReturningGuideController.State advance(Long owner, Long petId, ReturningGuideController.Advance request, Instant now) {
        var pet = owned(owner, petId, true);
        if (!eligible(pet)) throw new BusinessException(ErrorCode.INVALID_INPUT);
        if (request.step() == null || request.step() < 0 || request.step() > 5)
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        guides.start(owner, VERSION, petId, now);
        var stored = guides.find(owner, VERSION, true).orElseThrow();
        // Retry after a lost completion response must not require a now-stale persona token.
        if (stored.completedAt() != null) return state(pet, stored);
        boolean complete = Boolean.TRUE.equals(request.complete());
        if (complete) {
            if (request.step() != 5 || stored.step() != 5 || request.expectedPersonaHash() == null || request.expectedPersonaHash().isBlank())
                throw new BusinessException(ErrorCode.INVALID_INPUT);
            if (!Objects.equals(request.expectedPersonaHash(), PersonaSourceVersion.forEditing(pet)))
                throw new BusinessException(ErrorCode.ZZAL_PERSONA_SOURCE_STALE);
        }
        // petId is advisory. Switching to another eligible owned pet must not trap the account.
        guides.advance(owner, VERSION, petId, request.step(), complete, now);
        return state(pet, guides.find(owner, VERSION, false).orElseThrow());
    }
}
