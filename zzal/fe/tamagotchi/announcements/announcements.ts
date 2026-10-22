import type { ZzalLocale } from '../../lib/locale';

export type Announcement = {
  /** Immutable publication ID. A changed publication must receive a new ID. */
  id: string;
  publishedAt: string;
  content: Record<ZzalLocale, { title: string; paragraphs: readonly string[] }>;
};

/** Newest first. Only approved, published copy belongs here; never insert preview content. */
export const ANNOUNCEMENTS: readonly Announcement[] = [];
export const announcementDismissalKey = (id: string, scope: string) => `zzal-announcement-dismissed:v1:${scope}:${id}`;
