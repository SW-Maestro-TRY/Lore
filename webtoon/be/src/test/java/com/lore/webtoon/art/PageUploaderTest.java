package com.lore.webtoon.art;

import com.lore.common.s3.S3Storage;
import com.lore.webtoon.work.WorkLedger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PageUploaderTest {

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 320, 1080})
    void 다시_올릴_때는_모든_폭이_올라가야_주소를_바꾼다(int failing) throws Exception {
        S3Storage storage = mock(S3Storage.class);
        PageStore pages = mock(PageStore.class);
        StringBuilder list = new StringBuilder();
        for (int width : new int[]{0, 320, 1080}) {
            Path file = Files.writeString(dir.resolve(width + (width == 0 ? ".png" : ".jpg")), "그림");
            if (!list.isEmpty()) list.append(',');
            list.append("{\"page_no\":2,\"width\":%d,\"path\":\"%s\",\"bytes\":6}".formatted(width, file));
            if (width == failing) {
                doThrow(new IllegalStateException("창고")).when(storage).upload(anyString(), eq(file), anyString());
            }
        }
        new PageUploader(storage, pages, mock(WorkLedger.class), "bucket")
                .uploadWhole("run", "{\"run\":[" + list + "]}", line -> { });
        verify(storage, failing < 0 ? never() : times(1)).delete(argThat(keys -> keys.size() == 2));   // 올리다 만 둘은 지운다
        verify(pages, failing < 0 ? times(1) : never()).record(eq("run"), any(), anyBoolean());
    }
}
