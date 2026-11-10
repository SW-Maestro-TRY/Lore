package com.lore.zzal.attendance;
import java.nio.file.Path;
/** Exactly one explicit invocation. Implementations must not retry or switch providers automatically. */
public interface GenerationExecutor {
 default void preflight()throws Exception{}
 String execute(CustomGenerationRequest request,Path output)throws Exception;
}
