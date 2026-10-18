package com.lore.zzal.diary;
import com.lore.common.auth.jwt.LoginUser;
import com.lore.common.response.ApiResponse;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
@RestController
@RequestMapping("/api/zzal/v1/me/pets/{petId}/diary")
public class DiaryController {
    private final DiaryService diary;
    public DiaryController(DiaryService diary){this.diary=diary;}
    @io.swagger.v3.oas.annotations.Operation(operationId="zzalDiaryList")
    @GetMapping public ApiResponse<DiaryResponses.Page> list(@LoginUser Long owner,@PathVariable Long petId,
            @RequestParam(required=false)Long beforeId,@RequestParam(defaultValue="20")int limit){
        return ApiResponse.ok(diary.list(owner,petId,beforeId,limit));
    }
    @PostMapping("/{entryId}/read") public ApiResponse<DiaryResponses.Entry> read(@LoginUser Long owner,
            @PathVariable Long petId,@PathVariable Long entryId){
        return ApiResponse.ok(diary.read(owner,petId,entryId,Instant.now()));
    }
}
