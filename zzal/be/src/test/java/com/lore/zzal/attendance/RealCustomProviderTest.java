package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.*;
import com.lore.zzal.generation.client.*;
import com.lore.common.s3.S3Storage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealCustomProviderTest {
 CustomMotionPlan grounded(){return new CustomMotionPlan(CustomMotionPlan.CURRENT_VERSION,CustomPlannerTest.fixture().motions().stream().map(m->new CustomMotionPlan.Motion(m.order(),m.title(),m.action(),m.rationale(),m.basis(),m.selectedSources(),m.semantic(),m.albumOnly(),("sleep".equals(m.semantic())?"crouch:":"standing:")+m.pose(),m.props(),m.staging(),List.of())).toList());}
 CustomGenerationRequest request()throws Exception{return request(grounded());}
 CustomGenerationRequest request(CustomMotionPlan plan)throws Exception{
  var json=new ObjectMapper();var snapshot=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(CustomPlannerTest.SNAPSHOT);
  snapshot.put("materialPolicy",BundleCommandAdapter.SHEET_REFERENCE_POLICY);snapshot.put("sourceImageKey","images/source.png");snapshot.put("sheetImageKey","images/sheet.png");snapshot.put("baseReferenceRound",1);snapshot.put("anchorsKey","images/basic/1/anchors.json");snapshot.put("appearancePipelineVersion","v1");snapshot.put("customGridTemplateVersion",CustomGridPrompt.TEMPLATE_VERSION);snapshot.put("customGridTemplateHash",CustomGridPrompt.templateHash());
  String source=json.writeValueAsString(Map.of("selectedSources",plan.selectedSources(),"motionPlan",plan));return CustomGenerationRequest.from(91,json.writeValueAsString(snapshot),source);
 }
 @Test void realAuthorUsesOneExistingTextCallWithExplicitModelAndPreservedRoles()throws Exception{
  var client=mock(TextClient.class);when(client.generate(anyString(),eq(List.of()),any())).thenReturn(new TextClient.Result(new ObjectMapper().writeValueAsString(grounded()),BigDecimal.ONE));var audit=new AtomicReference<TextClientCustomAuthor.Audit>();var author=new TextClientCustomAuthor(client,"configured-planner-model",audit::set);
  var plan=new CustomMotionPlanner().plan(CustomPlannerTest.SNAPSHOT,Set.of(),author);assertThat(plan).isEqualTo(grounded());assertThat(audit.get().model()).isEqualTo("configured-planner-model");assertThat(audit.get().costUsd()).isEqualTo(BigDecimal.ONE);
  verify(client,times(1)).generate(contains("never turn them into CHARACTER facts"),eq(List.of()),eq(ModelSpec.of("configured-planner-model")));verifyNoMoreInteractions(client);
 }
 @Test void malformedAuthorOutputDoesNotRetryOrSwitchModels()throws Exception{
  var client=mock(TextClient.class);when(client.generate(anyString(),anyList(),any())).thenReturn(new TextClient.Result("```not valid JSON```",BigDecimal.ONE));var author=new TextClientCustomAuthor(client,"fixed-model",audit->{});
  assertThatThrownBy(()->new CustomMotionPlanner().plan(CustomPlannerTest.SNAPSHOT,Set.of(),author)).isInstanceOf(Exception.class);verify(client,times(1)).generate(anyString(),eq(List.of()),eq(ModelSpec.of("fixed-model")));verifyNoMoreInteractions(client);
 }
 @Test void apiAdapterSendsExplicitMediumAndExactReferenceOneTime()throws Exception{
  var images=mock(ImageClient.class);when(images.generate(anyString(),anyList(),anyString(),any())).thenReturn(new ImageClient.Result("images/output/grid.png",BigDecimal.ONE));var grid=new ImageClientCustomGridGenerator(images,"configured-image-model","1024x1024");var request=request();var render=CustomGridPrompt.render(request);
  var result=grid.generate(request,render.prompt(),"images/sheet.png","images/output/grid.png",Path.of("unused"));assertThat(result.actualQuality()).isEqualTo("medium");assertThat(result.costUsd()).isEqualTo(BigDecimal.ONE);verify(images,times(1)).generate(render.prompt(),List.of("images/sheet.png"),"images/output/grid.png",new ModelSpec("configured-image-model","1024x1024","medium"));verifyNoMoreInteractions(images);
 }
 @Test void cliUnsupportedMediumFailsBeforeStorageProcessOrPlanner()throws Exception{
  var storage=mock(S3Storage.class);var cli=new CodexCustomGridGenerator(storage,"not-executed","not-executed",Path.of("unused"),false);assertThatThrownBy(cli::preflight).hasMessageContaining("CLI_MEDIUM_CAPABILITY_UNVERIFIED");verifyNoInteractions(storage);
  var planner=mock(CustomPlanningService.class);var worker=mock(BundleJobWorker.class);var text=mock(TextClient.class);var executor=mock(GenerationExecutor.class);doThrow(new IllegalStateException("unsupported medium")).when(executor).preflight();var service=new RealCustomBundleService(planner,worker,executor,text,"configured");assertThatThrownBy(()->service.run(1,"token",Path.of("unused"),Instant.EPOCH)).hasMessage("unsupported medium");verifyNoInteractions(planner,worker,text);
 }
 @Test void groundingAndDynamicEightActionsAreExplicitBeforeImageCall()throws Exception{
  var rendered=CustomGridPrompt.render(request());assertThat(rendered.prompt()).contains("Cells 1-2:","Cells 15-16:","커피를 건네기","짙은 머리와 긴 코트").doesNotContain("1-2 EATING RICE","7-8 WASHING");assertThat(rendered.postures()).contains("eat_rice=standing","startle=crouch");
  assertThatThrownBy(()->CustomGridPrompt.render(request(CustomPlannerTest.fixture()))).hasMessage("Explicit postprocess grounding required before generation");
 }
 @Test void commonExecutorBuildsEightVerifiedUnpublishedAssetsAndCannotReplay(@org.junit.jupiter.api.io.TempDir Path out)throws Exception {
  String python=System.getenv("LORE_CUSTOM_TEST_PYTHON");org.junit.jupiter.api.Assumptions.assumeTrue(python!=null,"explicit existing PIL environment required");
  Path fixture=out.resolve("fixture.webp");var process=new ProcessBuilder(python,"-c","from PIL import Image,ImageDraw;import sys\na=Image.new('RGB',(32,32),'red');ImageDraw.Draw(a).rectangle((3,3,14,21),fill='blue');b=Image.new('RGB',(32,32),'red');ImageDraw.Draw(b).rectangle((4,3,15,21),fill='blue');a.save(sys.argv[1],save_all=True,append_images=[b],duration=[450,450],loop=0)",fixture.toString()).inheritIO().start();assertThat(process.waitFor()).isZero();
  var storage=mock(S3Storage.class);doAnswer(inv->{String key=inv.getArgument(0,String.class);Path target=inv.getArgument(1,Path.class);if(key.endsWith("/anchors.json"))Files.writeString(target,"{\"version\":\"1\",\"canvas\":{\"w\":32,\"h\":32},\"K\":24,\"Hw\":12,\"poses\":{}}");else Files.copy(fixture,target,StandardCopyOption.REPLACE_EXISTING);return null;}).when(storage).download(anyString(),any(Path.class));
  var post=mock(PostProcessor.class);var session=mock(PostProcessor.Session.class);when(post.open(anyString(),eq("v1"))).thenReturn(session);
  var generator=mock(CustomGridGenerator.class);when(generator.generate(any(),anyString(),anyString(),anyString(),any())).thenReturn(new CustomGridGenerator.Grid("images/grid.png","LOCAL_STUB","synthetic-grid","medium"));
  var executor=new CustomGridGenerationExecutor(generator,post,storage,"v1",python);var request=request();Path execution=out.resolve("execution");String receipt=executor.execute(request,execution);var parsed=new ObjectMapper().readValue(receipt,BundleJobWorker.Manifest.class);assertThat(parsed.assets()).hasSize(8).allMatch(a->!a.published());assertThat(parsed.usedSources()).containsExactly(new BundleSourcePlan.Source(11L,1));BundleJobWorker.validateExecutionReceipt(request,parsed);
  var order=inOrder(session,generator);order.verify(session).seedAnchors("images/basic/1/anchors.json");order.verify(generator).generate(eq(request),anyString(),eq("images/sheet.png"),anyString(),eq(execution));
  verify(session).seedAnchors("images/basic/1/anchors.json");
  verify(session).split("images/grid.png",CustomGridPrompt.SLOTS,CustomGridPrompt.render(request).postures(),2);verify(session).close();
  assertThatThrownBy(()->executor.execute(request,execution)).isInstanceOf(java.nio.file.FileAlreadyExistsException.class);verify(generator,times(1)).generate(any(),anyString(),anyString(),anyString(),any());
 }
 @Test void optInConfigurationIsAbsentByDefaultAndFailsClosedOnIncompleteFlags(){
  new ApplicationContextRunner().withUserConfiguration(CustomProviderConfiguration.class).run(c->assertThat(c).doesNotHaveBean(RealCustomBundleService.class));
  assertThatThrownBy(()->new CustomProviderConfiguration().realCustomBundleService(new MockEnvironment(),null,null,null,null,null,null,null)).hasMessageContaining("explicit real-generation");
 }

 @Test void productionConfigurationRequiresPublisherAndLegacyConstructorRemainsCompatible(){
  var env=new MockEnvironment().withProperty("app.zzal.generation.real","true").withProperty("app.zzal.generation.real-postprocess","true").withProperty("app.zzal.attendance.cli-enabled","true").withProperty("app.zzal.openai.api-key","SYNTHETIC_NOT_A_KEY").withProperty("app.zzal.custom.planner-model","gpt-5.6-luna").withProperty("app.zzal.custom.provider","api").withProperty("app.zzal.custom.image-model","SYNTHETIC").withProperty("app.zzal.custom.image-size","1024x1024").withProperty("app.zzal.custom.postprocess-version","v1");
  var planning=mock(CustomPlanningService.class);var worker=mock(BundleJobWorker.class);var text=mock(TextClient.class);var images=mock(ImageClient.class);var post=mock(PostProcessor.class);var storage=mock(S3Storage.class);var publication=mock(CustomBundlePublicationService.class);
  var service=new CustomProviderConfiguration().realCustomBundleService(env,planning,worker,text,images,post,storage,publication);assertThat(org.springframework.test.util.ReflectionTestUtils.getField(service,"publication")).isSameAs(publication);assertThat(org.springframework.test.util.ReflectionTestUtils.getField(service,"text")).isInstanceOf(CustomPlannerTextClient.class);
  assertThatThrownBy(()->new CustomProviderConfiguration().realCustomBundleService(env,planning,worker,text,images,post,storage,null)).hasMessage("Production custom publisher required");
  var legacy=new RealCustomBundleService(planning,worker,mock(GenerationExecutor.class),text,"SYNTHETIC");assertThat(org.springframework.test.util.ReflectionTestUtils.getField(legacy,"publication")).isNull();verifyNoInteractions(text,images,post,storage,publication);
 }
}
