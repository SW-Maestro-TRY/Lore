package com.lore.zzal.archive;

import com.lore.common.s3.S3Storage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 보관 배치가 <b>어느 버킷</b>에 올리는지를 정하는 한 자리.
 *
 * <h3>★★ 그림 버킷을 재활용하지 않는다</h3>
 * 공용 {@code S3Storage} 빈은 {@code app.s3.content-bucket} 에 묶여 있고, 그 버킷은
 * CloudFront 가 {@code /images/*} 를 <b>공개로</b> 내보내는 곳이다. 행동 기록에는 익명 번호와
 * 로그인 번호가 줄마다 들어간다 — 주소를 아는 사람이 받아 갈 수 있는 자리에 둘 수 없다.
 *
 * <p>그래서 <b>보관 버킷으로 {@code S3Storage} 를 하나 더 만들어</b> 쓴다. 공용 클래스는
 * 한 글자도 고치지 않는다 — 생성자가 이미 버킷을 밖에서 받게 돼 있어서 그 자리가 곧 이음매다.
 *
 * <h3>★ 빈 이름을 {@code zzalArchiveStorage} 로 따로 둔다</h3>
 * 타입이 {@link ArchiveStorage} 라 공용 {@code S3Storage} 빈과 경쟁하지 않는다. 누가 실수로
 * {@code S3Storage} 를 주입받아 보관에 쓰는 일이 <b>타입 수준에서</b> 안 된다.
 *
 * <h3>★ 버킷이 비어 있어도 빈은 만든다</h3>
 * 대신 {@link EventArchiveSettings#blockedReason()} 이 배치를 안 돌게 막는다. 여기서 예외를
 * 던지면 보관 설정 하나 때문에 서버가 통째로 안 뜬다 — 보관은 곁다리라 그 거래는 맞지 않는다.
 */
@Configuration
public class EventArchiveConfig {

    @Bean
    public ArchiveStorage zzalArchiveStorage(S3Client s3Client, EventArchiveSettings settings) {
        return new S3ArchiveStorage(new S3Storage(s3Client, settings.bucket()));
    }
}
