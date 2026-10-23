package com.lore.zzal.attendance;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.client.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CustomFailureTest {
 @TempDir Path out;
 private String python(){String p=System.getenv("LORE_CUSTOM_TEST_PYTHON");Assumptions.assumeTrue(p!=null,"explicit existing Python required");return p;}
 @Test void publicDiagnosticsAreCodesOnlyEvenWithQuotedSecretsAndFreeText(){
  String raw="POSTPROCESS_CLIP {\"api_key\":\"secret-canary\",\"token\":\"token-canary\"}\nPOSTPROCESS_FAILED persona private-canary memory private-memory prompt private-prompt\nAuthorization: Bearer bearer-canary\nCookie: session=cookie-canary\nhttps://host/path?X-Amz-Signature=signed-canary\n/Users/private/path";
  assertThat(CustomFailureEvidence.excerpt(raw)).isEqualTo("POSTPROCESS_CLIP");
  assertThat(CustomFailureEvidence.redact(raw,4000)).doesNotContain("secret-canary","token-canary","bearer-canary","cookie-canary","signed-canary","private-canary","private-memory","private-prompt","/Users");
 }
 @Test void firstLocalFailureAndVersionedRemoteEvidenceArePreserved()throws Exception{
  var request=new RealCustomProviderTest().request();var storage=mock(S3Storage.class);var first=new PostprocessFailureException(PostprocessFailureException.Code.CLIP,"POSTPROCESS_CLIP","",new Exception("first"));
  CustomFailureEvidence.preserve(storage,request,out,first);String original=Files.readString(out.resolve("failure/failure.json"));
  var second=new PostprocessFailureException(PostprocessFailureException.Code.TIMEOUT,"","",new Exception("second"));CustomFailureEvidence.preserve(storage,request,out,second);
  assertThat(Files.readString(out.resolve("failure/failure.json"))).isEqualTo(original);verify(storage,times(1)).upload(startsWith(CustomFailureEvidence.prefix(request.requestId())),any(Path.class),eq("application/json"));
  assertThat(CustomFailureEvidence.key(request.requestId())).isNotEqualTo(CustomFailureEvidence.key(request.requestId()));
  assertThat(Files.getPosixFilePermissions(out.resolve("failure/failure.json"))).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
 }
 @Test void splitFailureHasOriginalCauseAndNoImageReplayEvenWhenEvidenceUploadFails()throws Exception{
  var storage=mock(S3Storage.class);doThrow(new IllegalStateException("S3 unavailable")).when(storage).upload(anyString(),any(Path.class),anyString());
  var post=mock(PostProcessor.class);var session=mock(PostProcessor.Session.class);when(post.open(anyString(),anyString())).thenReturn(session);
  var original=new PostprocessFailureException(PostprocessFailureException.Code.CLIP,"POSTPROCESS_CLIP","",new Exception("clip"));doThrow(original).when(session).split(anyString(),anyList(),anyString(),anyInt());
  var generator=mock(CustomGridGenerator.class);when(generator.generate(any(),anyString(),anyString(),anyString(),any())).thenReturn(new CustomGridGenerator.Grid("images/grid.png","LOCAL_TEST","test","medium"));
  var executor=new CustomGridGenerationExecutor(generator,post,storage,"v1",python());var request=new RealCustomProviderTest().request();
  assertThatThrownBy(()->executor.execute(request,out)).isSameAs(original);assertThat(original.getSuppressed()).isNotEmpty();
  assertThatThrownBy(()->executor.execute(request,out)).isInstanceOf(FileAlreadyExistsException.class);verify(generator,times(1)).generate(any(),anyString(),anyString(),anyString(),any());
 }
 @Test void postSplitMissingAssetIsTypedButProviderFailureIsNot()throws Exception{
  var storage=mock(S3Storage.class);doThrow(new IllegalStateException("missing webp")).when(storage).download(anyString(),any(Path.class));
  var post=mock(PostProcessor.class);when(post.open(anyString(),anyString())).thenReturn(mock(PostProcessor.Session.class));
  var generator=mock(CustomGridGenerator.class);when(generator.generate(any(),anyString(),anyString(),anyString(),any())).thenReturn(new CustomGridGenerator.Grid("images/grid.png","LOCAL_TEST","test","medium"));
  var executor=new CustomGridGenerationExecutor(generator,post,storage,"v1",python());var request=new RealCustomProviderTest().request();
  assertThatThrownBy(()->executor.execute(request,out.resolve("post"))).isInstanceOf(PostprocessFailureException.class).hasCauseInstanceOf(IllegalStateException.class);
  var providerFailure=new IllegalStateException("provider uncertain");when(generator.generate(any(),anyString(),anyString(),anyString(),any())).thenThrow(providerFailure);
  assertThatThrownBy(()->executor.execute(request,out.resolve("provider"))).isSameAs(providerFailure);assertThat(out.resolve("provider/failure")).doesNotExist();
 }
 @Test void workerKeepsClaimAndMarksFailureWithoutRetry()throws Exception{
  var request=new RealCustomProviderTest().request();var db=mock(JdbcTemplate.class);var plans=mock(BundleSourcePlan.class);var tx=mock(TransactionTemplate.class);
  when(db.queryForObject(anyString(),eq(String.class),eq(91L))).thenReturn(request.frozenSnapshot());when(plans.requirePlan(91L,request.frozenSnapshot())).thenReturn(request.frozenSourcePlan());
  when(tx.execute(any())).thenReturn(true);var executor=mock(GenerationExecutor.class);var failure=new PostprocessFailureException(PostprocessFailureException.Code.CLIP,"","",new Exception("clip"));when(executor.execute(any(),any())).thenThrow(failure);
  var worker=new BundleJobWorker(db,tx,mock(AttendanceService.class),plans,python(),true);
  assertThatThrownBy(()->worker.run(91L,"test-claim",executor,out)).isSameAs(failure);
  verify(executor,times(1)).execute(any(),eq(out));verify(db).update("UPDATE zzal_custom_bundle_job SET error_code=? WHERE id=? AND state='CLAIMED' AND claim_token=?","CUSTOM_POSTPROCESS_CLIP",91L,"test-claim");
  verifyNoMoreInteractions(executor);
 }
}
