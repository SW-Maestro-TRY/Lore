package com.lore.zzal.docs;
import com.lore.zzal.diary.DiaryResponses;
import com.lore.zzal.pet.persona.FirstMeeting;
import com.lore.zzal.chat.ChatService;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ZzalDtoSchemaNamesTest {
 @Test void resolvingNewDtosTogetherKeepsDiaryAndFirstMeetingAndChatShapesSeparate(){
  Map<String,Schema> schemas=new LinkedHashMap<>();
  for(Class<?> type:List.of(DiaryResponses.Page.class,FirstMeeting.View.class,FirstMeeting.Request.class,ChatService.View.class))schemas.putAll(ModelConverters.getInstance().readAll(type));
  assertThat(schemas).containsKeys("ZzalDiaryPage","ZzalDiaryEntry","ZzalFirstMeetingView","ZzalFirstMeetingRequest","ZzalChatView").doesNotContainKeys("Page","Entry","View","Request");
  assertThat(schemas.get("ZzalDiaryPage").getProperties()).containsKeys("entries","pending","unreadCount").doesNotContainKey("discoveryLine");
  assertThat(schemas.get("ZzalDiaryEntry").getProperties()).containsKeys("body","language","readAt").doesNotContainKey("calls");
  assertThat(schemas.get("ZzalFirstMeetingView").getProperties()).containsKeys("discoveryLine","greetingLine","addressStatus").doesNotContainKey("turns");
  assertThat(schemas.get("ZzalChatView").getProperties()).containsKeys("calls","session","turns").doesNotContainKey("greetingLine");
 }
}
