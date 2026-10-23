package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
/** Prepared only in this batch; compile is permitted, execution waits for coordinated validation. */
class CustomAppearanceCorrectionTest {
 @Test void newTemplateAllowsPlannedPropsAndRejectsHistoricalVersionBeforeAuthor()throws Exception{
  String template;
  try(var stream=CustomGridPrompt.class.getResourceAsStream("/zzal/prompt/custom/v2/grid.txt")){template=new String(java.util.Objects.requireNonNull(stream).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
  assertThat(CustomGridPrompt.TEMPLATE_VERSION).isEqualTo("custom-v2");assertThat(template).contains("lunchbox plus fork","external petting hand","bathtub","exactly 25","#00FF00").doesNotContain("HELD OBJECTS ONLY","exactly the one object","no effect of any kind");
  var source=new ObjectMapper().createObjectNode();source.put("materialPolicy",BundleCommandAdapter.SHEET_REFERENCE_POLICY);source.put("customGridTemplateVersion","custom-v1");source.put("customGridTemplateHash","historical-hash");var calls=new java.util.concurrent.atomic.AtomicInteger();
  assertThatThrownBy(()->new CustomMotionPlanner().plan(source.toString(),Set.of(),context->{calls.incrementAndGet();return CustomPlannerTest.fixture();})).hasMessage("Frozen sheet/template contract required; historical job is not rewritten");assertThat(calls.get()).isZero();
 }

 @Test void newSnapshotProjectionRetainsSheetAndBaseMetadataWithoutChangingLegacyProjection()throws Exception{
  String source="{\"materialPolicy\":\"CUSTOM_SUBJECT_SHEET_REFERENCE_V4\",\"sheetImageKey\":\"images/sheet.png\",\"anchorsKey\":\"images/basic/1/anchors.json\",\"baseReferenceRound\":1,\"appearancePipelineVersion\":\"v1\",\"customGridTemplateVersion\":\"custom-v1\",\"customGridTemplateHash\":\"hash\",\"identity\":\"시트 정체성\"}";
  var projected=new ObjectMapper().readTree(BundleCommandAdapter.firstSetInput(source).json());assertThat(projected.path("sheetImageKey").asText()).isEqualTo("images/sheet.png");assertThat(projected.path("baseReferenceRound").asInt()).isEqualTo(1);assertThat(projected.path("anchorsKey").asText()).isEqualTo("images/basic/1/anchors.json");
  String old=source.replace("CUSTOM_SUBJECT_SHEET_REFERENCE_V4","CUSTOM_SUBJECT_PRESERVING_LONG_TERM_V3");assertThat(new ObjectMapper().readTree(BundleCommandAdapter.firstSetInput(old).json()).has("sheetImageKey")).isFalse();assertThat(source).contains("시트 정체성");
 }
 @Test void profileUsesDeclaredNonBaseSlotsAndRefusesMissingOrWrongVersion()throws Exception{
  var mapper=new ObjectMapper();var source=mapper.readTree("{\"materialPolicy\":\"CUSTOM_SUBJECT_SHEET_REFERENCE_V4\",\"sheetImageKey\":\"images/sheet\",\"anchorsKey\":\"images/anchors\",\"baseReferenceRound\":2,\"appearancePipelineVersion\":\"v1\"}");
  assertThat(CustomPostprocessProfile.from(source,"v1").baseReferenceRound()).isEqualTo(2);assertThat(CustomGridPrompt.SLOTS).hasSize(8).doesNotContain("base");
  assertThatThrownBy(()->CustomPostprocessProfile.from(source,"other")).hasMessage("Missing or incompatible frozen sheet/anchors/base round/version");assertThatThrownBy(()->CustomPostprocessProfile.from(mapper.createObjectNode(),"v1")).hasMessageContaining("historical snapshot unchanged");
 }
}
