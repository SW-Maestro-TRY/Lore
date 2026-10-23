package com.lore.zzal.inquiry;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Objects;

@Service
public class InquiryService {
    private final InquiryRepository inquiries;
    private final ZzalPetRepository pets;
    public InquiryService(InquiryRepository inquiries, ZzalPetRepository pets) {
        this.inquiries = inquiries; this.pets = pets;
    }

    @Transactional
    public InquiryController.Receipt submit(Long owner, InquiryRequests.Submit request, Instant now) {
        if (owner == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (request.petId() != null) pets.findById(request.petId()).filter(pet -> pet.isOwnedBy(owner))
                .orElseThrow(() -> new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        inquiries.insertIfAbsent(owner, request, now);
        var saved = inquiries.find(owner, request.requestId()).orElseThrow();
        if (!Objects.equals(saved.petId(), request.petId()) || !saved.body().equals(request.body())
                || !saved.locale().equals(request.locale().name()))
            throw new BusinessException(ErrorCode.ZZAL_INQUIRY_CONFLICT);
        return new InquiryController.Receipt(saved.id(), saved.createdAt());
    }
}
