package com.lore.zzal.diary;
/** The real provider is gated by zzal.diary.enabled (default false). Activation requires execution authorization. */
public interface DiaryModel {
    record Result(String body,String executionReceipt){}
    final class ExecutionUncertain extends RuntimeException {
        private final String receipt;
        public ExecutionUncertain(String receipt,Throwable cause){super("Diary execution outcome uncertain",cause);this.receipt=receipt;}
        public String receipt(){return receipt;}
    }
    Result generate(String frozenSnapshot,String language,String prompt);
}
