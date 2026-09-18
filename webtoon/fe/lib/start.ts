/* 웹툰 만들기를 **시작하는** 한 곳. 위자드(4걸음)와 캐릭터 카드의 「이 캐릭터로
 * 1화 보기」가 같은 함수를 부른다 — 두 벌이면 사진 올리는 길·보내는 칸이 어긋난다.
 *
 * 사진은 S3 로 먼저 올린다. 본문에 data URL 로 실으면 사진 한 장만 커져도
 * 요청이 1MB 를 넘고, CloudFront 앞단 WAF 가 통째로 막는다(2026-09-17 실측).
 * 로그인한 사람은 팀 공용 presign 을, 게스트는 webtoon 전용 presign 을 쓴다.
 * 올리다 실패하면 data URL 로 되돌린다 — 사진 길이 잠깐 막혔다고 만들기가
 * 통째로 죽으면 안 된다. */
import { uploadDataUrls } from "@common/api/uploads";
import { createJob, uploadDataUrlsAsGuest } from "./api";
import type { WizardForm } from "./wizardData";

export async function startJob(form: WizardForm, authenticated: boolean): Promise<string> {
  let keys: string[] | undefined;
  if (form.photos.length) {
    try {
      keys = authenticated
        ? await uploadDataUrls(form.photos, "webtoon")
        : await uploadDataUrlsAsGuest(form.photos);
    } catch {
      keys = undefined;
    }
  }
  const got = await createJob({
    name: form.name.trim(),
    character: form.character.trim(),
    photo_note: "",
    fields: {},
    genre: form.genre.trim(),
    story: form.story.trim(),
    style: form.style,
    quality: form.quality,
    photos_data: keys ? [] : form.photos,
    photo_keys: keys,
    character_id: form.characterId,
    agree_ip: form.agreeIp,
    checkpoints: form.mode === "expert",
  });
  return got.id;
}
