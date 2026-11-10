package com.lore.zzal.game;
import com.lore.zzal.game.dto.*;import com.lore.zzal.pet.PetService;import org.springframework.stereotype.Service;import org.springframework.transaction.annotation.*;import java.time.Instant;
@Service public class ProtectedGameGuessBoundary {
 private final PetService pets;private final GameGuessDeliveryService delivery;
 public ProtectedGameGuessBoundary(PetService pets,GameGuessDeliveryService delivery){this.pets=pets;this.delivery=delivery;}
 @Transactional(propagation=Propagation.NEVER)
 public GameResponses.Guess guess(Long user,Long pet,Long game,GameRequests.Guess request,Instant now){pets.refresh(user,pet,now);return delivery.guess(user,pet,game,request,now);}
}
