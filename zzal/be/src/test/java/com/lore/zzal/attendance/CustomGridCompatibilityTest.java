package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class CustomGridCompatibilityTest {
    @Test void blankIdentityKeepsFrozenTemplateAndRealSheetWithoutForcingAnalysis() throws Exception {
        var original=new RealCustomProviderTest().request();var json=new ObjectMapper();
        var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original.frozenSnapshot());
        snapshot.put("identity","");String bytes=json.writeValueAsString(snapshot);
        var request=CustomGenerationRequest.from(91,bytes,original.frozenSourcePlan());
        String hash=CustomGridPrompt.templateHash();var render=CustomGridPrompt.render(request);
        assertThat(render.prompt()).contains("Cells 1-2:","Cells 15-16:").doesNotContain("{IDENT}");
        assertThat(CustomPostprocessProfile.from(snapshot,"v1").sheetImageKey()).isEqualTo("images/sheet.png");
        assertThat(request.frozenSnapshot()).isEqualTo(bytes);assertThat(CustomGridPrompt.templateHash()).isEqualTo(hash);
    }
    @Test void placeholderAndTamperedFrozenInputsFailBeforeAuthor() throws Exception {
        var original=new RealCustomProviderTest().request();var json=new ObjectMapper();
        var calls=new AtomicInteger();
        for(String field:java.util.List.of("sheetImageKey","customGridTemplateHash","anchorsKey")){
            var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(original.frozenSnapshot());
            snapshot.put("identity","");snapshot.put(field,field.equals("sheetImageKey")?"images/zzal/demo/idle.webp":"");
            assertThatThrownBy(()->new CustomMotionPlanner().plan(json.writeValueAsString(snapshot),Set.of(),ctx->{calls.incrementAndGet();return original.frozenPlan();})).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(calls).hasValue(0);
    }
}
