package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.PipelineScripts;
import com.lore.zzal.motion.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Optional operator review only. Missing historical basic assets never invalidate the eight reward assets. */
public final class CustomReviewSequence {
 public static final String VERSION="CUSTOM_SEQUENCE_V1";
 public static final int MAX_MANIFEST_BYTES=262144,MAX_PNG_BYTES=8*1024*1024,MAX_SIDE=2048;
 public static final List<String> BASIC=MotionCatalog.ALL.stream().filter(m->m.layer()==MotionLayer.BASIC_1).map(MotionSpec::key).toList();
 private static final ObjectMapper JSON=new ObjectMapper();
 private CustomReviewSequence(){}
 public record Frame(int index,String group,int motionOrder,String slot,int frameIndex,String imageKey,String sha256,String sourceImageKey,String sourceSha256,int durationMs,int width,int height,List<Integer> alphaBounds,String rgbaSHA256){}
 public record Sequence(String version,String status,String reasonCode,Integer baseRound,Double K,Double Hw,JsonNode baseAnchors,JsonNode customAnchors,List<Frame> frames){}
 public static Sequence unavailable(String status,String code){return new Sequence(VERSION,status,code,null,null,null,null,null,List.of());}
 private static ObjectNode incomplete(String code){ObjectNode n=JSON.createObjectNode();n.put("version",VERSION);n.put("status","INCOMPLETE");n.put("reasonCode",code);return n;}
 public static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
 public static String reviewPrefix(String prefix){return prefix+"/review/sequence-v1";}
 public static ObjectNode create(S3Storage storage,CustomGenerationRequest request,CustomPostprocessProfile profile,Path output,String python)throws InterruptedException{
  String prefix="images/zzal/custom/"+request.requestId();Path dir=output.resolve("review-input"),export=output.resolve("review-sequence");
  try{
   var frozen=JSON.readTree(request.frozenSnapshot());long pet=frozen.path("memory").path("petId").asLong();
   if(pet<1||!MotionImageKeys.anchors(pet,profile.baseReferenceRound()).equals(profile.anchorsKey()))return incomplete("SOURCE_REFERENCE_INVALID");
   Files.createDirectories(dir);Path base=dir.resolve("base-anchors.json");var sources=new ArrayList<Map<String,Object>>();
   try{storage.download(profile.anchorsKey(),base);for(int i=0;i<8;i++){String slot=BASIC.get(i),key=MotionImageKeys.basic(pet,profile.baseReferenceRound(),slot);Path target=dir.resolve("basic-"+slot+".webp");storage.download(key,target);sources.add(Map.of("group","BASIC","motionOrder",i+1,"slot",slot,"imageKey",key,"path",target.toString()));}}catch(Exception missing){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Review export cancelled");return incomplete("BASIC_ASSET_UNAVAILABLE");}
   Path custom=output.resolve("anchors.json");JsonNode b=JSON.readTree(Files.readString(base)),c=JSON.readTree(Files.readString(custom));
   anchorView(b,BASIC);anchorView(c,CustomGridPrompt.SLOTS);if(!b.get("K").equals(c.get("K"))||!b.get("Hw").equals(c.get("Hw")))return incomplete("ANCHOR_REFERENCE_MISMATCH");
   for(int i=0;i<8;i++)sources.add(Map.of("group","CUSTOM","motionOrder",i+1,"slot",CustomGridPrompt.SLOTS.get(i),"imageKey",prefix+"/"+CustomGridPrompt.SLOTS.get(i)+".webp","path",output.resolve("motion-"+(i+1)+".webp").toString()));
   var cfg=new LinkedHashMap<String,Object>();cfg.put("sources",sources);cfg.put("setId",request.requestId().toString());cfg.put("reviewPrefix",reviewPrefix(prefix));cfg.put("baseRound",profile.baseReferenceRound());cfg.put("K",b.get("K"));cfg.put("Hw",b.get("Hw"));cfg.put("baseAnchorsKey",profile.anchorsKey());cfg.put("baseAnchorsSHA256",hash(Files.readAllBytes(base)));cfg.put("customAnchorsKey",prefix+"/anchors.json");cfg.put("customAnchorsSHA256",hash(Files.readAllBytes(custom)));
   Path config=dir.resolve("input.json");Files.writeString(config,JSON.writeValueAsString(cfg),StandardOpenOption.CREATE_NEW);var scripts=new PipelineScripts();scripts.extract();Path log=dir.resolve("export.log");
   Process process=new ProcessBuilder(python,scripts.script(profile.appearanceVersion(),"review_sequence.py"),config.toString(),export.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
   boolean done;try{done=process.waitFor(60,TimeUnit.SECONDS);}catch(InterruptedException stop){process.destroyForcibly();Thread.currentThread().interrupt();throw stop;}
   if(!done){process.destroyForcibly();return incomplete("REVIEW_EXPORT_TIMEOUT");}if(process.exitValue()!=0)return incomplete("REVIEW_EXPORT_FAILED");
   byte[] raw=Files.readAllBytes(export.resolve("manifest.json"));if(raw.length>MAX_MANIFEST_BYTES)return incomplete("REVIEW_LIMIT_EXCEEDED");
   JsonNode manifest=JSON.readTree(raw);var check=validate(manifest,pet,profile.baseReferenceRound(),prefix,b,c,Map.of());
   for(var frame:check.frames()){Path file=export.resolve(String.format("frame-%02d.png",frame.index()+1));byte[] png=Files.readAllBytes(file);verifyPng(frame,png);storage.upload(frame.imageKey(),file,"image/png");}
   String key=reviewPrefix(prefix)+"/manifest.json";storage.upload(key,export.resolve("manifest.json"),"application/json");ObjectNode ready=JSON.createObjectNode();ready.put("version",VERSION);ready.put("status","READY");ready.put("manifestKey",key);ready.put("manifestSHA256",hash(raw));return ready;
  }catch(InterruptedException stop){Thread.currentThread().interrupt();throw stop;}catch(Exception optional){if(Thread.currentThread().isInterrupted())throw new InterruptedException("Review export cancelled");return incomplete("REVIEW_EXPORT_FAILED");}
 }
 public static Sequence validate(JsonNode m,long pet,int round,String prefix,JsonNode base,JsonNode custom,Map<String,String> customHashes)throws Exception{
  require(m!=null&&VERSION.equals(m.path("version").asText())&&prefix.equals("images/zzal/custom/"+m.path("setId").asText()),"Sequence identity");require(round==m.path("baseRound").asInt()&&MotionImageKeys.anchors(pet,round).equals(m.path("baseAnchorsKey").asText())&&(prefix+"/anchors.json").equals(m.path("customAnchorsKey").asText()),"Sequence frozen reference");
  require(m.path("alphaBoundsThreshold").asInt(-1)==0&&m.path("frames").isArray()&&m.path("frames").size()==32,"Actual32 RGBA frames required");JsonNode b=anchorView(base,BASIC),c=anchorView(custom,CustomGridPrompt.SLOTS);require(base.get("K").equals(custom.get("K"))&&base.get("Hw").equals(custom.get("Hw"))&&m.get("K").equals(base.get("K"))&&m.get("Hw").equals(base.get("Hw")),"Sequence base scale");
  var frames=new ArrayList<Frame>();
  for(int index=0;index<32;index++){
   JsonNode f=m.path("frames").get(index);boolean basic=index<16;int order=index%16/2+1,ab=index%2;String slot=(basic?BASIC:CustomGridPrompt.SLOTS).get(order-1),key=reviewPrefix(prefix)+String.format("/frame-%02d.png",index+1),source=basic?MotionImageKeys.basic(pet,round,slot):prefix+"/"+slot+".webp";
   require(integer(f,"index",0,31)==index&&(basic?"BASIC":"CUSTOM").equals(f.path("group").asText())&&integer(f,"motionOrder",1,8)==order&&integer(f,"frameIndex",0,1)==ab&&slot.equals(f.path("slot").asText())&&key.equals(f.path("imageKey").asText())&&source.equals(f.path("sourceImageKey").asText()),"Sequence frame mapping");
   int w=integer(f,"width",16,MAX_SIDE),h=integer(f,"height",16,MAX_SIDE);JsonNode anchor=basic?b:c;require(w==anchor.path("canvas").path("w").asInt()&&h==anchor.path("canvas").path("h").asInt(),"Sequence canvas");String sha=f.path("sha256").asText(),sourceHash=f.path("sourceSha256").asText(),rgba=f.path("rgbaSHA256").asText();require(sha.matches("[a-f0-9]{64}")&&sourceHash.matches("[a-f0-9]{64}")&&rgba.matches("[a-f0-9]{64}"),"Sequence hash");if(!basic&&!customHashes.isEmpty())require(sourceHash.equals(customHashes.get(source)),"Sequence custom source hash");
   JsonNode bounds=f.path("alphaBounds");require(bounds.isArray()&&bounds.size()==4,"Sequence bounds");List<Integer> box=new ArrayList<>();for(int i=0;i<4;i++)box.add(numberInt(bounds.get(i),0,i%2==0?w:h));require(box.get(0)<box.get(2)&&box.get(1)<box.get(3),"Sequence empty bounds");frames.add(new Frame(index,basic?"BASIC":"CUSTOM",order,slot,ab,key,sha,source,sourceHash,integer(f,"durationMs",1,60000),w,h,List.copyOf(box),rgba));
  }
  return new Sequence(VERSION,"READY",null,round,base.path("K").asDouble(),base.path("Hw").asDouble(),b,c,List.copyOf(frames));
 }
 public static void verifyPng(Frame frame,byte[] png)throws Exception{
  require(png.length<=MAX_PNG_BYTES&&hash(png).equals(frame.sha256()),"Sequence PNG hash");
  try(var input=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(png))){var readers=javax.imageio.ImageIO.getImageReaders(input);require(readers.hasNext(),"Sequence PNG decoder");var reader=readers.next();try{reader.setInput(input);require("png".equalsIgnoreCase(reader.getFormatName())&&reader.getWidth(0)==frame.width()&&reader.getHeight(0)==frame.height(),"Sequence PNG dimensions");var image=reader.read(0);var digest=java.security.MessageDigest.getInstance("SHA-256");int left=frame.width(),top=frame.height(),right=0,bottom=0;for(int y=0;y<frame.height();y++)for(int x=0;x<frame.width();x++){int pixel=image.getRGB(x,y),a=pixel>>>24;digest.update((byte)(pixel>>>16));digest.update((byte)(pixel>>>8));digest.update((byte)pixel);digest.update((byte)a);if(a>0){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+1);bottom=Math.max(bottom,y+1);}}require(HexFormat.of().formatHex(digest.digest()).equals(frame.rgbaSHA256())&&frame.alphaBounds().equals(List.of(left,top,right,bottom)),"Sequence PNG RGBA/bounds");}finally{reader.dispose();}}
 }
 private static JsonNode anchorView(JsonNode a,List<String> keys){
  require(a!=null&&a.isObject(),"Sequence anchors missing");int w=integer(a.path("canvas"),"w",16,MAX_SIDE),h=integer(a.path("canvas"),"h",16,MAX_SIDE);finite(a.path("K"),1,4096);finite(a.path("Hw"),1,4096);ObjectNode view=JSON.createObjectNode();view.put("version",a.path("version").asText());view.putObject("canvas").put("w",w).put("h",h);view.set("K",a.get("K"));view.set("Hw",a.get("Hw"));ObjectNode poses=view.putObject("poses");
  for(String key:keys){JsonNode pose=a.path("poses").path(key);ObjectNode out=poses.putObject(key);for(String field:List.of("bbox","head_top","head_side","hand_front","feet")){JsonNode point=pose.path(field);require(point.isObject(),"Sequence pose anchor");for(String required:field.equals("bbox")?List.of("x","y","w","h"):field.equals("head_top")?List.of("x","y"):field.equals("feet")?List.of("y","left_x","right_x","center_x"):List.of("y","left_x","right_x"))require(point.has(required),"Sequence required anchor field");ObjectNode p=out.putObject(field);var fields=point.fields();while(fields.hasNext()){var e=fields.next();require(Set.of("x","y","w","h","left_x","right_x","center_x").contains(e.getKey()),"Sequence anchor field");finite(e.getValue(),0,e.getKey().equals("y")||e.getKey().equals("h")?h:w);p.set(e.getKey(),e.getValue());}if(point.has("left_x"))require(point.path("left_x").asDouble()<=point.path("right_x").asDouble(),"Sequence span");}JsonNode box=pose.path("bbox");require(box.path("x").asDouble()+box.path("w").asDouble()<=w&&box.path("y").asDouble()+box.path("h").asDouble()<=h,"Sequence anchor bounds");out.putArray("canvas").add(w).add(h);}
  return view;
 }
 private static int integer(JsonNode n,String field,int low,int high){return numberInt(n.path(field),low,high);}
 private static int numberInt(JsonNode n,int low,int high){require(n!=null&&n.isIntegralNumber()&&n.canConvertToInt()&&n.asInt()>=low&&n.asInt()<=high,"Sequence integer");return n.asInt();}
 private static void finite(JsonNode n,double low,double high){require(n.isNumber()&&Double.isFinite(n.asDouble())&&n.asDouble()>=low&&n.asDouble()<=high,"Sequence coordinate");}
 private static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
}
