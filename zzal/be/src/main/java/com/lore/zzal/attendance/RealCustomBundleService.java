package com.lore.zzal.attendance;
import com.lore.zzal.generation.client.TextClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
/** Explicit caller only. Flags, scheduled retries and paid execution are never inferred from HTTP attendance. */
public final class RealCustomBundleService {
 private final CustomPlanningService planning;private final BundleJobWorker worker;private final GenerationExecutor executor;private final TextClient text;private final String plannerModel;private final CustomBundlePublicationService publication;
 public RealCustomBundleService(CustomPlanningService planning,BundleJobWorker worker,GenerationExecutor executor,TextClient text,String plannerModel){this(planning,worker,executor,text,plannerModel,null);}
 public RealCustomBundleService(CustomPlanningService planning,BundleJobWorker worker,GenerationExecutor executor,TextClient text,String plannerModel,CustomBundlePublicationService publication){this.planning=planning;this.worker=worker;this.executor=executor;this.text=text;this.plannerModel=plannerModel;this.publication=publication;}
 public boolean run(long jobId,String claimToken,Path output,Instant now)throws Exception {
  if(publication!=null&&publication.publishGenerated(jobId,now))return true; // Generated recovery is publication-only, with no provider preflight or call.
  executor.preflight(); // capability failure must occur before a potentially paid author call
  Files.createDirectories(output);
  var author=new TextClientCustomAuthor(text,plannerModel,audit->{try{Files.writeString(output.resolve("planner-audit.json"),new ObjectMapper().writeValueAsString(audit),StandardOpenOption.CREATE_NEW);}catch(Exception e){throw new IllegalStateException("Cannot preserve planner provenance",e);}});
  planning.prepare(jobId,author,now);
  boolean generated=worker.run(jobId,claimToken,executor,output);
  if(generated&&publication!=null)publication.publishGenerated(jobId,now);
  return generated;
 }
}
