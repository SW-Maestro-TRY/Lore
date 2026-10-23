package com.lore.zzal.diary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
@Service
public class DiaryWorkerTransactions {
    private final DiaryEntryRepository entries;
    public DiaryWorkerTransactions(DiaryEntryRepository entries){this.entries=entries;}
    public record Claim(Long id,String token,String snapshot,String language){}
    @Transactional public Optional<Claim> claim(Long id,Instant now){
        var e=entries.locked(id).orElse(null);String token=UUID.randomUUID().toString();
        if(e==null||!e.claim(token,now))return Optional.empty();
        return Optional.of(new Claim(id,token,e.snapshot(),e.language()));
    }
    @Transactional public boolean finish(Claim c,DiaryModel.Result result,Instant now){
        return entries.locked(c.id()).map(e->e.complete(c.token(),result.body(),result.executionReceipt(),now)).orElse(false);
    }
    @Transactional public void uncertain(Claim c,String receipt){entries.locked(c.id()).ifPresent(e->e.uncertain(c.token(),receipt));}
    /** A dead process is never reclaimed into another paid call. The slot remains occupied. */
    @Transactional public void recoverUncertain(Instant before){
        entries.findByStatusAndClaimedAtBefore(DiaryEntry.Status.GENERATING,before)
                .forEach(e->e.uncertain(e.claimToken(),"Process outcome unknown; automatic retry forbidden"));
    }
}
