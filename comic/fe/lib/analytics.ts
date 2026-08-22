// 프론트 인터랙션만 살린 stub.
//
// 원본(jakae)의 analytics 는 GA4·Clarity·Supabase(/api/log·/api/submit)로 이벤트를
// 내보냈다. comic 탭은 아직 백엔드가 없으므로 여기서는 아무 데도 안 보내고 콘솔에만 남긴다.
// → 나중에 백엔드를 만들면 이 파일의 두 함수 본문만 실제 전송으로 채우면 된다.

type Props = Record<string, unknown>;

export function track(event: string, props: Props = {}): void {
  if (typeof window === "undefined") return;
  if (process.env.NODE_ENV !== "production") {
    console.debug("[track:stub]", event, props);
  }
  // TODO(백엔드 연동): 실제 이벤트 전송
}

export async function trackConversion(event: string, payload: Props = {}): Promise<void> {
  track(event, payload);
  // TODO(백엔드 연동): 실제 저장 API 호출
}
