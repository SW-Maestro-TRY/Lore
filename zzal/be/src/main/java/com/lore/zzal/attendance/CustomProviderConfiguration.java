package com.lore.zzal.attendance;
import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.client.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
/** Opt-in wiring only. The current LOCAL_FAKE runtime never enables this configuration. */
@Configuration
@ConditionalOnProperty(name="app.zzal.custom.enabled",havingValue="true")
public class CustomProviderConfiguration {
 @Bean public RealCustomBundleService realCustomBundleService(Environment env,CustomPlanningService planning,BundleJobWorker worker,TextClient unusedSharedText,ImageClient images,PostProcessor post,S3Storage storage,CustomBundlePublicationService publication){
  if(!env.getProperty("app.zzal.generation.real",Boolean.class,false)||!env.getProperty("app.zzal.generation.real-postprocess",Boolean.class,false)||!env.getProperty("app.zzal.attendance.cli-enabled",Boolean.class,false))throw new IllegalStateException("Real custom execution requires explicit real-generation, real-postprocess and worker execution guards");
  String plannerModel=required(env,"app.zzal.custom.planner-model"),provider=required(env,"app.zzal.custom.provider");
  CustomGridGenerator grid=switch(provider){
   case "api"->new ImageClientCustomGridGenerator(images,required(env,"app.zzal.custom.image-model"),required(env,"app.zzal.custom.image-size"));
   case "cli"->new CodexCustomGridGenerator(storage,required(env,"app.zzal.custom.codex-bin"),required(env,"app.zzal.custom.codex-worker-model"),java.nio.file.Path.of(System.getProperty("user.home"),".codex"),env.getProperty("app.zzal.custom.cli-medium-capability-verified",Boolean.class,false));
   default->throw new IllegalArgumentException("Unknown custom provider; no automatic fallback");
  };
  var executor=new CustomGridGenerationExecutor(grid,post,storage,required(env,"app.zzal.custom.postprocess-version"),env.getProperty("app.zzal.python.bin","python3"));
  var planner = new CustomPlannerTextClient(
      new com.lore.zzal.chat.line.OpenAiChatLineClient(required(env,"app.zzal.openai.api-key"),
          env.getProperty("app.zzal.custom.planner-reasoning-effort","none"),
          env.getProperty("app.zzal.custom.planner-max-completion-tokens",Integer.class,6144)),
      java.time.Duration.ofSeconds(env.getProperty("app.zzal.custom.planner-timeout-seconds",Integer.class,90)));
  return new RealCustomBundleService(planning,worker,executor,planner,plannerModel,java.util.Objects.requireNonNull(publication,"Production custom publisher required"));
 }
 private static String required(Environment env,String name){String value=env.getProperty(name);if(value==null||value.isBlank())throw new IllegalStateException("Missing explicit custom setting: "+name);return value;}
}
