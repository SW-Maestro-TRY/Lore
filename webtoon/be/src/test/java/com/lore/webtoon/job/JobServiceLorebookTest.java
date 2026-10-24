package com.lore.webtoon.job;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.s3.*;
import com.lore.webtoon.art.PrivateArt;
import com.lore.webtoon.character.*;
import com.lore.webtoon.push.JobPush;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.story.StoryStore;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class JobServiceLorebookTest {
 @TempDir Path dir;
 WebtoonJobRepository jobs=mock(WebtoonJobRepository.class);
 JobRunner runner=mock(JobRunner.class);
 ObjectMapper json=new ObjectMapper();
 JobService service;
 @BeforeEach void setup(){
  service=new JobService(jobs,mock(JobStore.class),mock(JobQueue.class),runner,mock(JobProgress.class),mock(StoryStore.class),mock(WorkLedger.class),mock(JobNotice.class),mock(CharacterService.class),mock(CharacterOwner.class),mock(PrivateArt.class),mock(S3Service.class),mock(S3Storage.class),mock(SafetyGuard.class),mock(WebtoonCastSheetRepository.class),mock(RunArt.class),mock(JobPush.class),2,2,dir.toString());
  when(jobs.save(any())).thenAnswer(i->i.getArgument(0));
 }
 JobService.CreateRequest form(Map<String,Object> book){return new JobService.CreateRequest("민수","다정한 청년","판타지","이번 화 이야기","frost",Map.of(),List.of(),List.of(),true,true,"uid",null,"","wave","ko","own","이번 화 고정 설정","","",book);}
 Map<String,Object> books(){return Map.of("confirmed",true,"books",List.of(Map.of("name","도시","fixed_settings","밤마다 문이 열린다","confirmed",true),Map.of("name","인물","fixed_settings","높은 곳을 무서워한다","confirmed",true)));}
 @Test void allBooksAreFrozenInDatabaseAndHarnessInput() throws Exception {
  String id=service.create(form(books()),7L,"uid",null);
  var charFile=json.readTree(dir.resolve(id).resolve("character.json").toFile());
  assertThat(charFile.path("lorebook").path("books").size()).isEqualTo(2);
  assertThat(charFile.path("lorebook").path("fixed_settings").asText()).contains("밤마다","높은 곳");
  var cap=ArgumentCaptor.forClass(WebtoonJob.class);verify(jobs).save(cap.capture());
  assertThat(json.readTree(cap.getValue().getInputJson()).path("lorebook")).isEqualTo(charFile.path("lorebook"));
 }
 @Test void removedBookDoesNotRemainInNewInput() throws Exception {
  String id=service.create(form(null),7L,"uid",null);
  assertThat(json.readTree(dir.resolve(id).resolve("character.json").toFile()).path("lorebook").size()).isZero();
 }
 @Test void continuationFreezesSourceAndWaitsForCommit() throws Exception {
  Path source=dir.resolve("source");Files.createDirectories(source);Files.writeString(source.resolve("sheet.png"),"original sheet");Files.writeString(source.resolve("input.json"),"{\"name\":\"민수\"}");
  TransactionSynchronizationManager.initSynchronization();
  try {
   String id=service.create(form(books()),7L,"uid",null,Map.of("source_dir",source.toString(),"progress_state","친구가 비밀을 알게 됐다","episode_number",2));
   verifyNoInteractions(runner);
   Files.writeString(source.resolve("sheet.png"),"changed source");
   Path frozen=Path.of(json.readTree(dir.resolve(id).resolve("continuation.json").toFile()).path("source_dir").asText());
   assertThat(frozen).startsWith(dir.resolve(id));assertThat(Files.readString(frozen.resolve("sheet.png"))).isEqualTo("original sheet");
   TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCommit());verify(runner).enqueue(any(),eq(dir.resolve(id)));
  }finally {TransactionSynchronizationManager.clearSynchronization();}
 }
 @Test void realPythonConsumesPreparedContinuationAndConfirmedBooksWithoutModels() throws Exception {
  Path source=dir.resolve("original");Files.createDirectory(source);
  Files.writeString(source.resolve("sheet.png"),"unchanged hero sheet");
  json.writeValue(source.resolve("input.json").toFile(),Map.of("name","민수","description","다정한 청년","genre","판타지"));
  json.writeValue(source.resolve("directions.json").toFile(),List.of(Map.of("n",1,"body","앞 화에서 친구를 만났다")));
  json.writeValue(source.resolve("pick.json").toFile(),Map.of("n",1));
  String id=service.create(form(books()),7L,"uid",null,Map.of("source_dir",source.toString(),"source_run","original",
    "episode_number",2,"fixed_settings","수정한 고정 설정","progress_state","친구가 비밀을 안다",
    "source_scenes",List.of("작가가 수정한 마지막 장면"),"request","이번 화 이야기"));
  Path jobsDir=dir.resolve(id), runs=dir.resolve("dry-runs");
  var actual=new HarnessProcess("python3",Path.of("webtoon/ai/new_harness").toAbsolutePath().toString(),60,runs.toString(),null);
  var lines=new ArrayList<String>();
  int code=actual.run(42L,List.of("--run-id","episode2","--character",jobsDir.resolve("character.json").toString(),
    "--continuation",jobsDir.resolve("continuation.json").toString(),"--own","--dry-run"),Map.of("LOGFIRE_TOKEN",""),lines::add);
  assertThat(code).withFailMessage(String.join("\n",lines)).isZero();
  Path output=runs.resolve("episode2");
  assertThat(Files.readString(output.resolve("story_prompt.txt"))).contains("2화","수정한 고정 설정","친구가 비밀을 안다","이번 화 이야기","앞 화에서 친구를 만났다","작가가 수정한 마지막 장면");
  assertThat(json.readTree(output.resolve("input.json").toFile()).path("lorebook").path("books").size()).isEqualTo(2);
  assertThat(Files.readString(output.resolve("sheet.png"))).isEqualTo("unchanged hero sheet");
  assertThat(Files.exists(output.resolve("directions.json"))).isFalse();
  assertThat(Files.exists(output.resolve("sheet_prompt.txt"))).isFalse();
  assertThat(Files.exists(output.resolve("meta.json"))).isFalse();
 }
 @Test void rollbackDeletesOnlyTheNewPreparedJob() throws Exception {
  Path keep=dir.resolve("existing-source");Files.createDirectory(keep);Files.writeString(keep.resolve("sheet.png"),"keep");
  TransactionSynchronizationManager.initSynchronization();
  try {
   String id=service.create(form(books()),7L,"uid",null);
   assertThat(Files.exists(dir.resolve(id))).isTrue();
   TransactionSynchronizationManager.getSynchronizations().forEach(s->s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));
   assertThat(Files.exists(dir.resolve(id))).isFalse();assertThat(Files.readString(keep.resolve("sheet.png"))).isEqualTo("keep");verifyNoInteractions(runner);
  }finally {TransactionSynchronizationManager.clearSynchronization();}
 }
 @Test void databaseFailureDeletesPreparedFiles() throws Exception {
  when(jobs.save(any())).thenThrow(new IllegalStateException("database failure"));
  assertThatThrownBy(()->service.create(form(books()),7L,"uid",null)).hasMessage("database failure");
  try(var files=Files.list(dir)){assertThat(files.toList()).isEmpty();}verifyNoInteractions(runner);
 }

 @Test void generationInputIsOnlyVisibleToItsAccountOwner() throws Exception {
  var job=mock(WebtoonJob.class);when(job.getUserId()).thenReturn(7L);
  when(job.getInputJson()).thenReturn("{\"settings\":\"개인 설정집에서 온 비밀 설정\",\"lorebook\":{\"name\":\"개인 책\"}}");
  try(var auth=mockStatic(com.lore.webtoon.credit.CreditGate.class)) {
   auth.when(com.lore.webtoon.credit.CreditGate::currentUser).thenReturn(8L);
   assertThat((Object)org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"inputsForViewer",job)).isNull();
   auth.when(com.lore.webtoon.credit.CreditGate::currentUser).thenReturn(null);
   assertThat((Object)org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"inputsForViewer",job)).isNull();
   auth.when(com.lore.webtoon.credit.CreditGate::currentUser).thenReturn(7L);
   Map<String,Object> input=org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"inputsForViewer",job);
   assertThat(input).containsEntry("settings","개인 설정집에서 온 비밀 설정").containsEntry("lorebook",Map.of("name","개인 책"));
  }
 }
}
