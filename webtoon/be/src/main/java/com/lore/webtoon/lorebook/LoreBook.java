package com.lore.webtoon.lorebook;
import com.lore.common.exception.*;
import java.util.*;
/** 생성 입력에 보관하는 설정집 사본. 식별자/경로를 실행 인자로 해석하지 않는다. */
public final class LoreBook {
 private LoreBook() {}
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
   if(list.size()>24) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정 항목은 24개까지 넣을 수 있어요.");
   for(Object item:list) if(item instanceof Map<?,?> e) {
    var row=new LinkedHashMap<String,Object>();
    row.put("title",text(e.get("title"),100));row.put("content",text(e.get("content"),2000));
    row.put("kind",text(e.get("kind"),30));row.put("evidence",text(e.get("evidence"),600));
    row.put("always",Boolean.TRUE.equals(e.get("always")));row.put("enabled",!Boolean.FALSE.equals(e.get("enabled")));
    var keys=new ArrayList<String>();
    if(e.get("keys") instanceof List<?> k) {if(k.size()>12) throw new BusinessException(ErrorCode.INVALID_INPUT,"키워드는 12개까지예요.");for(Object v:k) keys.add(text(v,50));}
    row.put("keys",keys);entries.add(row);total+=row.get("content").toString().length();
   }
  }
  if(total>16000) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집은 전체 16,000자까지 사용할 수 있어요.");
  out.put("entries",entries);out.put("confirmed",Boolean.TRUE.equals(raw.get("confirmed")));
  return out;
 }
 private static Map<String,Object> collection(Map<String,Object> raw,boolean requireConfirmed) {
  if(!(raw.get("books") instanceof List<?> list) || list.isEmpty() || list.size()>24)
   throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집 목록을 확인해 주세요.");
  var books=new ArrayList<Map<String,Object>>();var entries=new ArrayList<Map<String,Object>>();
  var names=new ArrayList<String>();var fixed=new ArrayList<String>();var progress=new ArrayList<String>();
  int total=0;
  for(Object item:list) {
   if(!(item instanceof Map<?,?> map)||map.containsKey("books")) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집 목록을 확인해 주세요.");
   var input=new LinkedHashMap<String,Object>();map.forEach((k,v)->input.put(String.valueOf(k),v));
   var book=clean(input,requireConfirmed);books.add(book);String name=String.valueOf(book.getOrDefault("name",""));names.add(name);
   for(String field:List.of("fixed_settings","progress_state")) {
    String value=String.valueOf(book.getOrDefault(field,""));
    if(!value.isBlank()) {String section="["+name+"]\n"+value;(field.equals("fixed_settings")?fixed:progress).add(section);}
   }
   for(Object e:(List<?>)book.getOrDefault("entries",List.of())) {
    @SuppressWarnings("unchecked") var entry=(Map<String,Object>)e;entries.add(entry);total+=String.valueOf(entry.get("content")).length();
   }
  }
  total+=String.join("\n\n",fixed).length()+String.join("\n\n",progress).length();
  if(total>16000) throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집은 합쳐서 16,000자까지예요.");
  var out=new LinkedHashMap<String,Object>();out.put("books",books);out.put("source","collection");out.put("name",String.join(" · ",names));
  out.put("fixed_settings",String.join("\n\n",fixed));out.put("progress_state",String.join("\n\n",progress));out.put("entries",entries);
  out.put("confirmed",books.stream().allMatch(b->Boolean.TRUE.equals(b.get("confirmed"))));return out;
 }

}
