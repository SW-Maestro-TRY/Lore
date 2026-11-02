import {request} from '@common/api/client';
import type {ZzalLocale} from './locale';
/** parentId — 더 물어보기(앞 문의 번호, 본인 것만). 첫 문의는 null. */
export interface InquiryInput{requestId:string;body:string;locale:ZzalLocale;petId:number|null;parentId?:number|null}
export interface InquiryReceipt{id:number;createdAt:string}
/** 운영팀 답장 — 화면은 캐릭터가 아니라 「로어 운영팀」 시스템 문구로 그린다. */
export interface InquiryReply{text:string;repliedAt:string}
/**
 * 내 문의 한 줄(GET /api/zzal/v1/me/inquiries). 손으로 쓴 타입이다 — 공용 api-schema.ts 는 이번에 다시 만들지 않았다.
 * replyUnseen: 답장이 있고 아직 안 봤으면 true(시트를 열면 seen 을 보내 지운다).
 */
export interface InquiryItem{id:number;petId:number|null;body:string;createdAt:string;reply:InquiryReply|null;parentId:number|null;replyUnseen:boolean}
/** Only a persisted receipt confirms an inquiry; this never sends email. */
export async function submitInquiry(input:InquiryInput,signal?:AbortSignal):Promise<InquiryReceipt>{
 const receipt=await request<InquiryReceipt>('/api/zzal/v1/me/inquiries',{method:'POST',body:input,signal});
 if(!receipt||!Number.isSafeInteger(receipt.id)||receipt.id<=0||typeof receipt.createdAt!=='string'||!Number.isFinite(Date.parse(receipt.createdAt)))throw new Error('Invalid inquiry receipt');
 return receipt;
}
/** 내 문의 전부, 오래된 것부터. */
export async function listInquiries(signal?:AbortSignal):Promise<InquiryItem[]>{
 const items=await request<InquiryItem[]>('/api/zzal/v1/me/inquiries',{signal});
 if(!Array.isArray(items))throw new Error('Invalid inquiry list');
 return items;
}
/** 이 문의의 답장을 봤다 — 204, 멱등. */
export function inquirySeen(id:number):Promise<void>{
 return request<void>(`/api/zzal/v1/me/inquiries/${id}/seen`,{method:'POST'});
}
