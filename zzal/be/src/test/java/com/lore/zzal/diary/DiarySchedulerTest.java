package com.lore.zzal.diary;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import static org.mockito.Mockito.*;
class DiarySchedulerTest {
    private final DiaryWorkerTransactions tx=mock(DiaryWorkerTransactions.class);
    private final DiaryModel model=mock(DiaryModel.class);
    private final DiaryWorkerTransactions.Claim claim=new DiaryWorkerTransactions.Claim(1L,"token","frozen source","ja");
    private final DiaryScheduler scheduler=new DiaryScheduler(null,null,null,tx,null);
    @Test void storageRetryReusesSameResultAndCallsModelOnce(){
        when(tx.claim(eq(1L),any())).thenReturn(Optional.of(claim));
        var result=new DiaryModel.Result("原文のまま。","stub:receipt");when(model.generate(any(),any(),any())).thenReturn(result);
        when(tx.finish(eq(claim),eq(result),any())).thenThrow(new RuntimeException("save failed")).thenReturn(true);
        scheduler.run(1L,model);verify(model,times(1)).generate("frozen source","ja",DiaryPrompt.SYSTEM);
        verify(tx,times(2)).finish(eq(claim),same(result),any());verify(tx,never()).uncertain(any(),any());
    }
    @Test void providerTimeoutNeverRetriesAndRetainsUnknownSlot(){
        when(tx.claim(eq(1L),any())).thenReturn(Optional.of(claim));when(model.generate(any(),any(),any())).thenThrow(new RuntimeException("timeout"));
        scheduler.run(1L,model);verify(model,times(1)).generate(any(),any(),any());verify(tx).uncertain(eq(claim),contains("unknown"));verify(tx,never()).finish(any(),any(),any());
    }
    @Test void providerBillingReceiptSurvivesSchedulerFailureBoundary(){
        when(tx.claim(eq(1L),any())).thenReturn(Optional.of(claim));
        String receipt="{\"outcome\":\"UNCERTAIN\",\"costUsd\":0.000123,\"sourceHash\":\"source\"}";
        when(model.generate(any(),any(),any())).thenThrow(new DiaryModel.ExecutionUncertain(receipt,new java.util.concurrent.TimeoutException()));
        scheduler.run(1L,model);verify(tx).uncertain(claim,receipt);verify(model,times(1)).generate(any(),any(),any());
    }
    @Test void duplicateClaimDoesNotCallModel(){
        when(tx.claim(eq(1L),any())).thenReturn(Optional.empty());scheduler.run(1L,model);verifyNoInteractions(model);
    }
}
