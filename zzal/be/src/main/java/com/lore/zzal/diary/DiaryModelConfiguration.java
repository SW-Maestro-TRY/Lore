package com.lore.zzal.diary;
import com.lore.zzal.chat.line.OpenAiChatLineClient;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
@Configuration
@ConditionalOnProperty(name="zzal.diary.enabled",havingValue="true")
public class DiaryModelConfiguration {
    @Bean @ConditionalOnMissingBean(DiaryModel.class)
    public DiaryModel diaryModel(@Value("${app.zzal.openai.api-key:}")String apiKey,
            @Value("${app.zzal.chat.model:gpt-5-mini}")String model,
            @Value("${app.zzal.chat.reasoning-effort:minimal}")String effort,
            @Value("${zzal.diary.timeout-ms:15000}")long timeoutMs){
        if(apiKey==null||apiKey.isBlank())throw new IllegalStateException("Diary explicitly enabled but existing ZZAL_OPENAI_API_KEY is missing");
        return new ChatLineDiaryModel(new OpenAiChatLineClient(apiKey,effort),model,Duration.ofMillis(timeoutMs));
    }
}
