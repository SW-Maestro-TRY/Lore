package com.lore.zzal.attendance;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.client.PostProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
/** One generated grid -> existing postprocessor -> local verified eight -> unchanged worker completion. */
public final class CustomGridGenerationExecutor implements GenerationExecutor {
 private final CustomGridGenerator generator;private final PostProcessor post;private final S3Storage storage;private final String pipeline;private final String python;
 public CustomGridGenerationExecutor(CustomGridGenerator generator,PostProcessor post,S3Storage storage,String pipeline,String python){this.generator=generator;this.post=post;this.storage=storage;if(pipeline==null||pipeline.isBlank())throw new IllegalArgumentException("Explicit postprocess version required");this.pipeline=pipeline;this.python=python;}
 @Override public void preflight()throws Exception{generator.preflight();new BundleAssetVerifier(python).preflight();}
 @Override public String execute(CustomGenerationRequest request,Path output)throws Exception {
  preflight();Files.createDirectories(output);var mapper=new ObjectMapper();var source=mapper.readTree(request.frozenSnapshot());var profile=CustomPostprocessProfile.from(source,pipeline);String ref=profile.sheetImageKey();
  var render=CustomGridPrompt.render(request);String prefix="images/zzal/custom/"+request.requestId();
  Path started=output.resolve("generation-started.json");Files.writeString(started,mapper.writeValueAsString(Map.of("requestId",request.requestId().toString(),"pipelineVersion",request.pipelineVersion(),"quality",request.quality(),"snapshotHash",request.snapshotHash(),"sourcePlanHash",request.sourcePlanHash(),"postprocessVersion",pipeline)),StandardOpenOption.CREATE_NEW);
  Files.writeString(output.resolve("prompt.txt"),render.prompt());Files.writeString(output.resolve("generation-request.json"),mapper.writeValueAsString(request));
  CustomGridGenerator.Grid grid=null;
  try(var session=post.open(prefix,pipeline)){
   profile.configurePlan(session,request.frozenPlan());
   profile.seed(session); // frozen base anchors must be available before the image call
   grid=generator.generate(request,render.prompt(),ref,prefix+"/grid.png",output);
   Files.writeString(output.resolve("provider-result.json"),mapper.writeValueAsString(grid)); // preserve returned cost; no invented zero/GenJob ID
   if(!"medium".equals(grid.actualQuality())||grid.provider()==null||grid.model()==null)throw new IllegalStateException("Provider quality evidence missing");
   profile.split(session,grid.imageKey(),render.postures());
  } catch(com.lore.zzal.generation.client.PostprocessFailureException failure) {
   CustomFailureEvidence.preserve(storage,request,output,failure);throw failure;
  } catch(Exception cause) {
   if(grid==null)throw cause; // Provider and pre-image failures are outside this postprocess queue.
   var failure=new com.lore.zzal.generation.client.PostprocessFailureException(com.lore.zzal.generation.client.PostprocessFailureException.Code.EXECUTION,"","",cause);
   CustomFailureEvidence.preserve(storage,request,output,failure);throw failure;
  }
  try {
  List<BundleAssetVerifier.Input> assets=new ArrayList<>();var verifier=new BundleAssetVerifier(python);
  for(int i=0;i<8;i++){String key=prefix+"/"+CustomGridPrompt.SLOTS.get(i)+".webp",local="motion-"+(i+1)+".webp";Path target=output.resolve(local);storage.download(key,target);var asset=new BundleAssetVerifier.Input(i+1,local,key,java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(target))),"UNREVIEWED",false);verifier.verify(asset,output);assets.add(asset);}
  Path anchors=output.resolve("anchors.json");storage.download(prefix+"/anchors.json",anchors);
  List<Map<String,Object>> resources=new ArrayList<>();
  for(var motion:request.frozenPlan().motions().stream().sorted(java.util.Comparator.comparingInt(CustomMotionPlan.Motion::order)).toList()){var resource=new LinkedHashMap<String,Object>();resource.put("motionOrder",motion.order());resource.put("resourceSlotKey",CustomGridPrompt.SLOTS.get(motion.order()-1));resource.put("anchorPoseKey",CustomGridPrompt.SLOTS.get(motion.order()-1));resource.put("semantic",motion.semantic());resource.put("situations",motion.situations());resource.put("albumOnly",motion.albumOnly());resource.put("embeddedProps",!motion.props().isEmpty());resource.put("suppressRoomProps",true);resources.add(resource);}
  Files.writeString(output.resolve("resource-metadata.json"),mapper.writeValueAsString(Map.of("planVersion",request.frozenPlan().version(),"setId",request.requestId().toString(),"baseReferenceRound",profile.baseReferenceRound(),"baseAnchorsKey",profile.anchorsKey(),"anchorsKey",prefix+"/anchors.json","appearancePipelineVersion",profile.appearanceVersion(),"resources",resources)));
  var metadataNode=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readString(output.resolve("resource-metadata.json")));
  var anchorNode=mapper.readTree(Files.readString(anchors));
  if(anchorNode.has("customTransform")){metadataNode.set("customTransform",anchorNode.get("customTransform"));metadataNode.put("gridImageKey",grid.imageKey());Files.writeString(output.resolve("resource-metadata.json"),mapper.writeValueAsString(metadataNode));}
  metadataNode.set("reviewSequence",CustomReviewSequence.create(storage,request,profile,output,python));
  Files.writeString(output.resolve("resource-metadata.json"),mapper.writeValueAsString(metadataNode));
  Path metadata=output.resolve("resource-metadata.json");
  storage.upload(prefix+"/resource-metadata.json",metadata,"application/json");
  var publication=new BundleJobWorker.PublicationEvidence("CUSTOM_PUBLICATION_V1",prefix+"/resource-metadata.json",AttendanceService.hash(Files.readString(metadata)),prefix+"/anchors.json",AttendanceService.hash(Files.readString(anchors)));
  var receipt=new BundleJobWorker.Manifest(request.requestId().toString(),assets,request.frozenPlan().selectedSources(),request.requestId().toString(),request.pipelineVersion(),request.quality(),grid.actualQuality(),grid.provider(),grid.model(),publication);
  String bytes=mapper.writeValueAsString(receipt);Files.writeString(output.resolve("manifest.json"),bytes,StandardOpenOption.CREATE_NEW);storage.upload(prefix+"/manifest.json",output.resolve("manifest.json"),"application/json");return bytes;
  }catch(Exception cause){
   var failure=new com.lore.zzal.generation.client.PostprocessFailureException(com.lore.zzal.generation.client.PostprocessFailureException.Code.EXECUTION,"","",cause);
   CustomFailureEvidence.preserve(storage,request,output,failure);throw failure;
  }
 }
}
