package com.lore.zzal.docs;
import com.lore.zzal.it.ZzalIntegrationTest;import com.fasterxml.jackson.databind.JsonNode;import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;import org.springframework.beans.factory.annotation.Autowired;import org.springframework.beans.factory.annotation.Value;import org.springframework.test.annotation.DirtiesContext;import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;import static org.assertj.core.api.Assertions.*;
/** Actual mixed Springdoc, not isolated ModelConverters. */
@ZzalIntegrationTest @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TutorialNullableContractIT {
 @Autowired MockMvc mvc;@Value("${springdoc.api-docs.path}")String docs;
 @Test void petGetTutorialAcceptsAnObjectOrNullUnion()throws Exception{
  var response=mvc.perform(get(docs)).andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(200);
  JsonNode api=new ObjectMapper().readTree(response.getContentAsString());
  var envelope=resolve(api,api.at("/paths/~1api~1zzal~1v1~1me~1pets~1{petId}/get/responses/200/content").elements().next().path("schema"));
  var detail=resolve(api,envelope.path("properties").path("data"));var tutorial=detail.path("properties").path("tutorial");
  assertThat(tutorial.has("$ref")).isFalse();assertThat(tutorial.has("type")).isFalse();assertThat(tutorial.path("anyOf").size()).isEqualTo(2);
  assertThat(tutorial.path("anyOf").get(1).path("type").asText()).isEqualTo("null");
  var object=resolve(api,tutorial.path("anyOf").get(0));assertThat(object.path("type").asText()).isEqualTo("object");assertThat(object.path("properties").has("steps")).isTrue();
 }
 private JsonNode resolve(JsonNode api,JsonNode value){return value.has("$ref")?api.at(value.path("$ref").asText().substring(1)):value;}
}
