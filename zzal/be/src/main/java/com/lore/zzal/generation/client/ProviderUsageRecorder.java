package com.lore.zzal.generation.client;
import com.fasterxml.jackson.databind.JsonNode;
/** Optional sanitized usage-only observer. Never receives headers/body/prompts/credentials. */
@FunctionalInterface
public interface ProviderUsageRecorder {
 ProviderUsageRecorder NO_OP = observation -> {};
 void record(Observation observation) throws Exception;
 enum Outcome { UNKNOWN, RESPONSE }
 record Observation(String modelRequested,String responseModel,int httpStatus,String responseId,String xRequestId,JsonNode usageRaw,Outcome outcome,
                    String callId, Long latencyMs, java.math.BigDecimal estimatedCostUsd, String callStatus) {
  public Observation(String modelRequested,String responseModel,int httpStatus,String responseId,String xRequestId,JsonNode usageRaw,Outcome outcome) {
   this(modelRequested,responseModel,httpStatus,responseId,xRequestId,usageRaw,outcome,null,null,null,null);
  }
  public Observation { modelRequested=safe(modelRequested);responseModel=safe(responseModel);responseId=safe(responseId);xRequestId=safe(xRequestId);usageRaw=safeUsage(usageRaw); }
  static String safe(String value){return value!=null&&value.matches("[A-Za-z0-9._:/-]{1,256}")?value:null;}
 }
 static JsonNode safeUsage(JsonNode node){
  if(node==null||node.isNull()||node.toString().length()>8192)return null;
  if(node.isNumber())return node.deepCopy();
  if(!node.isObject())return null;
  var fields=node.fields();while(fields.hasNext()){var e=fields.next();if(!e.getKey().matches("[a-z][a-z0-9_]{0,63}"))return null;if(!e.getValue().isNull()&&safeUsage(e.getValue())==null)return null;}
  return node.deepCopy();
 }
 static Observation headers(String model,int status,String xRequestId){return new Observation(model,null,status,null,xRequestId,null,Outcome.UNKNOWN);}
 static Observation payload(String model,int status,String xRequestId,JsonNode payload){JsonNode usage=payload.get("usage");return new Observation(model,payload.path("model").asText(null),status,payload.path("id").asText(null),xRequestId,safeUsage(usage),safeUsage(usage)!=null?Outcome.RESPONSE:Outcome.UNKNOWN);}
}
