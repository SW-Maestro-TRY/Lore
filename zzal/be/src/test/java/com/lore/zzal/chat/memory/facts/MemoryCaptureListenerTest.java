package com.lore.zzal.chat.memory.facts;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThatCode;
class MemoryCaptureListenerTest {
    @Test void storageFailureAfterCommitDoesNotEscapeAsSendFailure(){
        var worker=mock(MemoryCaptureWorker.class);doThrow(new IllegalStateException()).when(worker).process(1L);
        var listener=new MemoryCaptureListener(worker,Runnable::run);
        assertThatCode(()->listener.committed(new MemoryCaptureRequested(1L))).doesNotThrowAnyException();
        verify(worker).failed(1L);
    }
    @Test void saturatedExecutorLeavesDurableQueuedJobForExplicitRetry(){
        var worker=mock(MemoryCaptureWorker.class);TaskExecutor rejected=r->{throw new TaskRejectedException("full");};
        var listener=new MemoryCaptureListener(worker,rejected);
        assertThatCode(()->listener.committed(new MemoryCaptureRequested(1L))).doesNotThrowAnyException();
        verifyNoInteractions(worker);
    }
}
