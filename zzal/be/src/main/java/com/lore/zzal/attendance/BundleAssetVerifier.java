package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Decode every frame locally before recording PASS. Missing decoder is a hard failure. */
public class BundleAssetVerifier {
 private final String python;
 public BundleAssetVerifier(String python){this.python=python;}
 public record Input(int motionOrder,String localFile,String assetKey,String sha256,String humanReview,boolean published){}
 public void preflight() throws Exception {run("from PIL import Image",List.of());}
 public void verify(Input input,Path output) throws Exception {
  if(input.motionOrder()<1||input.motionOrder()>8||input.assetKey()==null||!input.assetKey().startsWith("images/")||input.assetKey().contains(".."))throw new IllegalArgumentException("Invalid asset identity");
  if(!Set.of("UNREVIEWED","APPROVED","REJECTED").contains(input.humanReview()))throw new IllegalArgumentException("Invalid review");
  Path path=output.resolve(input.localFile()).toRealPath();if(!path.startsWith(output.toRealPath())||!Files.isRegularFile(path))throw new IllegalArgumentException("Asset outside output");
  String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
  if(!actual.equals(input.sha256()))throw new IllegalArgumentException("Asset hash mismatch");
  run("from PIL import Image,ImageSequence\nimport sys\nim=Image.open(sys.argv[1])\nassert im.format=='WEBP' and im.n_frames>=2\nassert 16<=im.width<=4096 and 16<=im.height<=4096\nfor f in ImageSequence.Iterator(im):\n f=f.convert('RGBA');f.load();assert f.getchannel('A').getbbox() is not None\n assert any(a!=b for a,b in f.convert('RGB').getextrema())",List.of(path.toString()));
 }
 public void verifyCanvas(Input input,Path output,int width,int height)throws Exception{
  verify(input,output);
  if(width<1||height<1)throw new IllegalArgumentException("Positive anchor canvas required");
  run("from PIL import Image\nimport sys\nim=Image.open(sys.argv[1]);assert im.size==(int(sys.argv[2]),int(sys.argv[3]))",List.of(output.resolve(input.localFile()).toRealPath().toString(),Integer.toString(width),Integer.toString(height)));
 }
 public void verifyCustomFrames(Path asset,com.fasterxml.jackson.databind.JsonNode transform,int motionOrder)throws Exception{
  if(motionOrder<1||motionOrder>8)throw new IllegalArgumentException("Invalid motion order");
  Path proof=Files.createTempFile("custom-frame-proof-",".json");
  try{Files.writeString(proof,new ObjectMapper().writeValueAsString(transform.path("frames")));run("from PIL import Image,ImageSequence\nimport sys,json\nim=Image.open(sys.argv[1]);proof=json.load(open(sys.argv[2]));order=int(sys.argv[3]);assert im.n_frames==2\nfor i,frame in enumerate(ImageSequence.Iterator(im)):\n a=frame.convert('RGBA').getchannel('A');p=proof[2*(order-1)+i];assert list(a.getbbox())==p['bbox'];assert sum(n for value,n in enumerate(a.histogram()) if value>0)==p['alphaPixelsAfterPlacement']",List.of(asset.toRealPath().toString(),proof.toString(),Integer.toString(motionOrder)));}
  finally{Files.deleteIfExists(proof);}
 }
 private void run(String script,List<String> args)throws Exception{
  List<String> cmd=new ArrayList<>(List.of(python,"-c",script));cmd.addAll(args);
  Path diagnostics=Files.createTempFile("bundle-check-",".log");
  try {Process process=new ProcessBuilder(cmd).redirectError(diagnostics.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
   if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new IllegalStateException("Decoder timeout");}
   if(process.exitValue()!=0)throw new IllegalArgumentException("Asset decoding or frame checks failed");
  } finally {Files.deleteIfExists(diagnostics);}
 }
}
