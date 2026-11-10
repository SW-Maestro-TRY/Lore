package com.lore.zzal.attendance;
import java.nio.file.Path;
/** Single grid call; transport changes without changing planning/reservation/receipt contracts. */
public interface CustomGridGenerator {
 record Grid(String imageKey,String provider,String model,String actualQuality,java.math.BigDecimal costUsd){
  public Grid(String imageKey,String provider,String model,String actualQuality){this(imageKey,provider,model,actualQuality,null);}
 }
 default void preflight()throws Exception{}
 Grid generate(CustomGenerationRequest request,String prompt,String referenceKey,String outputKey,Path output)throws Exception;
}
