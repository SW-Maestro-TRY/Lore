import { request } from "@common/api/client";
import { BASE } from "./api";
const C = `${BASE}/community`;
export type Subscription = {
  subscribed: boolean;
  subscribers: number;
  episode: number;
};
export type Comment = {
  id: number;
  body: string;
  author: string;
  mine: boolean;
  created_at: string;
  updated_at: string;
};
export type Notice = {
  id: number;
  kind: string;
  run_id: string;
  message: string;
  created_at: string;
  read_at: string | null;
};
export type Followed = {
  root_job: string;
  run_id: string | null;
  available: boolean;
  created_at: string;
};
export type PublicCharacter = {
  id: string;
  name: string;
  description: string;
  art_url: string | null;
  creator: string;
};
export type Attribution = {
  character_id: string;
  character_name: string;
  creator: string | null;
  available: boolean;
};
export type Report = {
  id: number;
  kind: string;
  target: string;
  reason: string;
  content: string | null;
};
const runPath = (run: string) => `${C}/runs/${encodeURIComponent(run)}`;
export const subscription = (run: string) =>
  request<Subscription>(`${runPath(run)}/subscription`);
export const follow = (run: string, on: boolean) =>
  request<Subscription>(`${runPath(run)}/subscription`, {
    method: on ? "PUT" : "DELETE",
  });
export const comments = (run: string, before = 0) =>
  request<Comment[]>(`${runPath(run)}/comments?before=${before}`);
export const addComment = (run: string, body: string) =>
  request<{ id: number }>(`${runPath(run)}/comments`, {
    method: "POST",
    body: { body },
  });
export const editComment = (id: number, body: string) =>
  request(`${C}/comments/${id}`, { method: "PUT", body: { body } });
export const deleteComment = (id: number) =>
  request(`${C}/comments/${id}`, { method: "DELETE" });
export const notices = () => request<Notice[]>(`${C}/notices`);
export const readNotice = (id: number) =>
  request(`${C}/notices/${id}/read`, { method: "POST" });
export const subscriptions = () => request<Followed[]>(`${C}/subscriptions`);
export const unfollowRoot = (root: string) =>
  request(`${C}/subscriptions/${encodeURIComponent(root)}`, {
    method: "DELETE",
  });
export const publicCharacters = (page = 0) =>
  request<PublicCharacter[]>(`${C}/characters?page=${page}`);
export const publicCharacter = (id: string) =>
  request<PublicCharacter>(`${C}/characters/${encodeURIComponent(id)}`);
export const publishCharacter = (
  id: string,
  published: boolean,
  agreed = false,
) =>
  request<{ published: boolean }>(
    `${C}/characters/${encodeURIComponent(id)}/publication`,
    { method: "PUT", body: { published, agreed } },
  );
export const attribution = (run: string) =>
  request<Attribution[]>(`${runPath(run)}/attribution`);
export const report = (kind: string, target: string, reason: string) =>
  request(`${C}/reports`, { method: "POST", body: { kind, target, reason } });
export const reports = () => request<Report[]>(`${C}/reports`);
export const resolveReport = (id: number, hide: boolean) =>
  request(`${C}/reports/${id}/resolve`, { method: "POST", body: { hide } });
export const errorText = (e: unknown) =>
  e instanceof Error
    ? e.message
    : "요청을 완료하지 못했어요. 다시 시도해 주세요.";
