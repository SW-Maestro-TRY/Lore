// 그림 올리기. common/be 의 UploadController · S3Service 와 짝이다.
//
// 흐름은 두 걸음이다:
//   1) 우리 서버에서 presigned URL 과 key 를 받고
//   2) 그 URL 로 브라우저가 S3 에 파일을 직접 PUT 한다
// 파일 바이트가 우리 서버를 안 지나가므로 t3.micro 가 업로드로 멈출 일이 없다.
// 대신 서버는 업로드 순간을 못 보므로, 나중에 key 를 도메인 API 에 넘겨 확정한다.

import { request } from './api';

/** 키가 들어갈 폴더. 서버 ALLOWED_DOMAINS 밖의 값은 400 이다. */
export type UploadDomain = 'zzal' | 'webtoon' | 'trailer' | 'common';

/** presign 발급 결과(서버 S3Service.PresignedUpload). */
export interface PresignedUpload {
  /** 도메인 API 에 넘길 값. 예: images/zzal/<uuid> */
  key: string;
  /** 파일을 PUT 할 임시 주소. 10분간 유효하다. */
  url: string;
}

/** 업로드 주소를 받는다. 발급 기록이 서버에 남아 나중에 key 를 대조하는 근거가 된다. */
export function presign(domain: UploadDomain, contentType: string): Promise<PresignedUpload> {
  return request<PresignedUpload>('/api/v1/uploads/presign', {
    method: 'POST',
    body: { domain, contentType },
  });
}

/**
 * 파일을 올리고 key 를 돌려준다. 화면은 이 함수 하나만 부르면 된다.
 *
 * 돌려주는 key 는 그대로 createPet({ imageKey }) 에 넣는다. 한 key 는 한 번만 쓸 수 있다
 * (두 번째부터 UPLOAD_KEY_ALREADY_USED). 그러니 실패하면 이 함수를 처음부터 다시 부른다 —
 * 같은 key 로 PUT 만 재시도하지 말 것.
 */
export async function uploadImage(file: File, domain: UploadDomain = 'zzal'): Promise<string> {
  // ★ contentType 은 presign 때 준 것과 PUT 헤더가 **완전히 같아야** 한다.
  //   서버가 이 값을 서명에 넣기 때문에, 한 글자라도 다르면 S3 가 403 을 낸다.
  //   그래서 file.type 을 한 번만 읽어 두 곳에 같이 쓴다(브라우저가 빈 문자열을 줄 때도 있어
  //   그때는 양쪽 모두 이 기본값이 된다).
  const contentType = file.type || 'application/octet-stream';

  const { key, url } = await presign(domain, contentType);

  const res = await fetch(url, {
    method: 'PUT',
    // ★ 여기에 credentials 를 넣지 않는다. 이 요청은 우리 서버가 아니라 S3 로 간다.
    //   쿠키·인증 헤더가 붙으면 서명에 없던 값이 섞여 SignatureDoesNotMatch 로 거부된다.
    //   fetch 기본값이 same-origin 이라 '안 쓴 것' 이 곧 맞는 설정이지만,
    //   나중에 누가 습관적으로 include 를 넣는 걸 막으려고 이유를 적어 둔다.
    headers: { 'Content-Type': contentType },
    body: file,
  });

  if (!res.ok) {
    // S3 는 우리 봉투를 모른다. 본문은 XML 이라 사용자에게 보여줄 수 없으므로 상태만 남긴다.
    throw new Error(`이미지를 올리지 못했습니다 (S3 ${res.status})`);
  }

  return key;
}
