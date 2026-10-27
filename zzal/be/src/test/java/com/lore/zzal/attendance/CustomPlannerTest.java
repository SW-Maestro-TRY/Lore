package com.lore.zzal.attendance;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
class CustomPlannerTest {
 static final String SNAPSHOT="{\"materialPolicy\":\"CUSTOM_SUBJECT_PRESERVING_LONG_TERM_V3\",\"persona\":{\"raw\":\"차분한 도시 마법사\"},\"identity\":\"짙은 머리와 긴 코트\",\"memory\":{\"candidates\":[{\"id\":11,\"revision\":1,\"subject\":\"USER\",\"kind\":\"USER_REPORTED\",\"status\":\"ACTIVE\",\"sourceTurnId\":50,\"sourceQuote\":\"나는 커피를 좋아해\"}]}}";
 static CustomMotionPlan fixture(){
  List<CustomMotionPlan.Motion> motions=new ArrayList<>();String[] actions={"사용자에게 커피를 건네기","마법책 넘기기","코트 매무새 다듬기","작은 별빛 띄우기","비 오는 창밖 바라보기","가볍게 인사하기","책상에서 졸기","찻잔 들어 보이기"};
  for(int i=0;i<8;i++)motions.add(new CustomMotionPlan.Motion(i+1,actions[i],actions[i],i==0?"USER가 말한 취향을 위한 제안; 캐릭터의 취향이라는 주장 아님":"persona에 맞춘 새 연출; 실제 추억이라는 주장 아님",i==0?CustomMotionPlan.Basis.MEMORY:CustomMotionPlan.Basis.PERSONA_CREATIVE,i==0?List.of(new CustomMotionPlan.Evidence(11L,1,"USER")):List.of(),i==5?"hello":i==6?"sleep":null,i!=5&&i!=6,"정체성을 유지하는 명확한 전신 실루엣",i==0?List.of("커피 잔"):List.of(),"캐릭터는 중앙, 동작 의미와 소품이 읽히는 간결한 연출"));
  return new CustomMotionPlan(CustomMotionPlan.VERSION,motions);
 }
 static CustomGenerationRequest request()throws Exception {var m=new ObjectMapper();String source=m.writeValueAsString(Map.of("selectedSources",List.of(Map.of("factId",11,"revision",1)),"motionPlan",fixture()));return CustomGenerationRequest.from(1,SNAPSHOT,source);}
 @Test void plansVariedActionsAndKeepsUserAttribution()throws Exception {
  var plan=new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),context->{assertThat(context.instructions()).contains("never turn them into CHARACTER facts");assertThat(context.availableMemory()).hasSize(1);return fixture();});assertThat(plan.selectedSources()).containsExactly(new BundleSourcePlan.Source(11L,1));assertThat(plan.motions().get(0).selectedSources().get(0).subject()).isEqualTo("USER");
 }
 static CustomMotionPlan retitled(String... titles){var m=fixture().motions();var out=new ArrayList<CustomMotionPlan.Motion>();for(int i=0;i<8;i++){var o=m.get(i);out.add(new CustomMotionPlan.Motion(o.order(),titles[i%titles.length],o.action(),o.rationale(),o.basis(),o.selectedSources(),o.semantic(),o.albumOnly(),o.pose(),o.props(),o.staging()));}return new CustomMotionPlan(CustomMotionPlan.VERSION,out);}
 @Test void newPlanningTellsAuthorThePetLanguage()throws Exception {
  new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),"ko",c->{assertThat(c.instructions()).contains("never turn them into CHARACTER facts").contains("OUTPUT LANGUAGE: ko").contains("Korean (한국어)").contains("title");return fixture();});
  new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),"ja",c->{assertThat(c.instructions()).contains("OUTPUT LANGUAGE: ja").contains("Japanese");return retitled("窓の外を見る","こっそり乾杯");});
  new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),null,c->{assertThat(c.instructions()).isEqualTo(CustomMotionPlanner.INSTRUCTIONS);return fixture();});
  assertThat(CustomMotionPlanner.languageInstruction("xx")).contains("OUTPUT LANGUAGE: ko");
 }
 @Test void englishTitlesAreRejectedForKoreanPetButKoreanTitlesPass()throws Exception {
  var english=retitled("Chocolate Victory Pose","Cloudbound Leap");
  assertThatThrownBy(()->new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),"ko",c->english)).isInstanceOf(CustomMotionPlanner.LanguageMismatch.class).hasMessageContaining("pet language ko");
  assertThat(new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),"ko",c->retitled("초콜릿 승리 포즈","구름 위로 점프")).motions()).hasSize(8);
  assertThat(new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),"en",c->english).motions()).hasSize(8);
  assertThatThrownBy(()->new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),"en",c->fixture())).isInstanceOf(CustomMotionPlanner.LanguageMismatch.class);
  assertThatThrownBy(()->new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),"ja",c->english)).isInstanceOf(CustomMotionPlanner.LanguageMismatch.class);
  // Frozen historical plans are revalidated without the language gate, so existing English plans keep publishing.
  assertThat(new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),c->english).motions()).hasSize(8);
 }
 @Test void rejectsPreviouslyUsedEvidenceAndWrongSubject(){
  String episode=AttendanceService.hash("50|나는 커피를 좋아해");assertThatThrownBy(()->new CustomMotionPlanner().plan(SNAPSHOT,Set.of(episode),c->fixture())).hasMessage("Unknown, excluded or misattributed motion evidence");
  var old=fixture().motions().get(0);var wrong=new CustomMotionPlan.Motion(1,old.title(),old.action(),old.rationale(),old.basis(),List.of(new CustomMotionPlan.Evidence(11L,1,"CHARACTER")),null,true,old.pose(),old.props(),old.staging());var list=new ArrayList<>(fixture().motions());list.set(0,wrong);assertThatThrownBy(()->new CustomMotionPlanner().plan(SNAPSHOT,Set.of(),c->new CustomMotionPlan(CustomMotionPlan.VERSION,list))).hasMessage("Unknown, excluded or misattributed motion evidence");
 }
 @Test void noMemoryAllowsPersonaCreativityWithoutClaimingPastExperience()throws Exception {
  var motions=fixture().motions().stream().map(m->new CustomMotionPlan.Motion(m.order(),m.title(),m.action(),"새 persona 창작",CustomMotionPlan.Basis.PERSONA_CREATIVE,List.of(),m.semantic(),m.albumOnly(),m.pose(),m.props(),m.staging())).toList();
  assertThat(new CustomMotionPlanner().plan("{\"materialPolicy\":\"CUSTOM_SUBJECT_PRESERVING_LONG_TERM_V3\"}",Set.of(),c->new CustomMotionPlan(CustomMotionPlan.VERSION,motions)).selectedSources()).isEmpty();
 }
 @Test void freeActionsCannotEnterRoomSemanticAndOrdersCannotDuplicate(){
  assertThat(CustomMotionPlan.BASIC_SEMANTICS).containsExactlyInAnyOrder("base","eat","joy","sad","sick","pet","hello","sleep");
  assertThatThrownBy(()->new CustomMotionPlan.Motion(1,"행동","행동","근거",CustomMotionPlan.Basis.PERSONA_CREATIVE,List.of(),"reply",false,"pose",List.of(),"stage")).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->new CustomMotionPlan(CustomMotionPlan.VERSION,Collections.nCopies(8,fixture().motions().get(0)))).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void requestStableAndCannotSwapPlanOrQuality()throws Exception {
  var r=request();assertThat(CustomGenerationRequest.from(1,SNAPSHOT,r.frozenSourcePlan())).isEqualTo(r);assertThat(r.quality()).isEqualTo("medium");
  var changed=fixture().motions().stream().map(m->new CustomMotionPlan.Motion(m.order(),m.title()+"異",m.action(),m.rationale(),m.basis(),m.selectedSources(),m.semantic(),m.albumOnly(),m.pose(),m.props(),m.staging())).toList();
  assertThatThrownBy(()->new CustomGenerationRequest(r.pipelineVersion(),r.requestId(),"medium",r.snapshotHash(),r.sourcePlanHash(),new CustomMotionPlan(CustomMotionPlan.VERSION,changed),r.frozenSnapshot(),r.frozenSourcePlan())).hasMessage("Motion plan differs from frozen source plan");
  String swapped=r.frozenSourcePlan().replace("\"factId\":11,\"revision\":1","\"factId\":12,\"revision\":1");
  // Build a deterministic altered source selection independent of map serialization order.
  var root=(com.fasterxml.jackson.databind.node.ObjectNode)new ObjectMapper().readTree(r.frozenSourcePlan());root.set("selectedSources",new ObjectMapper().valueToTree(List.of(Map.of("factId",12,"revision",1))));String different=root.toString();
  assertThatThrownBy(()->new CustomGenerationRequest(r.pipelineVersion(),r.requestId(),"medium",r.snapshotHash(),AttendanceService.hash(different),r.frozenPlan(),r.frozenSnapshot(),different)).hasMessage("Source plan differs from motion evidence");
  var noAttestation=new BundleJobWorker.Manifest("run",List.of(),List.of(),r.requestId().toString(),r.pipelineVersion(),"medium",null,"CLI",null);assertThatThrownBy(()->BundleJobWorker.validateExecutionReceipt(r,noAttestation)).hasMessage("Execution quality/provider attestation missing or mismatched");
 }
 @Test void exportsRealTypedFixtureWhenEvidencePathProvided()throws Exception {
  var r=request();assertThat(r.frozenPlan().roomMotionProjection()).extracting(CustomMotionPlan.RoomMotion::semantic).containsExactly("hello","sleep");
  String destination=System.getenv("CUSTOM_PLANNER_EVIDENCE");if(destination!=null){Path path=Path.of(destination);Files.createDirectories(path);var mapper=new ObjectMapper();Files.writeString(path.resolve("typed-motion-plan.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r.frozenPlan()));Files.writeString(path.resolve("generation-request.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(r));Files.writeString(path.resolve("material-manifest.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("synthetic",true,"modelCalled",false,"snapshot",mapper.readTree(SNAPSHOT),"snapshotHash",r.snapshotHash(),"sourcePlanHash",r.sourcePlanHash(),"selectedSources",r.frozenPlan().selectedSources(),"roomProjection",r.frozenPlan().roomMotionProjection())));}
 }
 @Test void cliFailureDoesNotRetryOrSwitchProviders(@org.junit.jupiter.api.io.TempDir Path out)throws Exception {
  Path script=out.resolve("fail.py");Files.writeString(script,"import pathlib,sys\np=pathlib.Path(sys.argv[2])/'attempts';p.write_text(p.read_text()+'x' if p.exists() else 'x');sys.exit(1)\n");
  assertThatThrownBy(()->new CliGenerationExecutor(List.of("python3",script.toString())).execute(request(),out)).hasMessage("CLI_EXIT_1");assertThat(Files.readString(out.resolve("attempts"))).isEqualTo("x");
 }
 @Test void cliTransmitsMediumPlanAndHashesWithoutExternalCall(@org.junit.jupiter.api.io.TempDir Path out)throws Exception {
  Path script=out.resolve("fake.py");Files.writeString(script,"import json,sys,pathlib\ni=json.load(open(sys.argv[1]));o=pathlib.Path(sys.argv[2]);(o/'manifest.json').write_text(json.dumps(i['generationRequest']))\n");
  var r=request();String receipt=new CliGenerationExecutor(List.of("python3",script.toString())).execute(r,out);var json=new ObjectMapper().readTree(receipt);assertThat(json.path("quality").asText()).isEqualTo("medium");assertThat(json.path("frozenPlan").path("motions")).hasSize(8);var provenance=new ObjectMapper().readTree(Files.readString(out.resolve("input-provenance.json")));assertThat(provenance.path("sourceSnapshotHash").asText()).isEqualTo(r.snapshotHash());assertThat(provenance.path("generatorInputHash").asText()).isEqualTo(AttendanceService.hash(Files.readString(out.resolve("input.json"))));
 }
}
