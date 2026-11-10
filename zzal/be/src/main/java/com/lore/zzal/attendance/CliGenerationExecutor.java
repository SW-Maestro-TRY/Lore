package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
/** CLI transport only; medium support and actual provider must be attested by its receipt. */
public final class CliGenerationExecutor implements GenerationExecutor {
 private final List<String> argv;
 public CliGenerationExecutor(List<String> argv){this.argv=List.copyOf(argv);if(argv.isEmpty())throw new IllegalArgumentException("Missing CLI command");}
 @Override public String execute(CustomGenerationRequest request,Path output)throws Exception {
  Files.createDirectories(output);
  var mapper=new ObjectMapper();
  var input=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(BundleCommandAdapter.plannedInput(request.frozenSnapshot(),request.frozenSourcePlan()).json());
  input.set("generationRequest",mapper.valueToTree(Map.of("pipelineVersion",request.pipelineVersion(),"requestId",request.requestId().toString(),"quality",request.quality(),"snapshotHash",request.snapshotHash(),"sourcePlanHash",request.sourcePlanHash(),"frozenPlan",request.frozenPlan())));
  String bytes=mapper.writeValueAsString(input);
  Files.writeString(output.resolve("input-provenance.json"),mapper.writeValueAsString(Map.of("sourceSnapshotHash",request.snapshotHash(),"sourcePlanHash",request.sourcePlanHash(),"generatorInputHash",AttendanceService.hash(bytes),"requestedQuality",request.quality(),"requestId",request.requestId().toString(),"pipelineVersion",request.pipelineVersion())));
  return new BundleCommandAdapter().execute(argv,bytes,output,Duration.ofMinutes(10));
 }
}
