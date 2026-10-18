package com.lore.zzal.attendance;
import com.lore.common.s3.S3Storage;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Subscription CLI path reused from the verified Romi runner. Never changes CODEX_HOME or retries. */
public final class CodexCustomGridGenerator implements CustomGridGenerator {
 private final S3Storage storage;private final String command,workerModel;private final Path codexHome;private final boolean mediumCapabilityVerified;
 public CodexCustomGridGenerator(S3Storage storage,String command,String workerModel,Path codexHome,boolean mediumCapabilityVerified){this.storage=storage;this.command=command;this.workerModel=workerModel;this.codexHome=codexHome;this.mediumCapabilityVerified=mediumCapabilityVerified;}
 @Override public void preflight(){if(!mediumCapabilityVerified)throw new IllegalStateException("CLI_MEDIUM_CAPABILITY_UNVERIFIED: no planner or image call allowed");if(command==null||command.isBlank()||workerModel==null||workerModel.isBlank())throw new IllegalStateException("Explicit Codex executable/model required");}
 @Override public Grid generate(CustomGenerationRequest request,String prompt,String referenceKey,String outputKey,Path output)throws Exception {
  preflight();Files.createDirectories(output);Path reference=output.resolve("reference.png");storage.download(referenceKey,reference);Files.writeString(output.resolve("cli-prompt.txt"),prompt);
  String task="Generate exactly ONE square image with the built-in image_gen tool. No subagent, API key, external API, retry, variation or second image call. If unavailable stop. Read cli-prompt.txt and inspect reference.png only. Pass the FULL EXACT prompt unchanged and referenced_image_paths containing only "+reference.toAbsolutePath()+". transparent_background=false. Requested image quality is medium; actual native tool evidence MUST expose quality=medium, otherwise stop and report unsupported without generation. Do not infer quality from prompt wording. Do not edit source/settings/CODEX_HOME or other folders. No network/tool calls except this single image generation. Save returned path in final response; original native result will be audited by the parent process.";
  var args=List.of(command,"exec","--json","-m",workerModel,"-s","workspace-write","--skip-git-repo-check","-C",output.toAbsolutePath().toString(),"--output-last-message",output.resolve("worker-final.txt").toAbsolutePath().toString(),"-");
  var builder=new ProcessBuilder(args).redirectOutput(output.resolve("cli.jsonl").toFile()).redirectError(output.resolve("cli.stderr.log").toFile());
  builder.environment().keySet().removeIf(k->k.contains("API_KEY")||k.startsWith("AWS_")||k.equals("ANTHROPIC_AUTH_TOKEN"));
  var process=builder.start();try(var stdin=process.getOutputStream()){stdin.write(task.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  if(!process.waitFor(Duration.ofMinutes(10).toMillis(),TimeUnit.MILLISECONDS)){process.descendants().forEach(h->h.destroyForcibly());process.destroyForcibly();throw new IllegalStateException("AMBIGUOUS_CLI_TIMEOUT");}
  if(process.exitValue()!=0)throw new IllegalStateException("CLI_EXIT_"+process.exitValue());
  var mapper=new ObjectMapper();String thread=null;
  for(String line:Files.readAllLines(output.resolve("cli.jsonl"))){var event=mapper.readTree(line);if("thread.started".equals(event.path("type").asText())){if(thread!=null)throw new IllegalStateException("Ambiguous CLI thread");thread=event.path("thread_id").asText();}}
  if(thread==null||!thread.matches("[a-zA-Z0-9-]+"))throw new IllegalStateException("Missing own CLI thread");
  final String ownThread=thread;List<Path> logs;try(var stream=Files.walk(codexHome.resolve("sessions"))){logs=stream.filter(p->p.getFileName().toString().endsWith(ownThread+".jsonl")).toList();}
  if(logs.size()!=1||Files.size(logs.get(0))>100_000_000)throw new IllegalStateException("Ambiguous native tool evidence");
  List<JsonNode> calls=new ArrayList<>();for(String line:Files.readAllLines(logs.get(0))){var payload=mapper.readTree(line).path("payload");var item=payload.path("item");if("item_completed".equals(payload.path("type").asText())&&"image_gen.generation".equals(item.path("kind").asText()))calls.add(item);}
  if(calls.size()!=1)throw new IllegalStateException("Expected exactly one native image call");var call=calls.get(0);
  Files.writeString(output.resolve("native-tool-evidence.json"),mapper.writeValueAsString(Map.of("threadId",thread,"nativeToolCalls",calls.size(),"status",call.path("status").asText(),"toolId",call.path("id").asText(),"actualQuality",call.path("quality").asText(),"promptHash",AttendanceService.hash(call.path("revisedPrompt").asText()),"automaticRetries",0)));
  if(!"completed".equals(call.path("status").asText())||!prompt.equals(call.path("revisedPrompt").asText())||!"medium".equals(call.path("quality").asText()))throw new IllegalStateException("Native prompt/quality evidence mismatch; no success receipt");
  if(call.path("model").asText().isBlank())throw new IllegalStateException("Native image model evidence missing");
  Path generated=Path.of(call.path("savedPath").asText()).toRealPath();Path ownRoot=codexHome.resolve("generated_images").resolve(thread).toRealPath();if(!generated.startsWith(ownRoot)||!Files.isRegularFile(generated))throw new IllegalStateException("Image does not belong to own native tool");
  Path sheet=output.resolve("sheet.png");Files.copy(generated,sheet);String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(sheet)));Files.writeString(output.resolve("cli-source.json"),mapper.writeValueAsString(Map.of("sourcePath",generated.toString(),"sha256",hash,"referenceHash",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(reference))))));storage.upload(outputKey,sheet,"image/png");return new Grid(outputKey,"CODEX_SUBSCRIPTION_IMAGE_GEN",call.path("model").asText(),"medium");
 }
}
