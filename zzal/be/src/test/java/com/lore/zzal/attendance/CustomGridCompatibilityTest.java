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
    @Test void seatedGroundingAliasesPreserveFrozenPlanAndImagePrompt() throws Exception {
        var fixture=new RealCustomProviderTest();var json=new ObjectMapper();
        var tree=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(fixture.grounded());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("motions").get(0)).put("pose","sitting: seated at a small wooden table, leaning slightly forward while drawing");
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("motions").get(2)).put("pose","seated: curled comfortably in a window seat with one knee slightly raised");
        var plan=json.treeToValue(tree,CustomMotionPlan.class);var request=fixture.request(plan);
        String frozen=request.frozenSourcePlan(),hash=request.sourcePlanHash();
        var render=CustomGridPrompt.render(request);
        assertThat(render.postures()).contains(CustomGridPrompt.SLOTS.get(0)+"=crouch",CustomGridPrompt.SLOTS.get(2)+"=crouch");
        assertThat(render.prompt()).contains("sitting: seated at a small wooden table", "seated: curled comfortably in a window seat");
        assertThat(request.frozenSourcePlan()).isEqualTo(frozen);
        assertThat(request.sourcePlanHash()).isEqualTo(hash).isEqualTo(AttendanceService.hash(frozen));
        assertThat(json.readTree(frozen).path("motionPlan")).isEqualTo(json.readTree(tree.toString()));
    }
    @Test void unsupportedGroundingIsRejectedByPlannerBeforeReservation() throws Exception {
        var fixture=new RealCustomProviderTest();var json=new ObjectMapper();var request=fixture.request();
        var tree=json.valueToTree(fixture.grounded());
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("motions").get(0)).put("pose","floating: above the floor");
        var plan=json.treeToValue(tree,CustomMotionPlan.class);
        assertThatThrownBy(()->new CustomMotionPlanner().plan(request.frozenSnapshot(),Set.of(),ctx->plan))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Explicit postprocess grounding required before generation");
    }
    @Test void groundingAliasesDoNotChangeStandingOrLyingClassification(){
        assertThat(CustomGridPrompt.grounding("standing: holding a cup")).isEqualTo("standing");
        assertThat(CustomGridPrompt.grounding("lying: on a daybed")).isEqualTo("lying");
        assertThat(CustomGridPrompt.grounding("crouch: kneeling")).isEqualTo("crouch");
        assertThatThrownBy(()->CustomGridPrompt.grounding("sitting upright without explicit separator"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidFrozenGroundingStopsBeforeWorkerClaimOrProvider() throws Exception {
        var fixture=new RealCustomProviderTest();var original=fixture.request();var json=new ObjectMapper();
        var source=json.readTree(original.frozenSourcePlan());
        ((com.fasterxml.jackson.databind.node.ObjectNode)source.path("motionPlan").path("motions").get(0)).put("pose","floating: unsupported");
        var db=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var tx=org.mockito.Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class);
        var plans=org.mockito.Mockito.mock(BundleSourcePlan.class);
        var executor=org.mockito.Mockito.mock(GenerationExecutor.class);
        org.mockito.Mockito.when(db.queryForObject("SELECT snapshot_json FROM zzal_custom_bundle_job WHERE id=?",String.class,1L)).thenReturn(original.frozenSnapshot());
        org.mockito.Mockito.when(plans.requirePlan(1L,original.frozenSnapshot())).thenReturn(source.toString());
        var worker=new BundleJobWorker(db,tx,null,plans,"/must-not-execute-python",true);
        assertThatThrownBy(()->worker.run(1L,"test-claim",executor,java.nio.file.Path.of("unused")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Explicit postprocess grounding required before generation");
        org.mockito.Mockito.verifyNoInteractions(tx,executor);
    }
}
