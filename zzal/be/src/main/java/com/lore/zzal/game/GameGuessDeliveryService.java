package com.lore.zzal.game;
import com.lore.zzal.game.dto.*;import com.lore.zzal.pet.*;import com.lore.zzal.pet.receipt.*;import com.lore.common.exception.*;import tools.jackson.databind.json.JsonMapper;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.*;import java.time.Instant;import java.nio.charset.StandardCharsets;import java.security.*;import java.util.HexFormat;
/** Stored guess is a round fact, never authority for current pet sleep/travel. */
@Service public class GameGuessDeliveryService {
 private final ZzalPetRepository owners;private final PetService pets;private final GameService games;private final ZzalGameRepository gameRows;private final ActionReceiptRepository receipts;private final JsonMapper json;
 public GameGuessDeliveryService(ZzalPetRepository owners,PetService pets,GameService games,ZzalGameRepository gameRows,ActionReceiptRepository receipts,JsonMapper json){this.owners=owners;this.pets=pets;this.games=games;this.gameRows=gameRows;this.receipts=receipts;this.json=json;}
 @Transactional(propagation=Propagation.REQUIRES_NEW)
 public GameResponses.Guess guess(Long user,Long petId,Long gameId,GameRequests.Guess request,Instant now){
  ZzalPet pet=owners.findByIdForUpdate(petId).filter(p->p.isOwnedBy(user)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_PET_NOT_FOUND));String hash=hash(petId,gameId,request);
  var saved=receipts.findByPetIdAndRequestId(petId,request.requestId());
  if(saved.isPresent()){
   if(!saved.get().matches(hash,"GAME_GUESS"))throw new BusinessException(ErrorCode.ZZAL_ACTION_REQUEST_CONFLICT);
   try{return json.readValue(saved.get().resultJson(),GameResponses.Guess.class);}catch(Exception e){throw new IllegalStateException("Invalid frozen game receipt",e);}
  }
  var game=gameRows.findByIdForUpdate(gameId).filter(g->g.getUserId().equals(user)&&g.getPetId().equals(petId)).orElseThrow(()->new BusinessException(ErrorCode.ZZAL_GAME_NOT_FOUND));
  if(game.round()!=request.expectedRound())throw new BusinessException(ErrorCode.ZZAL_ACTION_REQUEST_CONFLICT,"놀이 회차가 바뀌었어요");
  // Existing bean keeps current awake/travel/sick/kind/finished checks and original rewards.
  var result=games.guess(user,petId,gameId,request.pick().code(),now);
  var response=GameResponses.Guess.of(result,games.remainingToday(pet));
  try{receipts.saveAndFlush(new ActionReceipt(petId,request.requestId(),"GAME_GUESS",hash,json.writeValueAsString(response),now));}catch(Exception e){throw new IllegalStateException(e);}
  return response;
 }
 public static String hash(Long pet,Long game,GameRequests.Guess request){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("game-guess:v1|"+pet+"|"+game+"|"+request.pick().name()+"|"+request.expectedRound()).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
