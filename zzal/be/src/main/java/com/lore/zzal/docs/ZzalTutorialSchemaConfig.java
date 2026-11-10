package com.lore.zzal.docs;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ComposedSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
/** Correct only the owned pet response after Springdoc has resolved and reused its components. */
@Configuration(proxyBeanMethods=false)
public class ZzalTutorialSchemaConfig {
 @Bean public GlobalOpenApiCustomizer zzalTutorialNullableSchema(){return api->{
  if(api.getPaths()==null||api.getComponents()==null||api.getComponents().getSchemas()==null)return;
  var path=api.getPaths().get("/api/zzal/v1/me/pets/{petId}");
  if(path==null||path.getGet()==null||path.getGet().getResponses()==null)return;
  var response=path.getGet().getResponses().get("200");if(response==null||response.getContent()==null)return;
  for(var media:response.getContent().values()){
   var envelope=resolve(api,media.getSchema());if(envelope==null||envelope.getProperties()==null)continue;
   var detail=resolve(api,(Schema<?>)envelope.getProperties().get("data"));
   if(detail==null||detail.getProperties()==null||!detail.getProperties().keySet().containsAll(List.of("petId","clock","firstIntro","tutorial")))continue;
   var old=(Schema<?>)detail.getProperties().get("tutorial");if(old==null||old.get$ref()==null)continue;
   var tutorial=resolve(api,old);if(tutorial==null||tutorial.getProperties()==null||!tutorial.getProperties().keySet().containsAll(List.of("active","step","steps")))continue;
   var union=new ComposedSchema();union.setDescription(old.getDescription());
   union.setAnyOf(List.of(new Schema<>().$ref(old.get$ref()),new io.swagger.v3.oas.models.media.JsonSchema().types(java.util.Set.of("null"))));
   detail.getProperties().put("tutorial",union);
  }
 };}
 private static Schema<?> resolve(OpenAPI api,Schema<?> schema){
  for(int depth=0;schema!=null&&schema.get$ref()!=null&&depth<8;depth++){
   String ref=schema.get$ref(),prefix="#/components/schemas/";if(!ref.startsWith(prefix))return null;
   schema=api.getComponents().getSchemas().get(ref.substring(prefix.length()));
  }
  return schema!=null&&schema.get$ref()==null?schema:null;
 }
}
