package com.lore.zzal.attendance;
import java.util.List;
@io.swagger.v3.oas.annotations.media.Schema(name="AttendanceRewards",schemaResolution=io.swagger.v3.oas.annotations.media.Schema.SchemaResolution.INLINE)
public record AttendanceRewards(String programVersion, int attendanceDay, String firstBundleState,
 int eligibleUnfulfilledCount, List<OwnedReward> ownedRewards, Background background,@io.swagger.v3.oas.annotations.media.Schema(requiredMode=io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED) List<OwnedCustomMotion> customMotions,
 @io.swagger.v3.oas.annotations.media.Schema(description="출석 선물 화면을 마지막으로 연 뒤 지급된 선물 수(처음 받은 기본 배경 제외). 0보다 크면 화면이 빨간 점을 그린다. POST /{petId}/rewards/seen 이 0으로 돌린다") int newRewardCount,
 @io.swagger.v3.oas.annotations.media.Schema(description="아직 안 읽은 일지 수. GET /{petId}/diary 의 unreadCount 와 같은 기준(READY·read_at 없음)") int unreadDiaryCount) {
 public AttendanceRewards(String programVersion,int attendanceDay,String firstBundleState,int eligibleUnfulfilledCount,List<OwnedReward> ownedRewards,Background background){this(programVersion,attendanceDay,firstBundleState,eligibleUnfulfilledCount,ownedRewards,background,List.of());}
 public AttendanceRewards(String programVersion,int attendanceDay,String firstBundleState,int eligibleUnfulfilledCount,List<OwnedReward> ownedRewards,Background background,List<OwnedCustomMotion> customMotions){this(programVersion,attendanceDay,firstBundleState,eligibleUnfulfilledCount,ownedRewards,background,customMotions,0,0);}
 public AttendanceRewards {customMotions=customMotions==null?List.of():List.copyOf(customMotions);}
 public record OwnedReward(String slotKey,String kind,String assetKey,String url,Integer motionOrder) {}
 public record OwnedBackground(String key,String url) {}
 public record Background(String selectedKey,List<OwnedBackground> owned,Preview preview) {}
 public record Preview(String key,String url) {}
}
