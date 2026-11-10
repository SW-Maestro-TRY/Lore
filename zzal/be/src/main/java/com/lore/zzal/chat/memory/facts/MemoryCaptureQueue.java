package com.lore.zzal.chat.memory.facts;
import com.lore.zzal.chat.session.ZzalChatTurn;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
@Service
public class MemoryCaptureQueue {
    public static final String VERSION="reply-deltas-v1";
    private final ZzalMemoryCaptureRepository captures;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    public MemoryCaptureQueue(ZzalMemoryCaptureRepository captures,ApplicationEventPublisher events,JsonMapper json){
        this.captures=captures;this.events=events;this.json=json;
    }
    public void queue(Long petId,ZzalChatTurn user,ZzalChatTurn pet,String raw,List<FactView> recalled,Instant now){
        if(raw==null||captures.findByUserTurnIdAndExtractorVersion(user.getId(),VERSION).isPresent())return;
        var job=captures.save(new ZzalMemoryCapture(petId,user.getId(),pet.getId(),VERSION,raw,
                json.writeValueAsString(recalled.stream().map(FactView::id).toList()),now));
        events.publishEvent(new MemoryCaptureRequested(job.id()));
    }
}
