package com.lore.zzal.diary;
import java.time.Instant;
import java.util.List;
public final class DiaryResponses {
    private DiaryResponses(){}
    @io.swagger.v3.oas.annotations.media.Schema(name="ZzalDiaryEntry")
    public record Entry(Long id,Instant writtenAt,String body,String preview,String language,Instant readAt,String kind){
        public static Entry from(DiaryEntry e){return new Entry(e.id(),e.writtenAt(),e.body(),e.preview(),e.language(),e.readAt(),"CHARACTER_DIARY");}
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="ZzalDiaryPage")
    public record Page(List<Entry> entries,Long nextBeforeId,int unreadCount,boolean pending){}
}
