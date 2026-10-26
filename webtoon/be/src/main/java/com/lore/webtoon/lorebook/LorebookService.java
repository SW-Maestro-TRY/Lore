package com.lore.webtoon.lorebook;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.lore.common.exception.*;
import com.lore.webtoon.job.HarnessProcess;
import com.lore.webtoon.safety.SafetyGuard;
import com.lore.webtoon.usage.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
@Service
public class LorebookService {
 private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(LorebookService.class);
 private final HarnessProcess harness;private final LoreExtractionRepository rows;private final SafetyGuard safety;
 private final SpendGuard guard;private final UsageService usage;private final int daily;
 private final Semaphore slots=new Semaphore(2);private final ObjectMapper json=new ObjectMapper();
 public LorebookService(HarnessProcess h,LoreExtractionRepository r,SafetyGuard s,SpendGuard g,UsageService u,@Value("${lore.webtoon.lorebook.extract-per-day:3}") int daily) {
  harness=h;rows=r;safety=s;guard=g;usage=u;this.daily=Math.max(0,daily);
 }
 private LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Seoul"));}
 public Map<String,Object> catalog(Long user) {
  var books=new ArrayList<Map<String,Object>>();
  try {
   JsonNode worlds=json.readTree(harness.dir().resolve("worlds.json").toFile()).path("presets");
   JsonNode lore=json.readTree(harness.dir().resolve("lorebook.json").toFile()).path("worlds");
   worlds.fields().forEachRemaining(w->{
    var b=new LinkedHashMap<String,Object>();b.put("id",w.getKey());b.put("name",w.getValue().path("label").asText());b.put("source","preset");
    b.put("fixed_settings",w.getValue().path("text").asText());b.put("progress_state","");b.put("confirmed",false);
    var entries=new ArrayList<Map<String,Object>>();
    for(JsonNode e:lore.path(w.getKey())) entries.add(Map.of("title",e.path("name").asText(),"content",e.path("text").asText(),"kind",e.path("kind").asText(),"keys",json.convertValue(e.path("keys"),List.class),"always",false,"enabled",true,"evidence",""));
    b.put("entries",entries);books.add(b);
   });
  } catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR,"준비된 설정집을 불러오지 못했어요.");}
  return Map.of("books",books,"extract_limit",daily,"extract_left",user==null?0:Math.max(0,daily-rows.used(user,today())));
 }
 public record ExtractForm(String text,Boolean agree_ip,String language){}
 @Transactional public Map<String,Object> extract(Long user,ExtractForm form) {
  if(user==null) throw new BusinessException(ErrorCode.UNAUTHORIZED,"글에서 설정을 정리하려면 로그인해 주세요.");
  if(!Boolean.TRUE.equals(form.agree_ip())) throw new BusinessException(ErrorCode.INVALID_INPUT,"입력한 글의 사용 권리를 확인해 주세요.");
  String text=LoreBook.text(form.text(),20000);
  if(text.length()<30) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정을 찾을 수 있도록 이야기를 30자 이상 적어 주세요.");
  safety.checkText("webtoon-lore-extract",text);
  String blocked=guard.whyBlockedByMoney();if(blocked!=null) throw new BusinessException(ErrorCode.INVALID_INPUT,blocked);
  if(daily==0) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정 정리를 잠시 쉬고 있어요. 직접 적거나 준비된 설정집을 사용해 주세요.");
  if(!slots.tryAcquire()) throw new BusinessException(ErrorCode.INVALID_INPUT,"다른 글을 정리하고 있어요. 잠시 후 다시 시도해 주세요.");
  boolean registered=false;
  try {
   if(rows.reserve(user,today(),daily)==0) throw new BusinessException(ErrorCode.INVALID_INPUT,"오늘 설정 정리 횟수를 모두 사용했어요. 내일 다시 이용해 주세요.");
   String id=UUID.randomUUID().toString().replace("-","");rows.save(new LoreExtraction(id,user));
   String lang=com.lore.webtoon.job.WebtoonLanguage.normalize(form.language());
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
    public void afterCommit(){Thread.ofVirtual().start(()->perform(id,text,lang));}
    public void afterCompletion(int status){if(status!=STATUS_COMMITTED) slots.release();}
   });registered=true;
   return Map.of("id",id,"status","RUNNING");
  } finally {if(!registered) slots.release();}
 }
 private void perform(String id,String source,String lang) {
  Path dir=null;
  try {
   dir=Files.createTempDirectory("lore-extract-");json.writeValue(dir.resolve("input.json").toFile(),Map.of("text",source,"language",lang));
   if(harness.extractLore(dir)!=0) throw new IllegalStateException("extract failed");
   var raw=json.readValue(dir.resolve("result.json").toFile(),new TypeReference<Map<String,Object>>(){});
   var book=LoreBook.clean(raw,false);safety.checkText("webtoon-lore-result",json.writeValueAsString(book));
   rows.finish(id,"DONE",json.writeValueAsString(book),null);
  } catch(Exception e) {rows.finish(id,"ERROR",null,"설정을 정리하지 못했어요. 글은 입력란에 남아 있어요. 준비된 설정집을 고르거나 직접 적을 수 있어요.");}
  finally {
   if(dir!=null) {
    try {usage.ingestMetaFile("lore-extract-"+id,dir.resolve("meta.json"));} catch(Exception error){log.warn("Lore extraction cleanup/accounting failed: {}",id,error);}
    try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);}catch(Exception error){log.warn("Lore extraction cleanup/accounting failed: {}",id,error);}
   }
   slots.release();
  }
 }
 public Map<String,Object> view(String id,Long user) {
  if(user==null) throw new BusinessException(ErrorCode.UNAUTHORIZED);
  var row=rows.findById(id).filter(e->user.equals(e.ownerId)).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
  if(row.status.equals("RUNNING")&&row.createdAt.isBefore(Instant.now().minusSeconds(180))) {
   rows.finish(id,"ERROR",null,"설정 정리가 중단됐어요. 다시 시도해 주세요.");row.status="ERROR";row.error="설정 정리가 중단됐어요. 다시 시도해 주세요.";
  }
  var out=new LinkedHashMap<String,Object>();out.put("id",id);out.put("status",row.status);out.put("error",row.error);
  try {out.put("book",row.resultJson==null?null:json.readValue(row.resultJson,Map.class));}catch(Exception e){throw new BusinessException(ErrorCode.INTERNAL_ERROR);}
  return out;
 }
}
