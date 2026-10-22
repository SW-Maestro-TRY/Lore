package com.lore.zzal.generation.client;
/** Custom-only process diagnostics. Durable/API writers must redact; never render this cause directly. */
public final class PostprocessFailureException extends IllegalStateException {
 public enum Code { CLIP, GRID_INVALID, TIMEOUT, EXECUTION, MEASUREMENT_AMBIGUOUS }
 private final Code code;private final String stdout,stderr;
 public PostprocessFailureException(Code code,String stdout,String stderr,Throwable cause){super("Custom postprocess "+code,cause);this.code=code;this.stdout=stdout==null?"":stdout;this.stderr=stderr==null?"":stderr;}
 public Code code(){return code;}public String stdout(){return stdout;}public String stderr(){return stderr;}
 public String errorCode(){return "CUSTOM_POSTPROCESS_"+(code==Code.EXECUTION?"FAILED":code.name());}
}
