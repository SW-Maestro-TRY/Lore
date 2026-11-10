package com.lore.zzal.attendance;
import com.lore.zzal.generation.client.*;
import java.nio.file.Path;
import java.util.*;
/** Reuses product API ImageClient and its explicit no-retry contract. No new HTTP stack or key lookup. */
public final class ImageClientCustomGridGenerator implements CustomGridGenerator {
 private final ImageClient images;private final ModelSpec spec;
 public ImageClientCustomGridGenerator(ImageClient images,String model,String size){if(model==null||model.isBlank()||size==null||size.isBlank())throw new IllegalArgumentException("Explicit image model/size required");this.images=Objects.requireNonNull(images);this.spec=new ModelSpec(model,size,"medium");}
 @Override public Grid generate(CustomGenerationRequest request,String prompt,String referenceKey,String outputKey,Path output)throws Exception {
  if(!"medium".equals(request.quality()))throw new IllegalArgumentException("Unsupported image quality");
  var result=images.generate(prompt,List.of(referenceKey),outputKey,spec);
  return new Grid(result.imageKey(),"OPENAI_IMAGE_API",spec.model(),spec.quality(),result.costUsd());
 }
}
