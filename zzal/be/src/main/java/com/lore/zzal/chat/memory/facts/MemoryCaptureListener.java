package com.lore.zzal.chat.memory.facts;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
@Component
public class MemoryCaptureListener {
    private final MemoryCaptureWorker worker;
    private final TaskExecutor executor;
    public MemoryCaptureListener(MemoryCaptureWorker worker,@Qualifier("memoryCaptureExecutor") TaskExecutor executor){
        this.worker=worker;this.executor=executor;
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void committed(MemoryCaptureRequested event){
        try{executor.execute(()->{
            try{worker.process(event.id());}
            catch(Exception e){
                try{worker.failed(event.id());}catch(Exception ignored){ /* QUEUED is retained for explicit retry. */ }
            }
        });}catch(org.springframework.core.task.TaskRejectedException rejected){
            // Keep the durable QUEUED row. A committed reply must not become a send failure.
        }
    }
}
