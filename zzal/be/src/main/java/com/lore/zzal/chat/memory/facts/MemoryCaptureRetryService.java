package com.lore.zzal.chat.memory.facts;
import com.lore.common.exception.*;
import com.lore.zzal.pet.ZzalPetRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
@Service
public class MemoryCaptureRetryService {
    private final ZzalPetRepository pets;private final ZzalMemoryCaptureRepository jobs;
    private final ApplicationEventPublisher events;
    public MemoryCaptureRetryService(ZzalPetRepository pets,ZzalMemoryCaptureRepository jobs,ApplicationEventPublisher events){
        this.pets=pets;this.jobs=jobs;this.events=events;
    }
    /** Local validation/storage retry only: it does not regenerate dialogue or call an AI. */
    @Transactional
    public int retry(Long owner,Long petId){
        pets.findById(petId).filter(p->p.isOwnedBy(owner)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));
        var pending=jobs.findByPetIdAndStatusInOrderByIdAsc(petId,List.of("QUEUED","FAILED"),PageRequest.of(0,20));
        pending.forEach(j->events.publishEvent(new MemoryCaptureRequested(j.id())));return pending.size();
    }
}
