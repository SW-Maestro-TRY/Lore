// 도감 API. zzal/be 의 MotionController 와 짝이다.
//
// ★ pet.ts 와 같은 약속 — 여기 타입은 서버의 MotionResponses 를 그대로 옮긴 것이고,
//   화면이 쓰기 편하게 이름을 바꾸거나 값을 계산해 넣지 않는다. 서버가 정본이다.

import { request } from '../api';

/** 이미 연 동작 하나. */
export interface OpenedMotion {
  /** 이 펫의 몇 번째 동작인가. 0부터. */
  seq: number;
  /** 동작 이름(예: '교감1_머리쓰다듬'). */
  /** 내부 이름(실험의 블록 파일명). 화면에는 쓰지 않는다. */
  name: string;
  /** 화면에 보일 이름. 서버가 내부 이름에서 만들어 준다. */
  label: string;
  /**
   * 움짤이 사는 곳. 전체 주소가 아니라 뒷부분이다 — `assetUrl()` 로 조립한다.
   * 직접 CDN 을 앞에 붙이면 `images/` 가 겹쳐 배포에서만 404 가 난다.
   */
  imageKey: string;
  /** 열린 시각(ISO-8601). */
  openedAt: string;
}

/**
 * 도감 — 연 것들과 다 모으면 몇 개인가.
 *
 * ★ 잠긴 자리는 목록에 **없다.** 이름을 미리 내려보내지 않기 때문이다(생성이 실패하면
 *   다른 동작으로 갈아끼워야 하는데, 이름을 약속하면 그때 어길 말이 생긴다).
 *   화면은 `total` 칸을 그리고 앞에서부터 `opened` 로 채운 뒤, 나머지를 이름 없는 빈 칸으로 둔다.
 *
 * ★ `total` 이 0 이면 도감이 완전히 빈다. 그것은 고장이 아니라 "아직 무엇을 열지 안 정했다" 는
 *   정상 상태다(서버 설정 app.zzal.motions 가 정본).
 */
export interface Dex {
  opened: OpenedMotion[];
  total: number;
}

/** 그 펫의 도감. 남의 펫이면 404(ZZAL_PET_NOT_FOUND). */
export function getDex(petId: number, signal?: AbortSignal): Promise<Dex> {
  return request<Dex>(`/api/zzal/v1/me/pets/${petId}/motions`, { signal });
}
