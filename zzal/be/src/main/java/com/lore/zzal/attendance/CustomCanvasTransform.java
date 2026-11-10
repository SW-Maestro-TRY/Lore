package com.lore.zzal.attendance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/** Strict additive custom canvas contract; historical results retain their original canvas check. */
public final class CustomCanvasTransform {
    private CustomCanvasTransform() { }
    public static void validate(JsonNode base, JsonNode anchors, JsonNode metadata,
                                String actualBaseHash, String actualGridHash) {
        validate(base,anchors,metadata,actualBaseHash,actualGridHash,null);
    }
    public static void validate(JsonNode base, JsonNode anchors, JsonNode metadata,
                                String actualBaseHash, String actualGridHash, CustomMotionPlan frozenPlan) {
        for (String key : List.of("version","K","Hw")) require(Objects.equals(base.get(key),anchors.get(key)),"Frozen base scale mismatch");
        JsonNode t=anchors.get("customTransform"), declared=metadata.get("customTransform");
        if(t==null && declared==null){require(Objects.equals(base.get("canvas"),anchors.get("canvas")),"Frozen base canvas mismatch");return;}
        require(t!=null && t.isObject() && t.equals(declared),"Custom transform sidecar mismatch");
        boolean body="CUSTOM_CANVAS_BODY_V2".equals(t.path("version").asText());
        require(body || "CUSTOM_CANVAS_V1".equals(t.path("version").asText()),"Unknown custom transform");
        require(actualBaseHash!=null && actualBaseHash.matches("[a-f0-9]{64}") && actualBaseHash.equals(t.path("baseAnchorsSHA256").asText()),"Base anchors digest mismatch");
        require(actualGridHash!=null && actualGridHash.matches("[a-f0-9]{64}") && actualGridHash.equals(t.path("gridSHA256").asText()),"Grid digest mismatch");
        require(base.path("canvas").equals(t.path("baseCanvas")) && anchors.path("canvas").equals(t.path("outputCanvas")),"Canvas provenance mismatch");
        require(base.path("K").equals(t.path("K")) && base.path("Hw").equals(t.path("Hw")),"Transform base scale mismatch");
        int bw=integer(base.path("canvas").path("w"),16,4096),bh=integer(base.path("canvas").path("h"),16,4096);
        int w=integer(t.path("outputCanvas").path("w"),16,4096),h=integer(t.path("outputCanvas").path("h"),16,4096);
        int tx=integer(t.path("baseOffset").path("x"),0,4096),ty=integer(t.path("baseOffset").path("y"),0,4096);
        require(tx+bw<=w && ty+bh<=h,"Base canvas is outside custom canvas");
        double scale=number(t.path("normalizationScale"),.25,4),sourceHw=number(t.path("sourceHeadWidth"),1,4096);
        if(!body)require(Math.abs(scale*sourceHw-base.path("Hw").asDouble())<.000001,"Head scale does not preserve base");
        require(t.path("sharedAffine").path("scale").asDouble(Double.NaN)==scale,"Shared scale mismatch");
        int dx=integer(t.path("sharedAffine").path("dx"),-4096,4096),dy=integer(t.path("sharedAffine").path("dy"),-4096,4096);
        double sourceFoot=number(t.path("standingFootSource"),0,4096),targetFoot=number(t.path("standingFootTarget"),0,h);
        if(body)validateBody(t.path("bodyNormalization"),base,frozenPlan,scale,sourceHw,sourceFoot);
        require(Math.abs(sourceFoot*scale+dy-targetFoot)<=.500001,"Foot affine mismatch");
        var bottoms=new java.util.ArrayList<Double>();
        for(JsonNode pose:base.path("poses")){if(pose.path("feet").path("y").isNumber()){
            JsonNode canvas=pose.path("canvas");double canvasH=canvas.isArray()&&canvas.size()==2?number(canvas.get(1),16,4096):bh;
            bottoms.add(canvasH-number(pose.path("feet").path("y"),0,canvasH));
        }}
        require(!bottoms.isEmpty(),"Base foot reference missing");bottoms.sort(Double::compare);
        int mid=bottoms.size()/2;double bottom=bottoms.size()%2==0?(bottoms.get(mid-1)+bottoms.get(mid))/2:bottoms.get(mid);
        require(Math.abs(targetFoot-(bh-bottom+ty))<.000001,"Base foot offset mismatch");
        JsonNode original=t.path("crop").path("originalBox"),expanded=t.path("crop").path("expandedBox");
        require(original.isArray()&&original.size()==4&&expanded.isArray()&&expanded.size()==4,"Crop boxes missing");
        for(int i=0;i<4;i++){integer(original.get(i),0,16384);integer(expanded.get(i),0,16384);}
        require(original.get(0).asInt()<original.get(2).asInt()&&original.get(1).asInt()<original.get(3).asInt()
                &&expanded.get(0).asInt()<=original.get(0).asInt()&&expanded.get(1).asInt()<=original.get(1).asInt()
                &&expanded.get(2).asInt()>=original.get(2).asInt()&&expanded.get(3).asInt()>=original.get(3).asInt(),"Crop containment mismatch");
        require(expanded.get(2).asInt()-expanded.get(0).asInt()==integer(t.path("inputCanvas").path("w"),16,4096)
                &&expanded.get(3).asInt()-expanded.get(1).asInt()==integer(t.path("inputCanvas").path("h"),16,4096),"Crop canvas mismatch");
        double sourcePivotX=(original.get(0).asDouble()+original.get(2).asDouble())/2-expanded.get(0).asDouble();
        require(Math.abs(sourcePivotX*scale+dx-(bw/2.0+tx))<=.500001,"Horizontal affine mismatch");
        for(String axis:List.of("w","h")){
            int input=integer(t.path("inputCanvas").path(axis),16,4096),resized=integer(t.path("resizedCanvas").path(axis),1,16384);
            require(Math.abs(input*scale-resized)<=.500001,"Resize dimensions mismatch");
        }
        require(integer(t.path("lostAlphaPixels"),0,0)==0,"Lost custom pixels");
        JsonNode crop=t.path("crop");require(crop.path("lostAlphaPixels").isArray()&&crop.path("lostAlphaPixels").size()==16,"Crop evidence missing");
        require(crop.path("alphaPixelsBefore").size()==16&&crop.path("alphaPixelsAfter").size()==16,"Crop counts missing");
        for(int i=0;i<16;i++){
            integer(crop.path("lostAlphaPixels").get(i),0,0);
            require(integer(crop.path("alphaPixelsBefore").get(i),1,16777216)==integer(crop.path("alphaPixelsAfter").get(i),1,16777216),"Crop alpha loss");
        }
        require(t.path("cellMembership").isArray()&&t.path("cellMembership").size()==16,"Cell membership evidence missing");
        for(int i=0;i<16;i++){
            JsonNode membership=t.path("cellMembership").get(i);require(integer(membership.path("frame"),1,16)==i+1,"Cell order mismatch");
            int removed=0;require(membership.path("componentsRemoved").isArray(),"Cell component evidence missing");
            for(JsonNode c:membership.path("componentsRemoved")){
                require("DISCONNECTED_OUTSIDE_OWN_CELL".equals(c.path("reason").asText()),"Unknown removal policy");
                removed+=integer(c.path("pixels"),1,16777216);
                JsonNode b=c.path("bbox"),own=membership.path("ownCell");require(b.isArray()&&b.size()==4&&own.isArray()&&own.size()==4,"Cell bounds missing");
                for(int j=0;j<4;j++){integer(b.get(j),0,4096);integer(own.get(j),0,4096);}
                require(b.get(2).asInt()<=own.get(0).asInt()||b.get(0).asInt()>=own.get(2).asInt()||b.get(3).asInt()<=own.get(1).asInt()||b.get(1).asInt()>=own.get(3).asInt(),"Removed component overlaps own cell");
            }
            require(removed==integer(membership.path("removedAlphaPixels"),0,16777216),"Cell removal count mismatch");
            integer(membership.path("retainedOwnCellAlphaPixels"),1,16777216);
        }
        require(t.path("frames").isArray()&&t.path("frames").size()==16,"Sixteen frame proofs required");
        for(int i=0;i<16;i++){
            JsonNode f=t.path("frames").get(i);require(integer(f.path("frame"),1,16)==i+1,"Frame order mismatch");
            require(f.path("contentPreserved").isBoolean()&&f.path("contentPreserved").asBoolean(),"Frame content loss");
            integer(f.path("lostAlphaPixels"),0,0);require(integer(f.path("alphaPixelsBeforePlacement"),1,16777216)==integer(f.path("alphaPixelsAfterPlacement"),1,16777216),"Placement alpha loss");
            JsonNode box=f.path("bbox");require(box.isArray()&&box.size()==4,"Frame bbox missing");int x0=integer(box.get(0),0,w),y0=integer(box.get(1),0,h),x1=integer(box.get(2),0,w),y1=integer(box.get(3),0,h);require(x0<x1&&y0<y1,"Empty frame bounds");require(x0>=16&&y0>=16&&x1<=w-16&&y1<=h-16,"Custom safety margin mismatch");
            require(f.path("edgeContact").isObject(),"Edge evidence missing");for(var side:List.of("L","R","T","B"))require(integer(f.path("edgeContact").path(side).isMissingNode()?com.fasterxml.jackson.databind.node.IntNode.valueOf(0):f.path("edgeContact").path(side),0,side.equals("T")?39:19)>=0,"Frame clips");
        }
        for(String key:CustomGridPrompt.SLOTS){JsonNode pose=anchors.path("poses").path(key);require(pose.isObject(),"Missing custom pose");JsonNode box=pose.path("bbox");double x=number(box.path("x"),0,w),y=number(box.path("y"),0,h),pw=number(box.path("w"),1,w),ph=number(box.path("h"),1,h);require(x+pw<=w&&y+ph<=h,"Pose bbox outside canvas");
            for(String field:List.of("head_top","head_side","hand_front","feet")){JsonNode point=pose.path(field);require(point.isObject(),"Missing pose anchor");// anchors.py uses crown_x +/- frozen Hw/2 for the head-side collision proxy.
                // This virtual span may extend beyond pixels; actual frame and other anchor bounds stay strict.
                double headMargin=field.equals("head_side")?number(base.path("Hw"),1,4096)/2:0;
                if(point.has("left_x")||point.has("right_x"))require(number(point.path("left_x"),-headMargin,w+headMargin)<=number(point.path("right_x"),-headMargin,w+headMargin),"Reversed pose span");
                var fields=point.fields();while(fields.hasNext()){var entry=fields.next();boolean proxy=field.equals("head_side")&&(entry.getKey().equals("left_x")||entry.getKey().equals("right_x"));number(entry.getValue(),proxy?-headMargin:0,proxy?w+headMargin:entry.getKey().equals("y")?h:w);}}
        }
    }
    private static void validateBody(JsonNode proof,JsonNode base,CustomMotionPlan plan,double scale,double sourceHw,double sourceFoot){
        require(plan!=null,"Frozen motion plan required for body normalization");
        require("UPRIGHT_SILHOUETTE_PROXY_V1".equals(proof.path("version").asText()),"Unknown upright measurement policy");
        require(proof.path("characterOnlySegmentation").isBoolean()&&!proof.path("characterOnlySegmentation").asBoolean(),"Silhouette proxy must not claim anatomical segmentation");
        try{require(AttendanceService.hash(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan)).equals(proof.path("motionPlanSHA256").asText()),"Frozen measurement plan digest mismatch");}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Frozen plan serialization failed",e);}
        JsonNode orders=proof.path("selectedOrders"),frames=proof.path("selectedFrames"),measured=proof.path("measuredFrames"),decisions=proof.path("decisions");
        require(orders.isArray()&&orders.size()>0&&orders.size()<=8&&frames.size()==orders.size()*2&&measured.size()==frames.size()&&decisions.size()==8,"Standing measurement evidence missing");
        int least=plan.motions().stream().filter(CustomCanvasTransform::neutralUpright).mapToInt(m->m.props().size()).min().orElseThrow(()->new IllegalStateException("No neutral upright plan reference"));
        var heights=new java.util.ArrayList<Double>();var widths=new java.util.ArrayList<Double>();var feet=new java.util.ArrayList<Double>();var selected=new java.util.HashSet<Integer>();
        int prior=0;
        for(int i=0;i<orders.size();i++){
            int order=integer(orders.get(i),1,8);require(order>prior&&selected.add(order),"Standing order mismatch");prior=order;
            var motion=plan.motions().stream().filter(m->m.order()==order).findFirst().orElseThrow();
            require(neutralUpright(motion)&&motion.props().size()==least,"Selected pose is not the preferred neutral upright reference");
            double[] pair=new double[2];
            for(int ab=0;ab<2;ab++){
                int index=i*2+ab,expected=(order-1)*2+ab+1;JsonNode m=measured.get(index);
                require(integer(frames.get(index),1,16)==expected&&integer(m.path("frame"),1,16)==expected,"Standing frame mapping mismatch");
                double height=number(m.path("height"),1,4096),top=number(m.path("headTop"),0,4096),foot=number(m.path("footY"),0,4096),hw=number(m.path("headWidthProxy"),1,4096);
                require(Math.abs(foot-top-height)<.000001&&height/hw>=1.15,"Unreliable upright silhouette extent");
                JsonNode box=m.path("bbox");require(box.isArray()&&box.size()==4&&number(box.get(0),0,4096)<number(box.get(2),0,4096)&&number(box.get(1),0,4096)==top&&number(box.get(3),0,4096)==foot,"Standing source bounds mismatch");
                heights.add(height);widths.add(hw);feet.add(foot);pair[ab]=height;
            }
            require(Math.abs(pair[0]-pair[1])/Math.max(pair[0],pair[1])<=.080000001,"Standing pair height disagrees");
        }
        require((java.util.Collections.max(heights)-java.util.Collections.min(heights))/java.util.Collections.max(heights)<=.100000001,"Standing references disagree");
        double sourceHeight=number(proof.path("sourceHeight"),1,4096),targetHeight=number(proof.path("targetHeight"),1,4096);
        require(Math.abs(median(heights)-sourceHeight)<.000001&&targetHeight==base.path("K").asDouble()&&Math.abs(scale*sourceHeight-targetHeight)<.000001,"Body height scale mismatch");
        require(number(proof.path("scale"),.25,4)==scale&&Math.abs(median(widths)-sourceHw)<.000001&&Math.abs(number(proof.path("sourceHeadWidthProxy"),1,4096)-sourceHw)<.000001,"Body proxy width mismatch");
        require(Math.abs(median(feet)-sourceFoot)<.000001&&Math.abs(number(proof.path("standingFootSource"),0,4096)-sourceFoot)<.000001,"Body proxy foot mismatch");
        for(var motion:plan.motions()){
            JsonNode d=decisions.get(motion.order()-1);require(integer(d.path("order"),1,8)==motion.order()&&CustomGridPrompt.SLOTS.get(motion.order()-1).equals(d.path("slot").asText())&&motion.pose().equals(d.path("pose").asText())&&integer(d.path("propsCount"),0,100)==motion.props().size(),"Measurement decision differs from frozen plan");
            require(d.path("selected").asBoolean()==selected.contains(motion.order()),"Standing selection decision mismatch");
        }
    }
    private static double median(java.util.List<Double> values){var sorted=new java.util.ArrayList<>(values);sorted.sort(Double::compare);int m=sorted.size()/2;return sorted.size()%2==0?(sorted.get(m-1)+sorted.get(m))/2:sorted.get(m);}
    private static boolean neutralUpright(CustomMotionPlan.Motion motion){
        String text=motion.pose().toLowerCase(java.util.Locale.ROOT);
        if(!text.startsWith("standing:")||java.util.regex.Pattern.compile("lean|bend|jump|airborne|lifted|tiptoe|off.{0,8}ground|기울|점프|뛰|발.{0,12}들").matcher(text).find())return false;
        if(java.util.regex.Pattern.compile("(rais|reach).{0,35}(sky|overhead|above.{0,8}head)|머리.{0,12}위|하늘|desk|table|telescope|tripod|sofa|blanket|책상|탁자|망원경|삼각대|소파|담요|창가").matcher(text).find())return false;
        return motion.props().stream().noneMatch(p->java.util.regex.Pattern.compile("desk|table|telescope|tripod|sofa|blanket|책상|탁자|망원경|삼각대|소파|담요").matcher(p.toLowerCase(java.util.Locale.ROOT)).find());
    }
    private static int integer(JsonNode node,int low,int high){require(node!=null&&node.isIntegralNumber()&&node.canConvertToInt(),"Integer evidence required");int v=node.intValue();require(v>=low&&v<=high,"Integer outside bound");return v;}
    private static double number(JsonNode node,double low,double high){require(node!=null&&node.isNumber(),"Numeric evidence required");double v=node.doubleValue();require(Double.isFinite(v)&&v>=low&&v<=high,"Number outside bound");return v;}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
