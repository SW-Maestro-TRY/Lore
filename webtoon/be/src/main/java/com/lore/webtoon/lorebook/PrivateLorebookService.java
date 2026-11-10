package com.lore.webtoon.lorebook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lore.common.exception.*;
import com.lore.webtoon.safety.SafetyGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** 기본 설정집과 별개인 계정별 보관함. 소유자 조건 없는 조회/공유 경로는 제공하지 않는다. */
@Service @Transactional(readOnly=true)
public class PrivateLorebookService {
 private final PrivateLorebookRepository rows;
 private final SafetyGuard safety;
 private final ObjectMapper json=new ObjectMapper();
 public PrivateLorebookService(PrivateLorebookRepository rows,SafetyGuard safety){this.rows=rows;this.safety=safety;}
 private Long requireUser(Long user){if(user==null)throw new BusinessException(ErrorCode.UNAUTHORIZED);return user;}
 private PrivateLorebook owned(String id,Long user){
  return rows.findByIdAndOwnerId(id,requireUser(user)).orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
 }
 public List<Map<String,Object>> list(Long user){
  return rows.findAllByOwnerIdOrderByUpdatedAtDesc(requireUser(user)).stream().map(this::view).toList();
 }
 public Map<String,Object> read(String id,Long user){return view(owned(id,user));}
 @Transactional public Map<String,Object> create(Long user,Map<String,Object> raw){
  Long owner=requireUser(user);
  var row=new PrivateLorebook(UUID.randomUUID().toString().replace("-",""),owner,encode(raw));
  return view(rows.save(row));
 }
 @Transactional public Map<String,Object> update(String id,Long user,Map<String,Object> raw){
  var row=owned(id,user);row.bookJson=encode(raw);row.updatedAt=Instant.now();return view(rows.save(row));
 }
 @Transactional public void delete(String id,Long user){rows.delete(owned(id,user));}
 private String encode(Map<String,Object> raw){
  if(raw==null||raw.containsKey("books"))throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집 한 권씩 저장해 주세요.");
  if(raw.containsKey("public")||raw.containsKey("visibility")||raw.containsKey("is_public")||raw.containsKey("shared"))
   throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집 공개·공유는 지원하지 않아요.");
  var book=LoreBook.clean(raw,true);
  if(String.valueOf(book.get("name")).isBlank())throw new BusinessException(ErrorCode.INVALID_INPUT,"설정집 제목을 입력해 주세요.");
  try {String value=json.writeValueAsString(book);safety.checkText("webtoon-private-lorebook",value);return value;}
  catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BusinessException(ErrorCode.INTERNAL_ERROR);}
 }
 private Map<String,Object> view(PrivateLorebook row){
  try {var out=json.readValue(row.bookJson,new TypeReference<LinkedHashMap<String,Object>>(){});
   out.put("library_id",row.id);out.put("library_owner",row.ownerId);return out;
  }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new BusinessException(ErrorCode.INTERNAL_ERROR);}
 }
}
