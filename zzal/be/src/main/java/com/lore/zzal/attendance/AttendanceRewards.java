package com.lore.zzal.attendance;
import java.util.List;
@io.swagger.v3.oas.annotations.media.Schema(name="AttendanceRewards",schemaResolution=io.swagger.v3.oas.annotations.media.Schema.SchemaResolution.INLINE)
public record AttendanceRewards(String programVersion, int attendanceDay, String firstBundleState,
 int eligibleUnfulfilledCount, List<OwnedReward> ownedRewards, Background background,@io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED) List<OwnedCustomMotion> customMotions) {
 public AttendanceRewards(String programVersion,int attendanceDay,String firstBundleState,int eligibleUnfulfilledCount,List<OwnedReward> ownedRewards,Background background){this(programVersion,attendanceDay,firstBundleState,eligibleUnfulfilledCount,ownedRewards,background,List.of());}
 public AttendanceRewards {customMotions=customMotions==null?List.of():List.copyOf(customMotions);}
 public record OwnedReward(String slotKey,String kind,String assetKey,String url,Integer motionOrder) {}
 public record OwnedBackground(String key,String url) {}
 public record Background(String selectedKey,List<OwnedBackground> owned,Preview preview) {}
 public record Preview(String key,String url) {}
}
