// 그림 올리기. common/be 의 UploadController · S3Service 와 짝이다.
//
// 흐름은 두 걸음이다:
//   1) 우리 서버에서 presigned URL 과 key 를 받고
//   2) 그 URL 로 브라우저가 S3 에 파일을 직접 PUT 한다
// 파일 바이트가 우리 서버를 안 지나가므로 t3.micro 가 업로드로 멈출 일이 없다.
// 대신 서버는 업로드 순간을 못 보므로, 나중에 key 를 도메인 API 에 넘겨 확정한다.

import { ApiError, request } from './api';

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

  let res: Response;
  try {
    res = await fetch(url, {
      method: 'PUT',
      // ★ 여기에 credentials 를 넣지 않는다. 이 요청은 우리 서버가 아니라 S3 로 간다.
      //   쿠키·인증 헤더가 붙으면 서명에 없던 값이 섞여 SignatureDoesNotMatch 로 거부된다.
      //   fetch 기본값이 same-origin 이라 '안 쓴 것' 이 곧 맞는 설정이지만,
      //   나중에 누가 습관적으로 include 를 넣는 걸 막으려고 이유를 적어 둔다.
      headers: { 'Content-Type': contentType },
      body: file,
    });
  } catch (e) {
    // ★ fetch 가 **던지는** 경우 = 응답을 아예 못 받았다(네트워크 끊김, 버킷 CORS 미설정).
    //   이때 브라우저가 넣는 문구는 영어다("Failed to fetch"). 부르는 쪽(useZzalSession)은
    //   "S3 쪽 문구는 이미 한국어" 라는 전제로 e.message 를 그대로 화면에 띄우므로,
    //   여기서 우리 말로 바꾸지 않으면 사용자가 영어 오류를 보게 된다(실제로 그랬다).
    //   원인은 cause 로 남겨 콘솔에서 추적할 수 있게 둔다.
    throw new Error('이미지를 올리지 못했습니다. 잠시 후 다시 시도해 주세요', { cause: e });
  }

  if (!res.ok) {
    // S3 는 우리 봉투를 모른다. 본문은 XML 이라 사용자에게 보여줄 수 없으므로 상태만 남긴다.
    throw new Error(`이미지를 올리지 못했습니다 (S3 ${res.status})`);
  }

  return key;
}


// ── 실패를 가르는 한 칸 ────────────────────────────────────────────────────

/**
 * 올리기가 왜 실패했나. **사용자 그림 탓인가, 우리 쪽 사정인가.**
 *
 * ★★ 왜 필요한가 — 올리기 칸에는 「이런 그림은 어려워요」 예시가 붙어 있다. 연결이 끊기거나
 *   S3 가 거절해서 실패해도 그 예시가 오류 한 줄 바로 아래 그대로 남아서, 사용자가
 *   **제 그림 탓으로 읽었다.** 실제로는 그림을 한 번 보지도 못한 실패다.
 *   그래서 화면이 둘을 갈라 그릴 수 있게, 여기서 한 칸으로 답해 준다.
 *
 * ★ 올리는 단계에서 **그림 탓인 실패는 사실상 한 가지뿐**이다 — 서버가
 *   `ZZAL_PET_HATCH_FAILED`("이 그림으로는 그리기가 어려웠어요")를 줄 때.
 *   presign 400·401·5xx, S3 403/CORS, 네트워크 끊김은 전부 우리 쪽 사정이다
 *   (업로드 서버는 파일 내용을 보지 않는다 — MIME 검사조차 없다).
 * ★ 막힘(`ZZAL_HATCH_BLOCKED_*`)은 여기 안 온다. 그건 오류가 아니라 안내라서
 *   `readHatchBlocked` 가 먼저 걷어 간다(→ `lib/hatchBlocked.ts`).
 */
export type UploadFailure = 'image' | 'infra';

/** 서버가 "이 그림으로는 못 그리겠다" 고 답하는 코드. `common/be` 의 ErrorCode 와 같은 이름. */
const IMAGE_REJECTED = 'ZZAL_PET_HATCH_FAILED';

export function classifyUploadFailure(e: unknown): UploadFailure {
  return e instanceof ApiError && e.code === IMAGE_REJECTED ? 'image' : 'infra';
}

/**
 * 화면에 띄울 실패 한 줄. **영어가 새는 것을 막는 자리**다.
 *
 * ★★ 왜 필요한가 — presign(우리 서버)이 네트워크 단에서 실패하면 공통 클라이언트는 브라우저가
 *   만든 `TypeError: Failed to fetch` 를 그대로 던진다. 부르는 쪽이 `e.message` 를 띄우므로
 *   사용자가 **영어 오류를 본다**(2026-09-20 실패 주입으로 실측). S3 PUT 쪽은 이미 위에서
 *   막아 두었는데 presign 쪽 길이 뚫려 있었다.
 * ★ 판정 기준을 예외의 종류가 아니라 **한글이 들어 있는가**로 잡은 이유 — 우리가 만든 줄과
 *   서버 봉투(ApiError.message)는 전부 한국어다. 반대로 브라우저·런타임이 만든 문구는
 *   전부 영어다. 새 실패 경로가 생겨도 이 규칙은 그대로 맞는다.
 */
const NETWORK_LINE = '이미지를 올리지 못했습니다. 잠시 후 다시 시도해 주세요';
const HANGUL = /[가-힣]/;

export function uploadFailureLine(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error && HANGUL.test(e.message)) return e.message;
  return NETWORK_LINE;
}
