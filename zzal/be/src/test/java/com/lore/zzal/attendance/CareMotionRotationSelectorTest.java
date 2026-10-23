package com.lore.zzal.attendance;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class CareMotionRotationSelectorTest {
    private static final CareMotionRotationSelector.Situation FEED = new CareMotionRotationSelector.Situation("FEED", "eat");
    private static OwnedCustomMotion motion(long set, int order, String semantic, boolean albumOnly) {
        return new OwnedCustomMotion("custom:"+set+"-"+order,set,order,"제안","연출",semantic,albumOnly,
                "images/custom/"+set+"/pose.webp",null,"physical_pose",1,true,true,"CUSTOM",CustomMotionPlan.CURRENT_VERSION,albumOnly?List.of():"eat".equals(semantic)?List.of("FEED"):List.of("PET"));
    }
    @Test void startsBasicThenSetOneSetTwoAndBasic() {
        var owned=List.of(motion(2,1,"eat",false),motion(1,1,"eat",false));
        var a=CareMotionRotationSelector.next(FEED,null,owned);
        var b=CareMotionRotationSelector.next(FEED,a,owned);
        var c=CareMotionRotationSelector.next(FEED,b,owned);
        var d=CareMotionRotationSelector.next(FEED,c,owned);
        assertThat(a.basic()).isTrue();assertThat(b.setId()).isEqualTo(1L);
        assertThat(c.setId()).isEqualTo(2L);assertThat(d.basic()).isTrue();
    }
    @Test void skipsAlbumOnlyWrongSituationAndUsesOnlyDeliveredInput() {
        // Unreceived/rejected/unpublished assets must never enter this list: ownership SQL owns that gate.
        var owned=List.of(motion(1,1,null,true),motion(2,1,"hello",false),motion(3,1,"eat",false));
        var base=CareMotionRotationSelector.next(FEED,null,owned);
        assertThat(CareMotionRotationSelector.next(FEED,base,owned).setId()).isEqualTo(3L);
        assertThat(CareMotionRotationSelector.next(FEED,base,List.of()).basic()).isTrue();
    }
    @Test void failedRequestDoesNotConsumeProposedSelection() {
        var owned=List.of(motion(1,1,"eat",false),motion(2,1,"eat",false));
        var committed=CareMotionRotationSelector.next(FEED,null,owned);
        var failedProposal=CareMotionRotationSelector.next(FEED,committed,owned);
        // Caller discards the failed proposal and retains committed state.
        assertThat(CareMotionRotationSelector.next(FEED,committed,owned)).isEqualTo(failedProposal);
        assertThat(committed.basic()).isTrue();
    }
    @Test void addingSetDoesNotReindexLastSetOrSkipNewTail() {
        var initial=List.of(motion(10,1,"eat",false),motion(20,1,"eat",false));
        var base=CareMotionRotationSelector.next(FEED,null,initial);
        var first=CareMotionRotationSelector.next(FEED,base,initial);
        var expanded=List.of(motion(5,1,"eat",false),motion(10,1,"eat",false),motion(20,1,"eat",false),motion(30,1,"eat",false));
        var second=CareMotionRotationSelector.next(FEED,first,expanded);
        assertThat(second.setId()).isEqualTo(20L);
        assertThat(CareMotionRotationSelector.next(FEED,second,expanded).setId()).isEqualTo(30L);
    }
    @Test void sameSetUsesLowestMotionOrderIndependentlyOfInputOrder() {
        var owned=List.of(motion(1,7,"eat",false),motion(1,2,"eat",false));
        var base=CareMotionRotationSelector.next(FEED,null,owned);
        assertThat(CareMotionRotationSelector.next(FEED,base,owned).resource().order()).isEqualTo(2);
    }
    @Test void removedPreviousSetContinuesToNextAvailableSet() {
        var previous=new CareMotionRotationSelector.Selection("FEED","custom:old",10L,null);
        assertThat(CareMotionRotationSelector.next(FEED,previous,List.of(motion(20,1,"eat",false))).setId()).isEqualTo(20L);
        assertThat(CareMotionRotationSelector.next(FEED,previous,List.of()).basic()).isTrue();
    }
    @Test void separateCareSituationsCannotAccidentallyShareCursor() {
        var feed=CareMotionRotationSelector.next(FEED,null,List.of());
        assertThatThrownBy(()->CareMotionRotationSelector.next(new CareMotionRotationSelector.Situation("SNACK","eat"),feed,List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new CareMotionRotationSelector.Situation("CLEAN","sweep")).isInstanceOf(IllegalArgumentException.class);
    }
}
