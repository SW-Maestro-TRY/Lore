package com.lore.zzal.chat.stage;
import com.lore.zzal.chat.line.LineChain;import com.lore.zzal.chat.prompt.ConversationScene;
import org.springframework.stereotype.Service;import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;import java.util.List;
/** Orchestrates committed claim -> no transaction/model call -> committed completion. */
@Service public class StageSpeechService {
 private final StageSpeechTransactions tx;private final LineChain lines;
 public StageSpeechService(StageSpeechTransactions tx,LineChain lines){this.tx=tx;this.lines=lines;}
 public ZzalStageSpeech.View prepare(Long owner,Long petId,ConversationScene.Step step,ConversationScene.Beat phase,Instant now){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stage generation must not run inside caller transaction");
  return generate(owner,petId,tx.claim(owner,petId,step,phase,now));
 }
 public ZzalStageSpeech.View prepareDiscovery(Long owner,Long petId,Instant now,String language){
  if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Stage generation must not run inside caller transaction");
  return generate(owner,petId,tx.claimDiscovery(owner,petId,now,language));
 }
 private ZzalStageSpeech.View generate(Long owner,Long petId,StageSpeechTransactions.Claim claim){
  if(!claim.acquired())return claim.view();
  com.lore.zzal.chat.line.GeneratedLine generated;
  try {generated=lines.generateOnce(claim.context(),owner);}
  catch(RuntimeException failure){tx.uncertain(owner,petId,claim,null,Instant.now());throw failure;}
  try {return tx.complete(owner,petId,claim,generated,Instant.now());}
  catch(RuntimeException failure){tx.uncertain(owner,petId,claim,generated,Instant.now());throw failure;}
 }
 public List<ZzalStageSpeech.View> read(Long owner,Long petId){return tx.read(owner,petId);}
}
