import {request} from '@common/api/client';
import type {ZzalLocale} from './locale';
export interface InquiryInput{requestId:string;body:string;locale:ZzalLocale;petId:number|null}
export interface InquiryReceipt{id:number;createdAt:string}
/** Only a persisted receipt confirms an inquiry; this never sends email. */
export async function submitInquiry(input:InquiryInput,signal?:AbortSignal):Promise<InquiryReceipt>{
 const receipt=await request<InquiryReceipt>('/api/zzal/v1/me/inquiries',{method:'POST',body:input,signal});
 if(!receipt||!Number.isSafeInteger(receipt.id)||receipt.id<=0||typeof receipt.createdAt!=='string'||!Number.isFinite(Date.parse(receipt.createdAt)))throw new Error('Invalid inquiry receipt');
 return receipt;
}
