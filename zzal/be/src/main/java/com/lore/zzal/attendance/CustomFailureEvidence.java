package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.client.PostprocessFailureException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
/** Contents are safe even when a local bucket has an overly broad public-read policy. */
public final class CustomFailureEvidence {
 private CustomFailureEvidence(){}
 public static String prefix(UUID id){return "zzal-review/custom-bundle-failures/"+id+"/";}
 public static String key(UUID id){return prefix(id)+java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSSSSSSSS").withZone(java.time.ZoneOffset.UTC).format(Instant.now())+"-"+UUID.randomUUID()+".json";}
 public static String redact(String text,int cap){
  if(text==null)return null;
  String safeLines=text.replaceAll("(?im)^.*(?:authorization|bearer|cookie|token|password|secret|api[_-]?key|claim|prompt|persona|memory).*$", "[sensitive diagnostic omitted]");
  String s=safeLines.replaceAll("(?i)(authorization\\s*[:=]\\s*|bearer\\s+)[^\\s\\r\\n]+","[credential]")
   .replaceAll("(?i)([A-Z0-9_-]*(?:token|password|secret|api[_-]?key|claim)[A-Z0-9_-]*\\s*[:=]\\s*)[^\\s\\r\\n,;]+","[credential]")
   .replaceAll("\\b(?:sk-[A-Za-z0-9_-]+|AKIA[A-Z0-9]{16})\\b","[credential]")
   .replaceAll("https?://[^\\s\\r\\n]+","[url]")
   .replaceAll("(?<![A-Za-z0-9])/(?:[^\\s\\r\\n]+)","[path]")
   .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}","[contact]")
   .replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]","");
  return s.substring(0,Math.min(cap,s.length()));
 }
 /** S3/API receive only known numerical pipeline diagnostics; never arbitrary traceback/source text. */
 public static String excerpt(String text){
  if(text==null)return null;
  // Deliberately return trusted fixed codes only: traceback text can contain credentials or prompts.
  var kept=new LinkedHashSet<String>();
  for(String code:List.of("POSTPROCESS_CLIP","CUSTOM_MEASUREMENT_AMBIGUOUS","GRID_STRUCTURE_INVALID","CUSTOM_POSTPROCESS_TIMEOUT","CUSTOM_POSTPROCESS_FAILED"))if(text.contains(code))kept.add(code);
  return String.join("\n",kept);
 }
 public static void preserve(S3Storage storage,CustomGenerationRequest request,Path output,PostprocessFailureException failure){
  try{
   Path dir=output.resolve("failure");Files.createDirectories(dir);try{Files.setPosixFilePermissions(dir,PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}
   writePrivate(dir.resolve("stdout.log"),redact(failure.stdout(),131072));writePrivate(dir.resolve("stderr.log"),redact(failure.stderr(),131072));
   var trace=new java.io.StringWriter();failure.printStackTrace(new java.io.PrintWriter(trace));writePrivate(dir.resolve("cause.log"),redact(trace.toString(),131072));
   var report=new LinkedHashMap<String,Object>();report.put("version","CUSTOM_FAILURE_V1");report.put("setId",request.requestId().toString());report.put("stage","POSTPROCESS");report.put("errorCode",failure.errorCode());report.put("failedAt",Instant.now().toString());report.put("stdoutExcerpt",excerpt(failure.stdout()));report.put("stderrExcerpt",excerpt(failure.stderr()));report.put("truncated",failure.stdout().length()>4000||failure.stderr().length()>4000);
   Path file=dir.resolve("failure.json");writePrivate(file,new ObjectMapper().writeValueAsString(report));
   try{storage.upload(key(request.requestId()),file,"application/json");}catch(Exception unavailable){failure.addSuppressed(unavailable);} // DB marker must still be written by worker.
  }catch(Exception unavailable){failure.addSuppressed(unavailable);}
 }
 private static void writePrivate(Path file,String value)throws Exception{Files.writeString(file,value==null?"":value,StandardOpenOption.CREATE_NEW);try{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
}
