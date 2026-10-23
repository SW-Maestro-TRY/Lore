import { request } from '@common/api/client';
import { PET_BASE } from './pet';
import { readZzalLocale } from './locale';

export interface FirstMeetingStatus {
  version: 1;
  state: 'DISCOVERED' | 'ADDRESS' | 'READY_TO_ENTER' | 'COMPLETE';
  callMe: string | null;
  addressStatus: 'UNPROVIDED' | 'DEFERRED' | 'EXPLICIT' | 'LEGACY';
  discoveryLine: string;
  greetingLine: string;
  greetingRetryAllowed: boolean;
  discoveryStatus?: 'PENDING' | 'READY' | 'FAILED' | 'UNCERTAIN' | 'SUPERSEDED' | null;
  discoverySource?: 'LLM' | 'FALLBACK' | null;
}
export type FirstMeetingAction =
  | { action: 'NEXT' | 'DEFER_ADDRESS' | 'ENTER_ROOM' }
  | { action: 'SET_ADDRESS'; callMe: string };
export function getFirstMeeting(petId: number, signal?: AbortSignal): Promise<FirstMeetingStatus> {
  return request(`${PET_BASE}/${petId}/first-meeting`, { signal });
}
export function advanceFirstMeeting(petId: number, action: FirstMeetingAction): Promise<FirstMeetingStatus> {
  return request(`${PET_BASE}/${petId}/first-meeting`, { method: 'POST', headers: { 'X-Zzal-Language': readZzalLocale() }, body: action });
}

/** Separate purpose: never advances meeting state or fabricates a user answer. */
export function prepareFirstMeetingDiscovery(petId:number,signal?:AbortSignal):Promise<FirstMeetingStatus>{
 return request(`${PET_BASE}/${petId}/first-meeting`,{method:'POST',signal,headers:{'X-Zzal-Language':readZzalLocale()},body:{action:'PREPARE_DISCOVERY'}});
}
