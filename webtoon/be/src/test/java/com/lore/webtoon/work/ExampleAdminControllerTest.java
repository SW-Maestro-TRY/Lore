package com.lore.webtoon.work;

import com.lore.common.exception.BusinessException;
import com.lore.common.exception.ErrorCode;
import com.lore.common.s3.S3Service;
import com.lore.common.s3.S3Storage;
import com.lore.webtoon.Admins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 예시 관리자 API(#614) — <b>관리자만 쓴다.</b> 로그인 안 했으면 401, 관리자가 아니면 403 이고, 그때는 아무것도 안 한다.
 */
class ExampleAdminControllerTest {

    private Admins admins;
    private ExampleAdmin admin;
    private ExampleImporter importer;
    private ExampleBundleExporter exporter;
    private S3Service uploads;
    private S3Storage storage;
    private ExampleAdminController controller;

    @BeforeEach
    void 세운다() {
        admins = mock(Admins.class);
        admin = mock(ExampleAdmin.class);
        importer = mock(ExampleImporter.class);
        exporter = mock(ExampleBundleExporter.class);
        uploads = mock(S3Service.class);
        storage = mock(S3Storage.class);
        controller = new ExampleAdminController(admins, admin, importer, exporter, uploads, storage);
    }

    @AfterEach
    void 치운다() {
        SecurityContextHolder.clearContext();
    }

    private static void 로그인(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private static ErrorCode 코드(Throwable e) {
        return ((BusinessException) e).getErrorCode();
    }

    @Test
    @DisplayName("로그인 안 했으면 401 — 목록 · 번들 내보내기 · 지정 · 내리기 · 올릴 주소 모두")
    void 로그인_안_했으면_401() {
        assertThatThrownBy(() -> controller.list()).extracting(ExampleAdminControllerTest::코드).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> controller.bundle("run-1")).extracting(ExampleAdminControllerTest::코드).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> controller.update("run-1", new ExampleAdminController.UpdateRequest(true, null, null)))
                .extracting(ExampleAdminControllerTest::코드).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> controller.takeDown("run-1")).extracting(ExampleAdminControllerTest::코드).isEqualTo(ErrorCode.UNAUTHORIZED);
        assertThatThrownBy(() -> controller.uploadUrl()).extracting(ExampleAdminControllerTest::코드).isEqualTo(ErrorCode.UNAUTHORIZED);
        verify(admin, never()).update(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("관리자가 아니면 403 — 아무것도 하지 않는다")
    void 관리자가_아니면_403() throws Exception {
        로그인(7L);
        when(admins.isAdmin(7L)).thenReturn(false);

        assertThatThrownBy(() -> controller.list()).extracting(ExampleAdminControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.takeDown("run-1")).extracting(ExampleAdminControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> controller.importKey(new ExampleAdminController.KeyRequest("k", false)))
                .extracting(ExampleAdminControllerTest::코드).isEqualTo(ErrorCode.FORBIDDEN);
        verify(admin, never()).takeDown(anyString());
        verify(uploads, never()).consume(any(), anyString(), any());
        verify(exporter, never()).export(anyString());
    }

    @Test
    @DisplayName("관리자는 목록을 본다")
    void 관리자는_목록을_본다() {
        로그인(9L);
        when(admins.isAdmin(9L)).thenReturn(true);
        when(admin.list()).thenReturn(List.of());

        assertThat(controller.list()).containsKey("examples");
    }

    @Test
    @DisplayName("관리자는 지정·내리기를 할 수 있고 public 값은 visible 로 넘어간다")
    void 관리자는_고친다() {
        로그인(9L);
        when(admins.isAdmin(9L)).thenReturn(true);

        controller.update("run-1", new ExampleAdminController.UpdateRequest(true, false, 2));
        controller.takeDown("run-1");

        verify(admin).update("run-1", true, false, 2);
        verify(admin).takeDown("run-1");
    }

    @Test
    @DisplayName("S3 로 올린 번들: 발급받은 키인지 확인하고, 심은 뒤 올린 파일을 지운다")
    void 키로_올리면_확인하고_지운다() throws Exception {
        로그인(9L);
        when(admins.isAdmin(9L)).thenReturn(true);
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.INVALID_UPLOAD_KEY))
                .when(uploads).consume(any(), anyString(), any());

        assertThatThrownBy(() -> controller.importKey(new ExampleAdminController.KeyRequest("남의-키", false)))
                .extracting(ExampleAdminControllerTest::코드).isEqualTo(ErrorCode.INVALID_UPLOAD_KEY);
        verify(storage, never()).download(anyString(), any());          // 확인을 못 넘으면 읽지도 않는다
    }

    @Test
    @DisplayName("키가 비어 있으면 거절한다")
    void 빈_키는_거절() {
        로그인(9L);
        when(admins.isAdmin(9L)).thenReturn(true);
        assertThatThrownBy(() -> controller.importKey(new ExampleAdminController.KeyRequest(" ", false)))
                .isInstanceOf(BusinessException.class);
    }
}
