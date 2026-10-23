import type { ZzalLocale } from '../../lib/locale';

export type Announcement = {
  /** Immutable publication ID. A changed publication must receive a new ID. */
  id: string;
  publishedAt: string;
  automatic?:boolean;
  content: Record<ZzalLocale, { title: string; paragraphs: readonly string[] }>;
};

/** Newest first. Only approved, published copy belongs here; never insert preview content. */
export const RETURNING_UPDATE: Announcement = {
  automatic:false,
  id: '20261023-chat-and-custom-motions',
  publishedAt: '2026-10-23T00:00:00+09:00',
  content: {
    ko: {title:'우리 캐릭터와 더 많은 이야기를 나눠보세요',paragraphs:['이제 캐릭터와 일상과 취향을 나누며 대화를 이어갈 수 있어요','함께 나눈 이야기는 캐릭터의 새로운 움직임으로도 이어져요','성격과 설정을 자세히 적어주면 더욱 캐릭터다운 모습을 만날 수 있어요','기존에 함께하던 캐릭터는 대화 예시를 본 뒤 설정을 확인하고 대화를 시작해요. 전에 나눈 대화와 받은 선물은 그대로예요.']},
    en: {title:'Share more stories with your character',paragraphs:['Keep chatting about your day and the things you love.','Your shared stories can become new motions for your character.','Detailed personality and settings help your character feel more like themselves.','For existing characters, see the examples and confirm their settings before chatting. Your past chats and gifts stay with you.']},
    ja: {title:'キャラクターともっとお話ししてみましょう',paragraphs:['日常や好きなことを話しながら、会話を続けられるようになりました。','一緒に話したことが、キャラクターの新しい動きにもつながります。','性格や設定を詳しく書くと、よりその子らしい姿に出会えます。','これまで一緒に過ごしてきたキャラクターは、会話の例を見て設定を確認してからお話ししましょう。これまでの会話や贈り物はそのままです。']},
  },
};
export const ANNOUNCEMENTS: readonly Announcement[] = [RETURNING_UPDATE];
export const announcementDismissalKey = (id: string, scope: string) => `zzal-announcement-dismissed:v1:${scope}:${id}`;
