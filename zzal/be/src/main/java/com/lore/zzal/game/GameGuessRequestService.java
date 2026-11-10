package com.lore.zzal.game;
import com.lore.zzal.game.dto.*;import com.lore.zzal.pet.PetService;import com.lore.common.exception.*;import org.springframework.stereotype.Service;import java.time.Instant;
@Service public class GameGuessRequestService {
 private final GameService games;private final PetService pets;private final ProtectedGameGuessBoundary boundary;
 public GameGuessRequestService(GameService games,PetService pets,ProtectedGameGuessBoundary boundary){this.games=games;this.pets=pets;this.boundary=boundary;}
 public GameResponses.Guess guess(Long user,Long pet,Long game,GameRequests.Guess request,Instant now){
  if(!request.isIdentityPairValid())throw new BusinessException(ErrorCode.INVALID_INPUT);
  if(request.requestId()==null)return GameResponses.Guess.of(games.guess(user,pet,game,request.pick().code(),now),games.remainingToday(pets.get(user,pet)));
  if(request.expectedRound()<0||request.expectedRound()>4)throw new BusinessException(ErrorCode.INVALID_INPUT);
  return boundary.guess(user,pet,game,request,now);
 }
}
