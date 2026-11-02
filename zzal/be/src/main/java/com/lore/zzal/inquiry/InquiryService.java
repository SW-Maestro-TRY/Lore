package com.lore.zzal.inquiry;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
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
        // 더 물어보기는 본인 문의에만 이어 쓴다. 남의 번호는 없는 번호와 똑같이 404(있다는 사실도 안 알린다).
        if (request.parentId() != null && !inquiries.ownedBy(request.parentId(), owner))
            throw new BusinessException(ErrorCode.ZZAL_INQUIRY_NOT_FOUND);
        inquiries.insertIfAbsent(owner, request, now);
        var saved = inquiries.find(owner, request.requestId()).orElseThrow();
        if (!Objects.equals(saved.petId(), request.petId()) || !saved.body().equals(request.body())
                || !saved.locale().equals(request.locale().name()) || !Objects.equals(saved.parentId(), request.parentId()))
            throw new BusinessException(ErrorCode.ZZAL_INQUIRY_CONFLICT);
        return new InquiryController.Receipt(saved.id(), saved.createdAt());
    }

    @Transactional(readOnly = true)
    public List<InquiryController.Item> listMine(Long owner) {
        if (owner == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        return inquiries.listMine(owner).stream().map(InquiryController.Item::of).toList();
    }

    /** 답장을 봤다. 남의 문의·없는 문의는 404, 답장이 아직 없거나 이미 봤으면 그대로(멱등). */
    @Transactional
    public void markSeen(Long owner, long id, Instant now) {
        if (owner == null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        if (!inquiries.ownedBy(id, owner)) throw new BusinessException(ErrorCode.ZZAL_INQUIRY_NOT_FOUND);
        inquiries.markSeen(id, owner, now);
    }
}
