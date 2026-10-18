package com.lore.zzal.diary;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
class DiaryModelConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(DiaryModelConfiguration.class);
    @Test void providerIsAbsentByDefault(){runner.run(context->assertThat(context).doesNotHaveBean(DiaryModel.class));}
    @Test void explicitEnableConnectsExistingAdapterWithoutMakingARequest(){
        runner.withPropertyValues("zzal.diary.enabled=true","app.zzal.openai.api-key=not-a-real-key")
                .run(context->assertThat(context.getBean(DiaryModel.class)).isInstanceOf(ChatLineDiaryModel.class));
    }
    @Test void enabledButMissingKeyFailsClosed(){runner.withPropertyValues("zzal.diary.enabled=true")
            .run(context->assertThat(context).hasFailed());}
}
