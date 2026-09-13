package com.lore.zzal.generation.client;

import com.lore.common.s3.S3Storage;
import com.lore.zzal.generation.PipelineScripts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 후처리 세션 — <b>1층과 2층이 같은 작업 폴더를 쓴다.</b>
 *
 * <h3>★★ 무엇을 지키나</h3>
 * v4 후처리는 그림 옆에 {@code anchors.json} 을 같이 내고, 2층이 1층이 낸 그 파일에 합쳐 쓴다.
 * 예전에는 자르는 메서드가 자기 안에서 임시 폴더를 만들고 {@code finally} 로 지워서,
 * 폴더를 공유하려 해도 <b>1층이 지운 폴더를 2층이 보게</b> 됐다. 그 어긋남은 예외가 안 나고
 * 앵커만 반쪽이 되어 <b>화면에서만</b> 드러난다.
 *
 * <h3>★ 파이썬을 실제로 돌리지 않는다</h3>
 * 스크립트 실행 자리만 갈아 끼워 <b>파일이 어떻게 오가는지</b>를 본다. 진짜 파이썬은 numpy·pillow 가
 * 필요하고 몇 초씩 걸려, 매번 돌 시험으로는 못 쓴다. 여기서 보는 것은 자바 쪽 심부름이다.
 */
@DisplayName("후처리 세션 — 작업 폴더 공유와 앵커")
class PostProcessSessionTest {

    private static final List<String> LAYER1 = List.of("base", "eat");
    private static final List<String> LAYER2 = List.of("sweep", "wash");

    /** 스크립트가 한 것처럼 파일을 남기는 대역. 호출마다 무엇을 받았는지도 적어 둔다. */
    private static final class FakeScript extends PythonPostProcessor {

        private final List<List<String>> made;
        private final List<Path> grids = new ArrayList<>();
        private final List<Path> logs = new ArrayList<>();
        /** 이 호출에서는 파일을 새로 쓰지 않고, 앞 호출이 남긴 것을 <b>옛 시각 그대로</b> 둔다. */
        private final boolean leaveStale;
        private final boolean writeAnchors;
        private int call;

        FakeScript(S3Storage storage, PipelineScripts scripts, List<List<String>> made,
                   boolean writeAnchors, boolean leaveStale) {
            super(storage, scripts, "python3", 5, v -> LAYER1);
            this.made = made;
            this.writeAnchors = writeAnchors;
            this.leaveStale = leaveStale;
        }

        @Override
        protected void exec(String scriptPath, Path grid, Path out, List<String> extraArgs, Path logFile)
                throws IOException {
            grids.add(grid);
            logs.add(logFile);
            Files.createDirectories(out);
            Files.writeString(logFile, "fake run %d".formatted(call + 1));
            List<String> keys = made.get(Math.min(call, made.size() - 1));
            for (String k : keys) {
                Path f = out.resolve(k + ".webp");
                Files.writeString(f, k);
                if (leaveStale) {
                    // 스크립트가 이번 호출에서는 안 건드린 셈 — 파일만 남아 있다.
                    Files.setLastModifiedTime(f, FileTime.fromMillis(0));
                }
            }
            if (writeAnchors) {
                // 파이썬이 하는 일과 같다 — 이미 있으면 합쳐 쓴다(여기서는 이어 붙이기로 흉내).
                Path a = out.resolve("anchors.json");
                String prior = Files.exists(a) ? Files.readString(a) : "";
                Files.writeString(a, prior + String.join(",", keys) + ";");
            }
            call++;
        }
    }

    private static PipelineScripts scripts() {
        PipelineScripts scripts = mock(PipelineScripts.class);
        when(scripts.script(anyString(), anyString())).thenReturn("service_post.py");
        return scripts;
    }

