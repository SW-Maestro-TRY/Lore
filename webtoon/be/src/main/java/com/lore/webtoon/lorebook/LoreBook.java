package com.lore.webtoon.lorebook;
import com.lore.common.exception.*;
import java.util.*;
/**
 * 생성 입력에 보관하는 설정집 사본. 식별자/경로를 실행 인자로 해석하지 않는다.
 *
 * <p>2층 로어북(orv-experiment 와 같다): 고정 로어북 = 대상마다 하나인 키워드 항목, 그중 세계 항목은 항상 켜짐(always),
 * 진행 상태 = progress 줄 목록(key 가 항목 id 면 그 항목의 「→ 지금:」 덮어쓰기). 글에서 뽑으면 항목이
 * 수십 개가 되므로 개수·글자 상한은 넉넉히 둔다 — 생성에 넣을 양은 하네스가 키워드로 고르며 자른다.
 */
public final class LoreBook {
 private LoreBook() {}
 static final int MAX_ENTRIES=400, MAX_PROGRESS=200, MAX_CHARS=80000;
 static String text(Object value,int max) {
  String s=value==null?"":String.valueOf(value).trim();
  if(s.length()>max) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집 내용이 너무 길어요.");
  return s;
 }
 public static Map<String,Object> clean(Map<String,Object> raw,boolean requireConfirmed) {
  if(raw==null || raw.isEmpty()) return Map.of();
  if(requireConfirmed && !Boolean.TRUE.equals(raw.get("confirmed")))
   throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집을 확인한 뒤 사용해 주세요.");
  if(raw.containsKey("books")) return collection(raw,requireConfirmed);
  var out=new LinkedHashMap<String,Object>();
  out.put("name",text(raw.get("name"),100));
  out.put("source",text(raw.get("source"),30));
  out.put("fixed_settings",text(raw.get("fixed_settings"),8000));
  out.put("progress_state",text(raw.get("progress_state"),8000));
  var entries=new ArrayList<Map<String,Object>>();
  int total=out.get("fixed_settings").toString().length()+out.get("progress_state").toString().length();
  if(raw.get("entries") instanceof List<?> list) {
   if(list.size()>MAX_ENTRIES) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정 항목은 "+MAX_ENTRIES+"개까지 넣을 수 있어요.");
   for(Object item:list) if(item instanceof Map<?,?> e) {
    var row=new LinkedHashMap<String,Object>();
    String id=text(e.get("id"),20);if(!id.isEmpty()) row.put("id",id);
    row.put("title",text(e.get("title"),100));row.put("content",text(e.get("content"),6000));
    row.put("kind",text(e.get("kind"),30));row.put("evidence",text(e.get("evidence"),600));
    row.put("always",Boolean.TRUE.equals(e.get("always")));row.put("enabled",!Boolean.FALSE.equals(e.get("enabled")));
    if(Boolean.TRUE.equals(e.get("named"))) row.put("named",true); // AI 가 지은 세계 이름 — 사람이 알아보고 고칠 수 있게 남긴다
    var keys=new ArrayList<String>();
    if(e.get("keys") instanceof List<?> k) {if(k.size()>40) throw new BusinessException(ErrorCode.INVALID_INPUT,"키워드는 40개까지예요.");for(Object v:k) keys.add(text(v,50));}
    row.put("keys",keys);entries.add(row);total+=row.get("content").toString().length();
   }
  }
  var progress=new ArrayList<Map<String,Object>>();
  if(raw.get("progress") instanceof List<?> rows) {
   if(rows.size()>MAX_PROGRESS) throw new BusinessException(ErrorCode.INVALID_INPUT,"진행 상태는 "+MAX_PROGRESS+"줄까지예요.");
   for(Object item:rows) if(item instanceof Map<?,?> p) {
    String line=text(p.get("text"),1000);if(line.isEmpty()) continue;
    var row=new LinkedHashMap<String,Object>();
    row.put("key",text(p.get("key"),20));row.put("title",text(p.get("title"),100));row.put("text",line);
    progress.add(row);total+=line.length();
   }
  }
  if(total>MAX_CHARS) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집은 전체 "+String.format("%,d",MAX_CHARS)+"자까지 사용할 수 있어요.");
  out.put("entries",entries);out.put("progress",progress);out.put("confirmed",Boolean.TRUE.equals(raw.get("confirmed")));
  return out;
 }
 private static Map<String,Object> collection(Map<String,Object> raw,boolean requireConfirmed) {
  if(!(raw.get("books") instanceof List<?> list) || list.isEmpty() || list.size()>24)
   throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집 목록을 확인해 주세요.");
  var books=new ArrayList<Map<String,Object>>();var entries=new ArrayList<Map<String,Object>>();
  var names=new ArrayList<String>();var fixed=new ArrayList<String>();var progress=new ArrayList<String>();var rows=new ArrayList<Map<String,Object>>();
  int total=0;
  for(Object item:list) {
   if(!(item instanceof Map<?,?> map)||map.containsKey("books")) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집 목록을 확인해 주세요.");
   var input=new LinkedHashMap<String,Object>();map.forEach((k,v)->input.put(String.valueOf(k),v));
   var book=clean(input,requireConfirmed);books.add(book);String name=String.valueOf(book.getOrDefault("name",""));names.add(name);
   for(String field:List.of("fixed_settings","progress_state")) {
    String value=String.valueOf(book.getOrDefault(field,""));
    if(!value.isBlank()) {String section="["+name+"]\n"+value;(field.equals("fixed_settings")?fixed:progress).add(section);}
   }
   for(Object r:(List<?>)book.getOrDefault("progress",List.of())) {
    @SuppressWarnings("unchecked") var row=(Map<String,Object>)r;rows.add(row);total+=String.valueOf(row.get("text")).length();
   }
   for(Object e:(List<?>)book.getOrDefault("entries",List.of())) {
    @SuppressWarnings("unchecked") var entry=(Map<String,Object>)e;entries.add(entry);total+=String.valueOf(entry.get("content")).length();
   }
  }
  total+=String.join("\n\n",fixed).length()+String.join("\n\n",progress).length();
  if(total>MAX_CHARS) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집은 합쳐서 "+String.format("%,d",MAX_CHARS)+"자까지예요.");
  var out=new LinkedHashMap<String,Object>();out.put("books",books);out.put("source","collection");out.put("name",String.join(" · ",names));
  out.put("fixed_settings",String.join("\n\n",fixed));out.put("progress_state",String.join("\n\n",progress));out.put("entries",entries);out.put("progress",rows);
  out.put("confirmed",books.stream().allMatch(b->Boolean.TRUE.equals(b.get("confirmed"))));return out;
 }

}
