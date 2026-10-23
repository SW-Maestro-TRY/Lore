package com.lore.zzal.generation.client;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
class ProviderAttemptTest {
 @Test void consolidatesHeadersAndPayloadAndPreservesUnknownTokens() throws Exception {
  var rows=new ArrayList<ProviderUsageRecorder.Observation>();
  try(var attempt=new ProviderAttempt(rows::add,"model")) {
   attempt.record(ProviderUsageRecorder.headers("model",200,"request-1"));
   attempt.record(ProviderUsageRecorder.payload("model",200,"request-1",new ObjectMapper().readTree("{\"usage\":{\"prompt_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":4}}}")));
   attempt.success();
  }
  assertThat(rows).hasSize(1);
  assertThat(rows.getFirst().callId()).isNotBlank();
  assertThat(rows.getFirst().callStatus()).isEqualTo("succeeded");
  assertThat(rows.getFirst().estimatedCostUsd()).isNull();
  assertThat(PersistentProviderUsageRecorder.token(rows.getFirst().usageRaw(),"output_tokens","completion_tokens")).isNull();
 }
 @Test void recorderFailureDoesNotReplaceProviderOutcome() {
  assertThatCode(()-> {try(var attempt=new ProviderAttempt(o->{throw new IllegalStateException("disk");},"model")){attempt.start();attempt.success();}}).doesNotThrowAnyException();
 }
 @Test void failureBeforeHttpDispatchDoesNotInventProviderCall() {
  var rows=new ArrayList<ProviderUsageRecorder.Observation>();
  try(var attempt=new ProviderAttempt(rows::add,"model")) { /* local input validation failed before dispatch */ }
  assertThat(rows).isEmpty();
 }
 @Test void explicitScopesRestoreAndDoNotLeak() {
  assertThat(ProviderUsageContext.current()).isNull();
  try(var outer=ProviderUsageContext.open("chat",1L,null,"request",1)) {
   try(var inner=ProviderUsageContext.open("custom",2L,3L,"job",2)) {assertThat(ProviderUsageContext.current().petId()).isEqualTo(2L);}
   assertThat(ProviderUsageContext.current().petId()).isEqualTo(1L);
  }
  assertThat(ProviderUsageContext.current()).isNull();
 }
}
