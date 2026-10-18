package com.lore.zzal.attendance;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Explicit worker invocation only. No scheduler, recovery retry, shell expansion or paid-client dependency. */
public class BundleCommandAdapter {
 public static final String SHEET_REFERENCE_POLICY="CUSTOM_SUBJECT_SHEET_REFERENCE_V4";
 public static boolean subjectPreservingPolicy(String policy){return SHARED_SUBJECT_POLICY.equals(policy)||SHEET_REFERENCE_POLICY.equals(policy);}
 public static final String SHARED_SUBJECT_POLICY="CUSTOM_SUBJECT_PRESERVING_LONG_TERM_V3";
 public static boolean sharedPolicy(String policy){return SHARED_MEMORY_POLICY.equals(policy)||subjectPreservingPolicy(policy);}
 public static final String SHARED_MEMORY_POLICY="CUSTOM_PERSONA_IDENTITY_LONG_TERM_V2";
 public static final String FIRST_SET_POLICY="FIRST_SET_PERSONA_IDENTITY_V1";
 public record FirstSetInput(String json,String sourceSnapshotHash,String generatorInputHash){}
 /** Frozen database bytes are never rewritten. Old first-set snapshots may contain
  * memory: only this allowlist crosses the CLI boundary. Hashes name different bytes. */
 static FirstSetInput firstSetInput(String frozenSnapshot) throws Exception {
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var source=mapper.readTree(frozenSnapshot);
  if(!source.isObject())throw new IllegalArgumentException("Expected first-set source object");
  boolean shared=sharedPolicy(source.path("materialPolicy").asText());
  var input=mapper.createObjectNode();input.put("materialPolicy",shared?source.path("materialPolicy").asText():FIRST_SET_POLICY);
  for(String key:List.of("schemaVersion","name","legacySource","persona","personaSourceKind","personaSourceHash","identity","sourceImageKey","identityHash"))
   if(source.has(key))input.set(key,source.get(key));
  if(SHEET_REFERENCE_POLICY.equals(source.path("materialPolicy").asText()))for(String key:List.of("sheetImageKey","baseReferenceRound","appearancePipelineVersion","anchorsKey","customGridTemplateVersion","customGridTemplateHash"))if(source.has(key))input.set(key,source.get(key));
  if(shared && source.has("memory"))input.set("memory",source.get("memory"));
  String value=mapper.writeValueAsString(input);
  return new FirstSetInput(value,AttendanceService.hash(frozenSnapshot),AttendanceService.hash(value));
 }
 static FirstSetInput writeFirstSetInput(String frozenSnapshot,Path output)throws Exception {
  FirstSetInput input=firstSetInput(frozenSnapshot);
  Files.createDirectories(output);
  // Sidecar provenance distinguishes the immutable DB snapshot hash from the exact CLI input hash.
  Files.writeString(output.resolve("input-provenance.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
   Map.of("materialPolicy",new com.fasterxml.jackson.databind.ObjectMapper().readTree(input.json()).path("materialPolicy").asText(),"sourceSnapshotHash",input.sourceSnapshotHash(),"generatorInputHash",input.generatorInputHash())));
  Files.writeString(output.resolve("input.json"),input.json());
  return input;
 }
 public String executeFirstSet(List<String> argv,String frozenSnapshot,Path output,Duration timeout)throws Exception {
  FirstSetInput input=writeFirstSetInput(frozenSnapshot,output);
  return execute(argv,input.json(),output,timeout);
 }

 static FirstSetInput plannedInput(String frozenSnapshot,String plan)throws Exception {
  if(plan==null)return firstSetInput(frozenSnapshot);
  var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
  var input=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(firstSetInput(frozenSnapshot).json());
  var selected=mapper.readTree(plan).path("selectedSources");
  var ids=new java.util.HashSet<Long>();selected.forEach(s->ids.add(s.path("factId").asLong()));
  var candidates=mapper.createArrayNode();input.path("memory").path("candidates").forEach(c->{if(ids.contains(c.path("id").asLong()))candidates.add(c);});
  // Only planned sources cross the generation boundary; never all retrieved candidates.
  if(input.get("memory") instanceof com.fasterxml.jackson.databind.node.ObjectNode memory)memory.set("candidates",candidates);
  input.set("selectedSources",selected);input.put("sourcePlanHash",AttendanceService.hash(plan));
  String bytes=mapper.writeValueAsString(input);
  return new FirstSetInput(bytes,AttendanceService.hash(frozenSnapshot),AttendanceService.hash(bytes));
 }
 public String executePlanned(List<String> argv,String frozenSnapshot,String plan,Path output,Duration timeout)throws Exception {
  if(plan==null)return executeFirstSet(argv,frozenSnapshot,output,timeout);
  var input=plannedInput(frozenSnapshot,plan);Files.createDirectories(output);
  Files.writeString(output.resolve("input-provenance.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("materialPolicy",new com.fasterxml.jackson.databind.ObjectMapper().readTree(input.json()).path("materialPolicy").asText(),"sourceSnapshotHash",input.sourceSnapshotHash(),"sourcePlanHash",AttendanceService.hash(plan),"generatorInputHash",input.generatorInputHash())));
  return execute(argv,input.json(),output,timeout);
 }

 public String execute(List<String> argv,String snapshot,Path output,Duration timeout) throws Exception {
  if(argv.isEmpty())throw new IllegalArgumentException("Missing command");
  Files.createDirectories(output);
  Path input=output.resolve("input.json");Files.writeString(input,snapshot);
  List<String> command=new ArrayList<>(argv);command.add(input.toString());command.add(output.toString());
  Process process=new ProcessBuilder(command).redirectError(output.resolve("stderr.log").toFile()).redirectOutput(output.resolve("stdout.log").toFile()).start();
  if(!process.waitFor(timeout.toMillis(),TimeUnit.MILLISECONDS)){process.destroyForcibly();throw new IllegalStateException("AMBIGUOUS_TIMEOUT");}
  if(process.exitValue()!=0)throw new IllegalStateException("CLI_EXIT_"+process.exitValue());
  return Files.readString(output.resolve("manifest.json"));
 }
}
