/* 이미지를 S3 에 **직접** 올린다 (팀 공용).
 *
 * 서버는 파일 바이트를 안 만진다 — 임시 주소만 내주고, 브라우저가 그 주소로
 * S3 에 바로 PUT 한다. 그래서 t3.micro 가 업로드로 안 멈춘다.
 *
 *     1. presign 으로 { key, url } 을 받는다
 *     2. 그 url 로 파일을 PUT 한다 (브라우저 → S3)
 *     3. 받은 key 를 자기 도메인 API 에 넘긴다
 *
 * 주소는 10분간 유효하다. **로그인이 필요하다** — 티켓이 계정에 묶여야
 * 남의 키를 적어 넣는 것을 서버가 막을 수 있다.
 */
import { request } from "./client";

export interface PresignedUpload {
  /** S3 안의 자리. 이 값을 도메인 API 에 넘긴다. */
  key: string;
  /** 여기로 PUT 한다. 10분 뒤 만료. */
  url: string;
}

/** 키 폴더. 서버가 이 넷만 받는다. */
export type UploadDomain = "webtoon" | "zzal" | "trailer" | "common";

/** 올릴 자리를 받는다. */
export function presign(domain: UploadDomain, contentType: string) {
  return request<PresignedUpload>("/api/v1/uploads/presign", {
    method: "POST",
    body: { domain, contentType },
  });
}

/**
 * 파일 하나를 올리고 **key** 를 돌려준다.
 *
 * `fetch` 를 그냥 쓴다 — 여기서 나가는 곳은 우리 서버가 아니라 S3 라,
 * 공통 클라이언트가 붙이는 토큰·봉투 규칙이 오히려 방해가 된다(서명에
 * 안 들어간 헤더가 붙으면 S3 가 403 을 낸다).
 *
 * `Content-Type` 은 **서명할 때 쓴 것과 똑같아야 한다.** 다르면 S3 가
 * 거절한다 — 그래서 presign 에 넘긴 값을 그대로 다시 쓴다.
 */
export async function uploadImage(file: File, domain: UploadDomain): Promise<string> {
  const type = file.type || "application/octet-stream";
  const { key, url } = await presign(domain, type);

  const res = await fetch(url, {
    method: "PUT",
    headers: { "Content-Type": type },
    body: file,
  });
  if (!res.ok) {
    throw new Error(`사진을 올리지 못했습니다 (${res.status})`);
  }
  return key;
}

/** 여러 장. 하나라도 실패하면 통째로 실패한다 — 반만 올라간 상태로 만들지 않는다. */
export function uploadImages(files: File[], domain: UploadDomain): Promise<string[]> {
  return Promise.all(files.map((f) => uploadImage(f, domain)));
}

/**
 * 화면이 들고 있는 data URL 을 그대로 올린다.
 *
 * 미리보기 때문에 사진을 data URL 로 들고 있는 화면이 많다(위저드가 그렇다).
 * 그 화면을 File 로 바꾸려면 미리보기·삭제·순서까지 다 손대야 해서, **보낼
 * 때만** 여기서 되돌린다 — 미리보기 코드는 그대로 둔다.
 *
 * data URL 이 아닌 것은 건너뛴다(이미 key 인 경우 등).
 */
export async function uploadDataUrls(urls: string[], domain: UploadDomain): Promise<string[]> {
  const files = await Promise.all(
    urls
      .filter((u) => typeof u === "string" && u.startsWith("data:"))
      .map(async (u, i) => {
        const blob = await (await fetch(u)).blob();
        const ext = (blob.type.split("/")[1] || "png").replace(/[^a-z0-9]/gi, "");
        return new File([blob], `photo${i + 1}.${ext}`, { type: blob.type });
      }),
  );
  return uploadImages(files, domain);
}
