package com.lore.zzal.attendance;

import java.time.Instant;
import java.util.*;

/** Service cycles, never calendar midnights. Reset participation at each set's first received reward. */
public final class NextCustomSetEligibility {
    private NextCustomSetEligibility() {}
    public record Cycle(String key, Instant startedAt, int storedUserMessages) {}
    public enum State { AWAITING_ALL_EIGHT, INVALID_HISTORY, AWAITING_SEVEN_CYCLES, AWAITING_NEXT_CYCLE, ELIGIBLE }
    public record Decision(State state, int qualifyingCycles, String firstReceiptCycle,
                           String conditionsSatisfiedCycle, String generationCycle) {
        public boolean eligible() { return state == State.ELIGIBLE; }
    }
    public static Decision evaluate(Map<Integer, Instant> received, List<Cycle> cycles) {
        if (received.size()!=8 || !received.keySet().equals(Set.of(1,2,3,4,5,6,7,8)) || received.containsValue(null))
            return result(State.AWAITING_ALL_EIGHT,0,null,null,null);
        for(int i=0;i<cycles.size();i++) if(cycles.get(i).startedAt()==null || cycles.get(i).storedUserMessages()<0
                || (i>0&&!cycles.get(i-1).startedAt().isBefore(cycles.get(i).startedAt())))
            return result(State.INVALID_HISTORY,0,null,null,null);
        Instant first=Collections.min(received.values()),last=Collections.max(received.values());
        int firstCycle=at(cycles,first),lastCycle=at(cycles,last);
        if(firstCycle<0 || lastCycle<0)return result(State.INVALID_HISTORY,0,null,null,null);
        int qualified=0,seventh=-1;
        for(int i=firstCycle;i<cycles.size();i++) if(cycles.get(i).storedUserMessages()>=5){qualified++;if(qualified==7)seventh=i;}
        if(seventh<0)return result(State.AWAITING_SEVEN_CYCLES,qualified,cycles.get(firstCycle).key(),null,null);
        int satisfied=Math.max(seventh,lastCycle);
        if(satisfied+1>=cycles.size())return result(State.AWAITING_NEXT_CYCLE,qualified,cycles.get(firstCycle).key(),cycles.get(satisfied).key(),null);
        return result(State.ELIGIBLE,qualified,cycles.get(firstCycle).key(),cycles.get(satisfied).key(),cycles.get(satisfied+1).key());
    }
    private static int at(List<Cycle> cycles,Instant timestamp){int found=-1;for(int i=0;i<cycles.size();i++)if(!cycles.get(i).startedAt().isAfter(timestamp))found=i;return found;}
    private static Decision result(State state,int count,String first,String satisfied,String next){return new Decision(state,count,first,satisfied,next);}
}
