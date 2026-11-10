package com.lore.webtoon.lorebook;
import com.lore.webtoon.WebtoonApi;
import com.lore.webtoon.credit.CreditGate;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping(WebtoonApi.V1+"/lorebooks")
public class LorebookController {
 private final LorebookService service;
 private final PrivateLorebookService library;
 public LorebookController(LorebookService s,PrivateLorebookService library){service=s;this.library=library;}
 @GetMapping public Map<String,Object> list(){
  Long user=CreditGate.currentUser();var result=new java.util.LinkedHashMap<>(service.catalog(user));
  result.put("personal_books",user==null?java.util.List.of():library.list(user));return result;
 }
 @PostMapping("/mine") public Map<String,Object> create(@RequestBody Map<String,Object> body){return library.create(CreditGate.currentUser(),body);}
 @GetMapping("/mine/{id}") public Map<String,Object> mine(@PathVariable String id){return library.read(id,CreditGate.currentUser());}
 @PutMapping("/mine/{id}") public Map<String,Object> update(@PathVariable String id,@RequestBody Map<String,Object> body){return library.update(id,CreditGate.currentUser(),body);}
 @DeleteMapping("/mine/{id}") public void delete(@PathVariable String id){library.delete(id,CreditGate.currentUser());}
 @PostMapping("/extract") public Map<String,Object> extract(@RequestBody LorebookService.ExtractForm form){return service.extract(CreditGate.currentUser(),form);}
 @GetMapping("/extract/{id}") public Map<String,Object> read(@PathVariable String id){return service.view(id,CreditGate.currentUser());}
}
