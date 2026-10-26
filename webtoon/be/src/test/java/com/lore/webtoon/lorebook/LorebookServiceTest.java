package com.lore.webtoon.lorebook;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.usage.*;
import com.lore.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class LorebookServiceTest {
 final HarnessProcess harness=mock(HarnessProcess.class);
 final LoreExtractionRepository rows=mock(LoreExtractionRepository.class);
 final SafetyGuard safety=mock(SafetyGuard.class);
 final SpendGuard guard=mock(SpendGuard.class);
 final UsageService usage=mock(UsageService.class);
 final LorebookService service=new LorebookService(harness,rows,safety,guard,usage,3);
 @Test void catalogUsesRealFourteenBooks(){when(harness.dir()).thenReturn(Path.of("webtoon/ai/new_harness"));var result=service.catalog(null);var books=(List<Map<String,Object>>)result.get("books");assertThat(books).hasSize(14);books.forEach(b->assertThat(LoreBook.clean(b,false)).containsEntry("confirmed",false));}
 @Test void privateResultsRequireOwner(){var row=new LoreExtraction("extract",7L);when(rows.findById("extract")).thenReturn(Optional.of(row));assertThatThrownBy(()->service.view("extract",null)).isInstanceOf(BusinessException.class);assertThatThrownBy(()->service.view("extract",8L)).isInstanceOf(BusinessException.class);assertThat(service.view("extract",7L)).containsEntry("status","RUNNING");}
 @Test void extractionRequiresLoginRightsAndMinimumText(){assertThatThrownBy(()->service.extract(null,new LorebookService.ExtractForm("x".repeat(40),true,"ko"))).isInstanceOf(BusinessException.class);assertThatThrownBy(()->service.extract(7L,new LorebookService.ExtractForm("x".repeat(40),false,"ko"))).isInstanceOf(BusinessException.class);assertThatThrownBy(()->service.extract(7L,new LorebookService.ExtractForm("short",true,"ko"))).isInstanceOf(BusinessException.class);verifyNoInteractions(harness,rows);}
 @Test void exhaustedQuotaDoesNotStartModelAndReleasesSlot(){when(rows.reserve(any(),any(),eq(3))).thenReturn(0);for(int i=0;i<4;i++)assertThatThrownBy(()->service.extract(7L,new LorebookService.ExtractForm("x".repeat(40),true,"ko"))).isInstanceOf(BusinessException.class).hasMessageContaining("횟수");verifyNoInteractions(harness);}
 @Test void successfulExtractionReturnsEditableDraftAndCleansSource() throws Exception {
  when(rows.reserve(any(),any(),eq(3))).thenReturn(1);
  var process=new java.util.concurrent.atomic.AtomicReference<Thread>();
  var folder=new java.util.concurrent.atomic.AtomicReference<Path>();
  var started=new java.util.concurrent.LinkedBlockingQueue<Boolean>();
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var rowRef=new java.util.concurrent.atomic.AtomicReference<LoreExtraction>();
  when(rows.save(any())).thenAnswer(call->{LoreExtraction row=call.getArgument(0);rowRef.set(row);return row;});
  when(rows.findById(anyString())).thenAnswer(call->Optional.ofNullable(rowRef.get()));
  doAnswer(call->{var row=rowRef.get();row.status=call.getArgument(1);row.resultJson=call.getArgument(2);return null;})
    .when(rows).finish(anyString(),eq("DONE"),anyString(),isNull());
  when(harness.extractLore(any())).thenAnswer(call->{
   process.set(Thread.currentThread());Path path=call.getArgument(0);folder.set(path);
   assertThat(mapper.readTree(path.resolve("input.json").toFile()).path("text").asText()).contains("우체국");
   mapper.writeValue(path.resolve("result.json").toFile(),Map.of("name","달빛 우체국","source","extracted","confirmed",false,
      "fixed_settings","편지는 밤에만 읽힌다","progress_state","","entries",List.of()));
   started.add(true);return 0;
  });
  org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
  try {
   var result=service.extract(7L,new LorebookService.ExtractForm("달빛 우체국에서는 편지를 밤에만 읽을 수 있다. 하린은 그곳의 새로운 배달부다.",true,"ko"));
   verifyNoInteractions(harness);
   org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(sync->sync.afterCommit());
   assertThat(started.poll(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();process.get().join(5000);
   assertThat(process.get().isAlive()).isFalse();
   var response=service.view(result.get("id").toString(),7L);
   assertThat(response).containsEntry("status","DONE");
   var book=(Map<String,Object>)response.get("book");
   assertThat(book).containsEntry("confirmed",false).containsEntry("progress_state","");
   assertThatThrownBy(()->LoreBook.clean(book,true)).isInstanceOf(BusinessException.class);
   var edited=new LinkedHashMap<>(book);edited.put("fixed_settings","작가가 수정한 규칙");edited.put("confirmed",true);
   assertThat(LoreBook.clean(edited,true)).containsEntry("fixed_settings","작가가 수정한 규칙");
   assertThat(Files.exists(folder.get())).isFalse();verify(usage).ingestMetaFile(anyString(),any());
  } finally {org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
 }
 @Test void failedExtractionCleansSourceAccountsUsageAndReleasesSlot() throws Exception {
  when(rows.reserve(any(),any(),eq(3))).thenReturn(1);
  var process=new java.util.concurrent.atomic.AtomicReference<Thread>();var folder=new java.util.concurrent.atomic.AtomicReference<Path>();
  var started=new java.util.concurrent.LinkedBlockingQueue<Boolean>();
  when(harness.extractLore(any())).thenAnswer(call->{process.set(Thread.currentThread());folder.set(call.getArgument(0));Files.writeString(folder.get().resolve("meta.json"),"{}");started.add(true);return 1;});
  for(int i=0;i<3;i++){
   org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
   try{
    var result=service.extract(7L,new LorebookService.ExtractForm("원문".repeat(30),true,"ko"));
    org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().forEach(sync->sync.afterCommit());
    assertThat(started.poll(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();process.get().join(5000);
    assertThat(process.get().isAlive()).isFalse();assertThat(Files.exists(folder.get())).isFalse();
    verify(rows).finish(eq(result.get("id").toString()),eq("ERROR"),isNull(),anyString());
   }finally{org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();}
  }
  verify(usage,times(3)).ingestMetaFile(anyString(),any());
 }

}