    @Test
    @DisplayName("★★ 1층·2층이 같은 폴더를 본다 — 2층이 1층 앵커에 합쳐 쓴다")
    void bothLayersShareOneWorkDirectory() throws Exception {
        S3Storage storage = mock(S3Storage.class);
        FakeScript p = new FakeScript(storage, scripts(), List.of(LAYER1, LAYER2), true, false);

        try (PostProcessor.Session s = p.open("images/zzal/pets/7/basic/1", "v4")) {
            s.split("grid1.png", LAYER1, "base=standing,eat=standing");
            s.split("grid2.png", LAYER2, "sweep=standing,wash=crouch");
        }

        // 두 호출의 출력 폴더가 같아야 파이썬이 1층 앵커를 읽어 합칠 수 있다.
        assertThat(p.grids.get(0).getParent()).isEqualTo(p.grids.get(1).getParent());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        verify(storage, times(5)).upload(key.capture(), any(Path.class), type.capture());
        assertThat(key.getAllValues()).containsExactly(
                "images/zzal/pets/7/basic/1/base.webp",
                "images/zzal/pets/7/basic/1/eat.webp",
                "images/zzal/pets/7/basic/1/sweep.webp",
                "images/zzal/pets/7/basic/1/wash.webp",
                "images/zzal/pets/7/basic/1/anchors.json");
        // ★ 앵커는 세션을 닫을 때 한 번만. 층마다 올리면 2층이 실패했을 때 반쪽 앵커가 남는다.
        assertThat(key.getAllValues()).filteredOn(k -> k.endsWith("anchors.json")).hasSize(1);
        // ★ 형식을 안 박으면 S3 가 기본 형식으로 내려보내고 브라우저 파서가 거부한다.
        assertThat(type.getAllValues().get(4)).isEqualTo("application/json");
    }

    @Test
    @DisplayName("★ 작업 폴더 안의 격자·로그 이름을 층마다 가른다 — 2층이 1층 기록을 덮으면 판정 근거가 사라진다")
    void perLayerNamesInsideTheWorkDirectory() throws Exception {
        FakeScript p = new FakeScript(mock(S3Storage.class), scripts(), List.of(LAYER1, LAYER2), true, false);

        try (PostProcessor.Session s = p.open("images/zzal/pets/7/basic/1", "v4")) {
            s.split("grid1.png", LAYER1, "");
            s.split("grid2.png", LAYER2, "");
        }

        assertThat(p.grids.get(0).getFileName()).isNotEqualTo(p.grids.get(1).getFileName());
        assertThat(p.logs.get(0).getFileName()).isNotEqualTo(p.logs.get(1).getFileName());
    }

    @Test
    @DisplayName("★★ v4 에서 앵커가 없으면 실패로 올린다 — 그림만 올라가는 모양은 화면을 봐야만 드러난다")
    void v4FailsWhenAnchorsAreMissing() {
        FakeScript p = new FakeScript(mock(S3Storage.class), scripts(), List.of(LAYER1), false, false);

        assertThatThrownBy(() -> {
            try (PostProcessor.Session s = p.open("images/zzal/pets/7/basic/1", "v4")) {
                s.split("grid1.png", LAYER1, "");
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("anchors.json");
    }

    @Test
    @DisplayName("v1·v2 는 앵커를 안 낸다 — 그 버전에서 없는 것은 정상이다")
    void olderVersionsPassWithoutAnchors() throws Exception {
        FakeScript p = new FakeScript(mock(S3Storage.class), scripts(), List.of(LAYER1), false, false);

        try (PostProcessor.Session s = p.open("images/zzal/pets/7/basic/1", "v2")) {
            s.split("grid1.png", LAYER1);
        }
    }

    @Test
    @DisplayName("★★ 앞 층이 남긴 파일은 이번 층 검사를 통과하지 못한다 — 2층 실패가 1층 그림으로 메워지면 안 된다")
    void aFileLeftByTheEarlierLayerDoesNotPass() {
        // 스크립트가 이번 호출에서 아무것도 새로 쓰지 않고 앞 호출의 파일만 남긴 상황.
        FakeScript p = new FakeScript(mock(S3Storage.class), scripts(), List.of(LAYER1), true, true);

        assertThatThrownBy(() -> {
            try (PostProcessor.Session s = p.open("images/zzal/pets/7/basic/1", "v4")) {
                s.split("grid1.png", LAYER1, "");
            }
        }).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base");
    }
}
