package com.lore.zzal.diary;
import com.lore.zzal.chat.line.ChatLineClient;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class ChatLineDiaryModelTest {
    private final String snapshot="{\"authoredPersona\":{\"rawText\":\"사용자가 쓴 설정 原文\"},\"memories\":[{\"id\":7,\"revision\":2,\"subject\":\"CHARACTER\"}]}";
    @Test void threeLanguagesPreserveFrozenDataAndSingleRequestReceipt(){
        for(String language:new String[]{"ko","ja","en"}){
            var calls=new AtomicInteger();ChatLineClient client=(sys,user,model,timeout)->{
                calls.incrementAndGet();assertTrue(user.contains("사용자가 쓴 설정 原文"));assertTrue(user.contains("\"revision\":2"));
                assertTrue(user.contains("DIARY_FICTION"));assertTrue(sys.contains("single string field body"));
                assertEquals("existing-model",model);assertEquals(Duration.ofSeconds(15),timeout);
                return new ChatLineClient.Completion("{\"body\":\"原文を変えない。\"}",new BigDecimal("0.000123"),100,20);
            };
            var result=new ChatLineDiaryModel(client,"existing-model",Duration.ofSeconds(15)).generate(snapshot,language,DiaryPrompt.SYSTEM);
            assertEquals(1,calls.get());assertEquals("原文を変えない。",result.body());
            assertTrue(result.executionReceipt().contains("0.000123"));assertTrue(result.executionReceipt().contains("\"language\":\""+language+"\""));
            assertTrue(result.executionReceipt().contains(DiaryEntry.hash(snapshot)));assertTrue(result.executionReceipt().contains("\"inputTokens\":100"));
        }
    }
    @Test void malformedBilledResponseIsFailedWithReceiptAndNoSecondRequest(){
        for(String output:new String[]{"{broken","{}","{\"body\":\"\"}","{\"body\":\"text\",\"userFacts\":[\"injected\"]}"}){
            var calls=new AtomicInteger();var adapter=new ChatLineDiaryModel((s,u,m,t)->{calls.incrementAndGet();return new ChatLineClient.Completion(output,BigDecimal.ONE,2,3);},"model",Duration.ofSeconds(1));
            var result=adapter.generate(snapshot,"ko",DiaryPrompt.SYSTEM);assertNull(result.body());assertEquals(1,calls.get());
            assertTrue(result.executionReceipt().contains("INVALID_"));assertTrue(result.executionReceipt().contains("costUsd"));
        }
    }
    @Test void timeoutRetainsUnknownReceiptAndNeverRetries(){
        var calls=new AtomicInteger();var adapter=new ChatLineDiaryModel((s,u,m,t)->{calls.incrementAndGet();throw new TimeoutException();},"model",Duration.ofMillis(4000));
        var failure=assertThrows(DiaryModel.ExecutionUncertain.class,()->adapter.generate(snapshot,"en",DiaryPrompt.SYSTEM));
        assertEquals(1,calls.get());assertTrue(failure.receipt().contains("UNCERTAIN"));assertTrue(failure.receipt().contains("TimeoutException"));assertTrue(failure.receipt().contains(DiaryEntry.hash(snapshot)));
    }
    @Test void billedExceptionPreservesKnownCostWithoutRetry(){
        var calls=new AtomicInteger();var adapter=new ChatLineDiaryModel((s,u,m,t)->{calls.incrementAndGet();throw new ChatLineClient.BilledException("no content",new BigDecimal("0.000009"));},"model",Duration.ofSeconds(1));
        var failure=assertThrows(DiaryModel.ExecutionUncertain.class,()->adapter.generate(snapshot,"ja",DiaryPrompt.SYSTEM));
        assertEquals(1,calls.get());assertTrue(failure.receipt().contains("0.000009"));
    }
    @Test void invalidInputDoesNotReachClient(){
        var calls=new AtomicInteger();var adapter=new ChatLineDiaryModel((s,u,m,t)->{calls.incrementAndGet();throw new AssertionError("network must not be reached");},"model",Duration.ofSeconds(1));
        assertThrows(IllegalArgumentException.class,()->adapter.generate(snapshot,"fr",DiaryPrompt.SYSTEM));
        assertThrows(IllegalArgumentException.class,()->adapter.generate("{bad","ko",DiaryPrompt.SYSTEM));assertEquals(0,calls.get());
    }
}
