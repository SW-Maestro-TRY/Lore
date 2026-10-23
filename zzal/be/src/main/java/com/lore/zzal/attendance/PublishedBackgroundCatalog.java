package com.lore.zzal.attendance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.lore.zzal.pet.ZzalPet;
import java.util.*;
/** Deployment-owned publication manifest. Empty by default: unapproved candidates never become ownership. */
@Component
public class PublishedBackgroundCatalog {
 public record Entry(String key,String url,String world,List<String> tags,boolean published,String autoCheck,String humanReview) {
  boolean eligible(){return published && "PASS".equals(autoCheck) && ("UNREVIEWED".equals(humanReview)||"APPROVED".equals(humanReview))
    && key!=null && key.startsWith("images/") && !key.contains("..") && url!=null && (url.startsWith("/")||url.startsWith("https://"));}
 }
 private final List<Entry> entries;
 public PublishedBackgroundCatalog(@Value("${app.zzal.attendance.published-backgrounds:[]}") String json){
  try { entries=List.of(new ObjectMapper().readValue(json,Entry[].class)); }
  catch(Exception e){throw new IllegalArgumentException("Invalid published background manifest",e);}
 }
 /** Stable existing catalog taxonomy: world is major category, tags[1] is middle category. */
 public static String clusterKey(Entry entry){
  return entry.world()==null || entry.tags()==null || entry.tags().size()<2 || entry.tags().get(1)==null
    || entry.tags().get(1).isBlank() ? null : entry.world()+"::"+entry.tags().get(1);
 }
 public Optional<String> clusterOf(String key){return entries.stream().filter(Entry::eligible).filter(e->e.key().equals(key)).map(PublishedBackgroundCatalog::clusterKey).filter(Objects::nonNull).findFirst();}
 public Optional<Entry> select(ZzalPet pet){return select(pet,null,null);}
 /** A reward with a stored initial cluster never falls through to another cluster. */
 public Optional<Entry> select(ZzalPet pet,String excludeKey,String requiredCluster){
  var authored=pet.getAuthoredPersona();
  String source=authored==null||authored.fields()==null?"":authored.fields().stream()
    .filter(Objects::nonNull).filter(f->!f.skipped()).map(PublishedBackgroundCatalog::text)
    .collect(java.util.stream.Collectors.joining(" "));
  String world=authored==null?Objects.toString(pet.getWorld(),"")+" "+Objects.toString(pet.getGenre(),"")
    :authored.fields()==null?"":authored.fields().stream().filter(Objects::nonNull).filter(f->!f.skipped()&&"world".equals(f.fieldId()))
      .map(PublishedBackgroundCatalog::text).collect(java.util.stream.Collectors.joining(" "));
  String legacy=Objects.toString(pet.getNote(),"")+" "+Objects.toString(pet.getWorld(),"")+" "+Objects.toString(pet.getGenre(),"")+" "+(pet.getPersonalities()==null||pet.getPersonalities().isEmpty()?"":pet.getPersonalities());
  final String matching=(authored==null?legacy:source).strip().toLowerCase(Locale.ROOT);
  final String worldSource=world.strip().toLowerCase(Locale.ROOT);
  boolean persona=!matching.isBlank();
  return entries.stream().filter(Entry::eligible).filter(e->!Objects.equals(excludeKey,e.key()))
    .filter(e->requiredCluster==null||requiredCluster.equals(clusterKey(e)))
    .filter(e->persona||"modern_daily".equals(e.world()))
    .filter(e->compatible(e,worldSource.isBlank()?matching:worldSource))
    .sorted(Comparator.<Entry>comparingInt(e->score(e,worldSource,matching,persona)).reversed().thenComparing(Comparator.comparingInt((Entry e)->defaultPreference(e)).reversed()).thenComparing(Entry::key)).findFirst();
 }
 /** Reject only explicit contradictions from existing tags; no inferred new clusters. */
 private static boolean compatible(Entry e,String source){
  String metadata=Objects.toString(e.world(),"")+" "+String.join(" ",e.tags()==null?List.of():e.tags());
  var country=countries(source);var offered=countries(metadata);
  if(!country.isEmpty()&&!offered.isEmpty()&&Collections.disjoint(country,offered))return false;
  var era=eras(source);var offeredEra=eras(metadata);
  return era.isEmpty()||offeredEra.isEmpty()||!Collections.disjoint(era,offeredEra);
 }
 private static Set<String> countries(String text){
  String s=text.toLowerCase(Locale.ROOT);Set<String> result=new HashSet<>();
  if(containsAny(s,"한국","조선","한양","한옥","고려","신라","korea","korean","joseon","韓国","朝鮮"))result.add("KR");
  if(containsAny(s,"일본","에도","교토","사무라이","japan","japanese","edo","日本","京都"))result.add("JP");
  if(containsAny(s,"중국","중원","명나라","청나라","당나라","china","chinese","中国"))result.add("CN");
  return result;
 }
 private static Set<String> eras(String text){
  String s=text.toLowerCase(Locale.ROOT);Set<String> result=new HashSet<>();
  if(containsAny(s,"현대","modern","contemporary","現代"))result.add("MODERN");
  if(containsAny(s,"미래","sf","우주","future","spaceship","宇宙","未来"))result.add("FUTURE");
  if(containsAny(s,"중세","medieval","中世"))result.add("MEDIEVAL");
  if(containsAny(s,"조선","한옥","전통","에도","joseon","traditional","伝統"))result.add("TRADITIONAL");
  if(containsAny(s,"근대","산업화","레트로","retro","近代"))result.add("RETRO");
  if(containsAny(s,"고대","ancient","古代"))result.add("ANCIENT");return result;
 }
 private static boolean containsAny(String source,String... words){return Arrays.stream(words).anyMatch(word->word.chars().allMatch(c->c>='a'&&c<='z') ? java.util.regex.Pattern.compile("(?<![a-z])"+java.util.regex.Pattern.quote(word)+"(?![a-z])").matcher(source).find() : source.contains(word));}
 private static String text(com.lore.zzal.pet.persona.AuthoredPersona.Field f){return Objects.toString(f.rawText(),"")+" "+String.join(" ",f.chips()==null?List.of():f.chips());}
 private static int defaultPreference(Entry entry){return "modern_daily".equals(entry.world())&&entry.tags()!=null&&entry.tags().stream().anyMatch(t->t!=null&&t.contains("자취방"))?1:0;}
 private static int score(Entry entry,String world,String source,boolean persona){
  if(!persona)return ("modern_daily".equals(entry.world())?100:0)+(entry.tags()!=null&&entry.tags().stream().anyMatch(t->t!=null&&t.contains("자취방"))?10:0);
  Set<String> words=new HashSet<>();if(entry.world()!=null)words.add(entry.world().toLowerCase(Locale.ROOT));
  for(String tag:entry.tags()==null?List.<String>of():entry.tags())if(tag!=null&&!tag.isBlank()){words.add(tag.strip().toLowerCase(Locale.ROOT));for(String word:tag.toLowerCase(Locale.ROOT).split("[\\s·,/]+"))if(word.length()>=2)words.add(word);}
  int score=0;for(String word:words){if(world.contains(word))score+=100;if(source.contains(word))score++;}return score;
 }
 public String url(String key){return entries.stream().filter(Entry::eligible).filter(x->x.key().equals(key)).map(Entry::url).findFirst().orElse("/"+key);}
}
