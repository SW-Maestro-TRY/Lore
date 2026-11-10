package com.lore.zzal.generation.client;
import com.fasterxml.jackson.databind.*;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.attendance.*;
import com.lore.zzal.generation.PipelineScripts;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CustomPlanWiringTest {
 String plan()throws Exception{String rel="zzal/custom-body-romi.json";var in=getClass().getResourceAsStream("/"+rel);var json=new ObjectMapper();var fixture=in==null?json.readTree(Files.readString(Path.of("zzal/be/src/test/resources/"+rel))):json.readTree(in);return json.writeValueAsString(json.treeToValue(fixture.get("plan"),CustomMotionPlan.class));}
 @Test void unsupportedProcessorCannotSilentlyIgnoreFrozenPlan()throws Exception{var s=new PostProcessor.Session(){public void split(String a,List<String>b){}public void split(String a,List<String>b,String c){}public void close(){}};assertThatThrownBy(()->s.customMotionPlan(plan())).isInstanceOf(UnsupportedOperationException.class);}
 @Test void actualCustomSessionRequiresAndPassesExactPlanAndCoarsePostures()throws Exception{
  var storage=mock(S3Storage.class);var scripts=mock(PipelineScripts.class);when(scripts.script(anyString(),anyString())).thenAnswer(i->i.getArgument(1));String frozen=plan();
  class Capture extends PythonPostProcessor {boolean called;Capture(){super(storage,scripts,"unused",1,v->CustomGridPrompt.SLOTS);}protected void exec(String script,Path grid,Path out,List<String>args,Path log)throws java.io.IOException{called=true;assertThat(script).isEqualTo("custom_post.py");assertThat(args).contains("--motion-plan","--postures").doesNotContain("--normalize");assertThat(Files.readString(Path.of(args.get(args.indexOf("--motion-plan")+1)))).isEqualTo(frozen);for(String key:CustomGridPrompt.SLOTS)Files.writeString(out.resolve(key+".webp"),"test");Files.writeString(out.resolve("anchors.json"),"{}");}}
  var processor=new Capture();var session=processor.open("images/zzal/custom/test","v1");
  try{assertThatThrownBy(()->session.split("grid",CustomGridPrompt.SLOTS,"eat_rice=standing",2)).hasMessage("Frozen custom motion plan required");assertThat(processor.called).isFalse();session.customMotionPlan(frozen);session.split("grid",CustomGridPrompt.SLOTS,"eat_rice=standing",2);assertThat(processor.called).isTrue();}finally{session.close();}
 }
 @Test void measurementFailureHasTypedAdminCodeAndPublicSafeExcerpt(){var failure=new PostprocessFailureException(PostprocessFailureException.Code.MEASUREMENT_AMBIGUOUS,"CUSTOM_MEASUREMENT_AMBIGUOUS secret-token","",new Exception("measurement"));assertThat(failure.errorCode()).isEqualTo("CUSTOM_POSTPROCESS_MEASUREMENT_AMBIGUOUS");assertThat(CustomFailureEvidence.excerpt(failure.stdout())).isEqualTo("CUSTOM_MEASUREMENT_AMBIGUOUS");}
}
