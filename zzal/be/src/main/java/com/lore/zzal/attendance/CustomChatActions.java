package com.lore.zzal.attendance;
import com.lore.zzal.chat.prompt.ChatContext;
import java.util.*;
/** Same line-model response chooses an owned ID; no selector invocation or playback rotation. */
public final class CustomChatActions {
 private CustomChatActions(){}
 public static List<ChatContext.ActionOption> allowed(List<OwnedCustomMotion> owned){
  List<ChatContext.ActionOption> options=new ArrayList<>();options.add(new ChatContext.ActionOption("base","기본 자세","어울리는 지급 동작이 없으면 기본 자세","base"));
  if(owned!=null)for(var motion:owned)if(motion.chatEligible())options.add(new ChatContext.ActionOption(motion.id(),motion.title(),motion.description(),motion.semantic()));
  return List.copyOf(options);
 }
 public static String select(String requested,List<ChatContext.ActionOption> allowed){return requested!=null&&allowed.stream().anyMatch(a->a.id().equals(requested))?requested:"base";}
}
