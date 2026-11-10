package com.lore.zzal.attendance;
import com.lore.zzal.motion.*;
import java.util.*;

/** Eight authored proposals, not eight hardcoded action types. Free actions remain album-only. */
public record CustomMotionPlan(String version,List<Motion> motions) {
 public static final String VERSION="CUSTOM_MOTION_PLAN_V1";
 public static final String CURRENT_VERSION="CUSTOM_MOTION_PLAN_V2";
 public static final Set<String> CARE_SITUATIONS=Set.of("FEED","SNACK","PET","CLEAN","BATH","MEDICINE");
 public static final Set<String> BASIC_SEMANTICS=MotionCatalog.ALL.stream().filter(m->m.layer()==MotionLayer.BASIC_1).map(MotionSpec::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
 public enum Basis { PERSONA_CREATIVE, MEMORY }
 public record Evidence(Long factId,int revision,String subject) {public Evidence{if(factId==null||revision<1||!Set.of("USER","CHARACTER").contains(subject))throw new IllegalArgumentException("Invalid source attribution");}}
 public record Motion(int order,String title,String action,String rationale,Basis basis,List<Evidence> selectedSources,String semantic,boolean albumOnly,String pose,List<String> props,String staging,List<String> situations) {
  public Motion(int order,String title,String action,String rationale,Basis basis,List<Evidence> selectedSources,String semantic,boolean albumOnly,String pose,List<String> props,String staging){this(order,title,action,rationale,basis,selectedSources,semantic,albumOnly,pose,props,staging,null);}
  public Motion {
   selectedSources=List.copyOf(selectedSources);props=List.copyOf(props);
   if(situations!=null){situations=List.copyOf(situations);if(!CARE_SITUATIONS.containsAll(situations)||new HashSet<>(situations).size()!=situations.size()||albumOnly&&!situations.isEmpty())throw new IllegalArgumentException("Invalid explicit care situations");}
   if(order<1||order>8||blank(title)||blank(action)||blank(rationale)||blank(pose)||blank(staging)||basis==null)throw new IllegalArgumentException("Incomplete motion proposal");
   if(albumOnly?semantic!=null:(semantic==null||!BASIC_SEMANTICS.contains(semantic)))throw new IllegalArgumentException("Free motion must be album-only; room motion requires a basic semantic");
   if((basis==Basis.MEMORY)!=!selectedSources.isEmpty())throw new IllegalArgumentException("Memory claims require explicit evidence; creative proposals cannot claim a memory");
  }
 }
 public CustomMotionPlan {
  motions=List.copyOf(motions);
  if(!(VERSION.equals(version)||CURRENT_VERSION.equals(version))||motions.size()!=8||motions.stream().map(Motion::order).distinct().count()!=8)throw new IllegalArgumentException("Expected eight unique motion orders");
  for(var m:motions)if(VERSION.equals(version)?m.situations()!=null&&!m.situations().isEmpty():m.situations()==null)throw new IllegalArgumentException("V1 has no situation tags; V2 requires explicit situations");
  if(VERSION.equals(version))motions=motions.stream().map(m->new Motion(m.order(),m.title(),m.action(),m.rationale(),m.basis(),m.selectedSources(),m.semantic(),m.albumOnly(),m.pose(),m.props(),m.staging(),List.of())).toList();
 }
 public record RoomMotion(int order,String semantic){}
 public List<RoomMotion> roomMotionProjection(){return motions.stream().filter(m->!m.albumOnly()).map(m->new RoomMotion(m.order(),m.semantic())).toList();}
 public List<BundleSourcePlan.Source> selectedSources(){return motions.stream().flatMap(m->m.selectedSources().stream()).map(e->new BundleSourcePlan.Source(e.factId(),e.revision())).distinct().sorted(Comparator.comparing(BundleSourcePlan.Source::factId)).toList();}
 private static boolean blank(String value){return value==null||value.isBlank();}
}
