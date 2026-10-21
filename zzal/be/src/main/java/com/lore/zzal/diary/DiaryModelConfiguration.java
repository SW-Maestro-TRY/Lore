package com.lore.zzal.diary;
import com.lore.zzal.chat.line.OpenAiChatLineClient;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
@Configuration
@ConditionalOnProperty(name="zzal.diary.enabled",havingValue="true")
public class DiaryModelConfiguration {
    /** Preserve existing direct callers; no output cap unless explicitly configured. */
    public DiaryModel diaryModel(String apiKey,String model,String effort,long timeoutMs){
        return diaryModel(apiKey,model,effort,timeoutMs,null);
    }
    @Bean @ConditionalOnMissingBean(DiaryModel.class)
    public DiaryModel diaryModel(@Value("${app.zzal.openai.api-key:}")String apiKey,
            @Value("${app.zzal.chat.model:gpt-5.6-luna}")String model,
            @Value("${app.zzal.chat.reasoning-effort:none}")String effort,
            @Value("${zzal.diary.timeout-ms:15000}")long timeoutMs,
            @Value("${zzal.diary.max-completion-tokens:}")Integer maxCompletionTokens){
        if(apiKey==null||apiKey.isBlank())throw new IllegalStateException("Diary explicitly enabled but existing ZZAL_OPENAI_API_KEY is missing");
        return new ChatLineDiaryModel(new OpenAiChatLineClient(apiKey,effort,maxCompletionTokens),model,Duration.ofMillis(timeoutMs));
    }
}
