// 관리자 — 문의·후기. zzal/be 의 AdminInquiryController 와 짝이다.
//
// ★ 타입은 손으로 옮긴 것이다(공용 openapi.json·api-schema.ts 는 건드리지 않는다 — 관리자 API 는 운영 명세에 없다).
//   서버 이름(InquiryRepository.AdminRow·Row·Feedback, AdminInquiryService.Detail)을 그대로 쓴다.
// ★ 봇 토큰으로는 403 이다 — 이 탭은 쿠키 로그인한 사람만 쓴다.

import { request } from '@common/api/client';

export const INQUIRY_LIST_PATH = '/api/zzal/v1/admin/inquiries';
export const FEEDBACK_LIST_PATH = '/api/zzal/v1/admin/feedback';

/** 목록 한 줄(서버 InquiryRepository.AdminRow). */
export interface AdminInquiryRow {
  id: number;
  userId: number;
  petId: number | null;
  /** 지금 펫 이름. 펫이 지워졌으면 null. */
  petName: string | null;
  locale: 'ko' | 'en' | 'ja';
  body: string;
  replied: boolean;
  createdAt: string;
  repliedAt: string | null;
  parentId: number | null;
}

/** 사슬 한 칸(서버 InquiryRepository.Row). */
export interface AdminInquiryChainRow {
  id: number;
  userId: number;
  petId: number | null;
  parentId: number | null;
  body: string;
  locale: string;
  createdAt: string;
  replyText: string | null;
  repliedAt: string | null;
  repliedBy: number | null;
  replySeenAt: string | null;
}

/** 후기 한 줄(서버 InquiryRepository.Feedback). */
export interface AdminFeedbackRow {
  id: number;
  userId: number;
  petId: number;
  petName: string | null;
  rating: number;
  tags: string[];
  text: string | null;
  createdAt: string;
}

export interface AdminInquiryDetail {
  inquiry: AdminInquiryRow;
  chain: AdminInquiryChainRow[];
  feedback: AdminFeedbackRow[];
}

export const listInquiries = (days = 30) => request<AdminInquiryRow[]>(`${INQUIRY_LIST_PATH}?days=${days}`);
export const getInquiry = (id: number) => request<AdminInquiryDetail>(`${INQUIRY_LIST_PATH}/${id}`);
/** 답장 한 번. 이미 있으면 409(ZZAL_INQUIRY_ALREADY_REPLIED). */
export const replyInquiry = (id: number, text: string) =>
  request<AdminInquiryDetail>(`${INQUIRY_LIST_PATH}/${id}/reply`, { method: 'POST', body: { text } });
export const listFeedback = (days = 30) => request<AdminFeedbackRow[]>(`${FEEDBACK_LIST_PATH}?days=${days}`);
