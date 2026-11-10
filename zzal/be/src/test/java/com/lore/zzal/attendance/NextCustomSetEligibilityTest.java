package com.lore.zzal.attendance;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class NextCustomSetEligibilityTest {
 static final Instant START=Instant.parse("2026-10-01T01:00:00Z");
 static List<NextCustomSetEligibility.Cycle> cycles(int count){List<NextCustomSetEligibility.Cycle> result=new ArrayList<>();for(int i=0;i<count;i++)result.add(new NextCustomSetEligibility.Cycle("NIGHT:"+i,START.plusSeconds(86400L*i),5));return result;}
 static Map<Integer,Instant> received(int first,int last){Map<Integer,Instant> map=new HashMap<>();for(int i=1;i<=8;i++)map.put(i,START.plusSeconds(86400L*(i==1?first:last)+60));return map;}
 @Test void seventhQualifiedCycleDoesNotGenerateUntilFollowingCycle(){
  var grants=received(0,6);
  assertThat(NextCustomSetEligibility.evaluate(grants,cycles(7)).state()).isEqualTo(NextCustomSetEligibility.State.AWAITING_NEXT_CYCLE);
  assertThat(NextCustomSetEligibility.evaluate(grants,cycles(8)).generationCycle()).isEqualTo("NIGHT:7");
 }
 @Test void lateEighthReceiptUsesMaximumOfParticipationAndAllReceivedCycles(){
  var grants=received(0,9);
  assertThat(NextCustomSetEligibility.evaluate(grants,cycles(10)).state()).isEqualTo(NextCustomSetEligibility.State.AWAITING_NEXT_CYCLE);
  assertThat(NextCustomSetEligibility.evaluate(grants,cycles(11)).generationCycle()).isEqualTo("NIGHT:10");
 }
 @Test void exactlyFiveCountsFourDoesNotAndCyclesNeedNotBeConsecutive(){
  var list=new ArrayList<>(cycles(9));list.set(2,new NextCustomSetEligibility.Cycle("NIGHT:2",START.plusSeconds(86400L*2),4));
  var decision=NextCustomSetEligibility.evaluate(received(0,6),list);
  assertThat(decision.qualifyingCycles()).isEqualTo(8);assertThat(decision.generationCycle()).isEqualTo("NIGHT:8");
 }
 @Test void participationBeforeThisSetsFirstReceiptDoesNotCarryOver(){
  var decision=NextCustomSetEligibility.evaluate(received(7,12),cycles(14));
  assertThat(decision.qualifyingCycles()).isEqualTo(7);assertThat(decision.state()).isEqualTo(NextCustomSetEligibility.State.AWAITING_NEXT_CYCLE);
 }
 @Test void requiresAllEightOrdersNotJustSevenOrDuplicateReceipts(){
  var grants=received(0,6);grants.remove(8);
  assertThat(NextCustomSetEligibility.evaluate(grants,cycles(20)).state()).isEqualTo(NextCustomSetEligibility.State.AWAITING_ALL_EIGHT);
 }
 @Test void cycleBoundaryIsDataNotMidnight(){
  var list=new ArrayList<NextCustomSetEligibility.Cycle>();for(int i=0;i<8;i++)list.add(new NextCustomSetEligibility.Cycle("wake-"+i,START.plusSeconds(3600L*i),5));
  Map<Integer,Instant> grants=new HashMap<>();for(int i=1;i<=8;i++)grants.put(i,START.plusSeconds(i==1?60:6*3600+60));
  assertThat(NextCustomSetEligibility.evaluate(grants,list).generationCycle()).isEqualTo("wake-7");
 }
 @Test void slotOrderParsingAcceptsOnlyExactLegacyOrVersionedEightSlots(){
  assertThat(AttendanceService.rewardMotionOrder("motion-8")).isEqualTo(8);
  assertThat(AttendanceService.rewardMotionOrder("bundle-2:motion-1")).isEqualTo(1);
  assertThat(AttendanceService.rewardMotionOrder("bundle-100:motion-8")).isEqualTo(8);
  for(String slot:List.of("background","legacy-background","bundle-2:motion-9","bundle-2:motion-11","xmotion-1","bundle-0:motion-1"))assertThat(AttendanceService.rewardMotionOrder(slot)).isNull();
 }

}
