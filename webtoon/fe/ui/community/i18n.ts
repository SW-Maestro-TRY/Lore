import { registerDict, type Dict } from "../../lib/i18n";
const dict: Dict = {
  "총 {n}화": { en: "{n} episodes", ja: "全{n}話", zh: "共 {n} 话" },
  "구독자 {n}명": { en: "{n} subscribers", ja: "購読者 {n}人", zh: "{n} 位订阅者" },

  "작품 구독과 회차 댓글": {
    en: "Follow and discuss this episode",
    ja: "作品のフォローと各話のコメント",
    zh: "关注作品与本话评论",
  },
  "캐릭터 원작:": {
    en: "Original character:",
    ja: "キャラクター原作者：",
    zh: "角色原作：",
  },
  원작자: {
    en: "Original creator",
    ja: "原作者",
    zh: "原作者",
  },
  "· 현재 비공개": {
    en: "· currently private",
    ja: "・現在非公開",
    zh: "· 当前未公开",
  },
  "다음 이야기도 함께해요": {
    en: "Stay for the next chapter",
    ja: "次のお話も一緒に",
    zh: "一起期待下一话",
  },
  "구독하면 새 화 소식을 마이페이지 알림에서 볼 수 있어요.": {
    en: "Follow this story to see new episode alerts in My Page.",
    ja: "フォローすると、新しい話のお知らせをマイページで確認できます。",
    zh: "关注作品后，可在个人主页查看新话通知。",
  },
  "{n}명이 이 작품을 기다려요": {
    en: "{n} readers are following this story",
    ja: "{n}人がこの作品を待っています",
    zh: "{n}位读者正在等待这部作品",
  },
  "로그인하면 작품을 구독할 수 있어요. 화면 위 로그인 버튼을 눌러 주세요.": {
    en: "Sign in using the button at the top to follow this story.",
    ja: "画面上部のログインボタンからログインすると、作品をフォローできます。",
    zh: "请点击页面顶部的登录按钮，登录后即可关注作品。",
  },
  "구독 취소": {
    en: "Unfollow",
    ja: "フォロー解除",
    zh: "取消关注",
  },
  "작품 구독": {
    en: "Follow story",
    ja: "作品をフォロー",
    zh: "关注作品",
  },
  "{n}화 이야기": {
    en: "Episode {n} discussion",
    ja: "第{n}話の感想",
    zh: "第{n}话讨论",
  },
  "인상 깊은 장면이나 다음 화에 대한 기대를 나눠 주세요. 스포일러가 포함될 수 있어요.":
    {
      en: "Share your favorite moments or hopes for the next episode. Comments may contain spoilers.",
      ja: "印象に残った場面や次の話への期待を共有しましょう。ネタバレを含む場合があります。",
      zh: "分享难忘的场景或对下一话的期待。评论可能含有剧透。",
    },
  "회차 댓글": {
    en: "Episode comment",
    ja: "この話へのコメント",
    zh: "本话评论",
  },
  "이 화는 어땠나요?": {
    en: "What did you think of this episode?",
    ja: "この話はいかがでしたか？",
    zh: "你觉得这一话怎么样？",
  },
  "댓글 남기기": {
    en: "Post comment",
    ja: "コメントを投稿",
    zh: "发表评论",
  },
  "로그인하면 댓글을 남길 수 있어요. 화면 위 로그인 버튼을 눌러 주세요.": {
    en: "Sign in using the button at the top to leave a comment.",
    ja: "画面上部のログインボタンからログインすると、コメントできます。",
    zh: "请点击页面顶部的登录按钮，登录后即可评论。",
  },
  "다시 불러오기": {
    en: "Reload",
    ja: "再読み込み",
    zh: "重新加载",
  },
  "대화를 불러오는 중…": {
    en: "Loading comments…",
    ja: "コメントを読み込み中…",
    zh: "正在加载评论…",
  },
  "아직 댓글이 없어요. 첫 감상을 남겨 주세요.": {
    en: "No comments yet. Be the first to share your thoughts.",
    ja: "まだコメントはありません。最初の感想をどうぞ。",
    zh: "还没有评论，来分享第一条感想吧。",
  },
  " · 나": {
    en: " · you",
    ja: "・自分",
    zh: "· 我",
  },
  "수정됨": {
    en: "Edited",
    ja: "編集済み",
    zh: "已编辑",
  },
  "댓글 수정": {
    en: "Edit comment",
    ja: "コメントを編集",
    zh: "编辑评论",
  },
  저장: {
    en: "Save",
    ja: "保存",
    zh: "保存",
  },
  취소: {
    en: "Cancel",
    ja: "キャンセル",
    zh: "取消",
  },
  "수정": {
    en: "Edit",
    ja: "編集",
    zh: "编辑",
  },
  "댓글을 지울까요?": {
    en: "Delete this comment?",
    ja: "このコメントを削除しますか？",
    zh: "删除这条评论吗？",
  },
  "삭제 확인": {
    en: "Confirm deletion",
    ja: "削除を確定",
    zh: "确认删除",
  },
  삭제: {
    en: "Delete",
    ja: "削除",
    zh: "删除",
  },
  "신고": {
    en: "Report",
    ja: "通報",
    zh: "举报",
  },
  "이전 댓글 더 보기": {
    en: "Load older comments",
    ja: "以前のコメントを見る",
    zh: "查看更多评论",
  },
  "구독한 작품": {
    en: "Followed story",
    ja: "フォロー中の作品",
    zh: "已关注作品",
  },
  "구독과 알림": {
    en: "Following and notifications",
    ja: "フォローとお知らせ",
    zh: "关注与通知",
  },
  "기다리던 이야기": {
    en: "Stories you are waiting for",
    ja: "待っていたお話",
    zh: "期待的故事",
  },
  새로고침: {
    en: "Refresh",
    ja: "更新",
    zh: "刷新",
  },
  "소식을 불러오는 중…": {
    en: "Loading updates…",
    ja: "お知らせを読み込み中…",
    zh: "正在加载消息…",
  },
  "알림": {
    en: "Notifications",
    ja: "お知らせ",
    zh: "通知",
  },
  "· 읽지 않음 {n}": {
    en: "· {n} unread",
    ja: "・未読{n}件",
    zh: "· {n}条未读",
  },
  "아직 새 소식이 없어요. 구독한 작품의 새 화와 내 작품 댓글, 캐릭터로 만든 작품을 여기서 알려 드려요.":
    {
      en: "No updates yet. New episodes, comments on your stories, and stories using your characters will appear here.",
      ja: "まだお知らせはありません。フォロー中の作品の新しい話、自分の作品へのコメント、自分のキャラクターが使われた作品をここでお知らせします。",
      zh: "暂无新消息。关注作品的新话、自己作品的评论及使用自己角色创作的作品都会在这里通知你。",
    },
  "읽음 표시": {
    en: "Mark as read",
    ja: "既読にする",
    zh: "标为已读",
  },
  "구독 중인 작품": {
    en: "Following",
    ja: "フォロー中の作品",
    zh: "正在关注",
  },
  "마음에 드는 작품에서 ‘작품 구독’을 눌러 보세요.": {
    en: "Choose “Follow story” on a story you enjoy.",
    ja: "気になる作品で「作品をフォロー」を押してみましょう。",
    zh: "在喜欢的作品页面点击“关注作品”吧。",
  },
  "현재 공개되지 않은 작품": {
    en: "This story is currently private",
    ja: "現在非公開の作品",
    zh: "该作品当前未公开",
  },
  "커뮤니티 신고": {
    en: "Community reports",
    ja: "コミュニティの通報",
    zh: "社区举报",
  },
  "댓글": {
    en: "Comment",
    ja: "コメント",
    zh: "评论",
  },
  캐릭터: {
    en: "Character",
    ja: "キャラクター",
    zh: "角色",
  },
  "삭제된 내용": {
    en: "Deleted content",
    ja: "削除された内容",
    zh: "已删除的内容",
  },
  "사유:": {
    en: "Reason:",
    ja: "理由：",
    zh: "原因：",
  },
  "숨기고 처리 완료": {
    en: "Hide and resolve",
    ja: "非表示にして対応完了",
    zh: "隐藏并处理完毕",
  },
  "조치 없이 처리 완료": {
    en: "Resolve without action",
    ja: "措置なしで対応完了",
    zh: "不采取措施并结案",
  },
  "신고 검토로 공개가 제한됐어요. 문의하기로 연락해 주세요.": {
    en: "Publishing is restricted after a report review. Please contact support.",
    ja: "通報の確認により公開が制限されています。お問い合わせからご連絡ください。",
    zh: "经举报审核，公开功能已受限。请联系支持人员。",
  },
  "다른 사람이 이 캐릭터로 새 이야기를 만들 수 있어요.": {
    en: "Others can use this character to create new stories.",
    ja: "他の人がこのキャラクターで新しいお話を作れます。",
    zh: "其他人可以使用此角色创作新故事。",
  },
  "캐릭터 공개 취소": {
    en: "Make character private",
    ja: "キャラクターを非公開にする",
    zh: "取消公开角色",
  },
  "공개하면 이름·설명·캐릭터 그림이 보이고, 누구나 LORE 안에서 웹툰 생성에 사용할 수 있어요. 공개를 취소해도 이미 생성한 작품은 남아요. 원본 사진은 공개하지 않으며, 그림 파일의 별도 이용 권한을 주는 것은 아니에요.":
    {
      en: "Publishing shows the name, description, and character art, and lets anyone use this character to generate webtoons within LORE. Existing stories remain if you make it private later. Original photos are not shared, and this does not grant separate rights to use the image file.",
      ja: "公開すると名前・説明・キャラクター画像が表示され、誰でもLORE内でウェブトゥーンの生成に使えます。非公開に戻しても、すでに生成された作品は残ります。元の写真は公開されず、画像ファイルの別途利用を許可するものではありません。",
      zh: "公开后，名称、描述和角色图像将可见，任何人都可在LORE内使用该角色生成漫画。取消公开后，已生成的作品仍会保留。原始照片不会公开，这也不代表授予图像文件的其他使用权。",
    },
  "이 캐릭터를 공유할 권리가 있으며, 위 이용 범위에 동의해요.": {
    en: "I have the right to share this character and agree to the use described above.",
    ja: "このキャラクターを共有する権利を持ち、上記の利用範囲に同意します。",
    zh: "我有权分享此角色，并同意上述使用范围。",
  },
  "동의하고 공개": {
    en: "Agree and publish",
    ja: "同意して公開",
    zh: "同意并公开",
  },
  "다른 사람도 쓸 수 있게 공개": {
    en: "Share for others to create with",
    ja: "他の人も使えるように公開",
    zh: "公开以供他人创作",
  },
  "공개 캐릭터로 새 이야기": {
    en: "New stories with public characters",
    ja: "公開キャラクターで新しいお話",
    zh: "用公开角色创作新故事",
  },
  "창작자가 함께 쓰도록 공개한 캐릭터예요. 마음에 드는 캐릭터로 나만의 이야기를 시작해 보세요.":
    {
      en: "Creators shared these characters for everyone to use. Choose one and start your own story.",
      ja: "作者がみんなで使えるように公開したキャラクターです。お気に入りのキャラクターで自分だけのお話を始めましょう。",
      zh: "这些角色由创作者公开供大家使用。选择喜欢的角色，开始自己的故事吧。",
    },
  "캐릭터를 불러오는 중…": {
    en: "Loading characters…",
    ja: "キャラクターを読み込み中…",
    zh: "正在加载角色…",
  },
  "다시 시도": {
    en: "Try again",
    ja: "再試行",
    zh: "重试",
  },
  "아직 공개된 캐릭터가 없어요. 내 캐릭터를 먼저 공개해 보세요.": {
    en: "No public characters yet. Share one of yours to get started.",
    ja: "公開キャラクターはまだありません。自分のキャラクターを公開してみましょう。",
    zh: "还没有公开角色，试着先公开自己的角色吧。",
  },
  "원작자 ·": {
    en: "Original creator ·",
    ja: "原作者・",
    zh: "原作者 ·",
  },
  "이 캐릭터로 새 이야기": {
    en: "Create a story with this character",
    ja: "このキャラクターでお話を作る",
    zh: "用此角色创作故事",
  },
  "캐릭터 더 보기": {
    en: "More characters",
    ja: "キャラクターをもっと見る",
    zh: "查看更多角色",
  },
  "신고를 접수했어요. 운영자가 확인하겠습니다.": {
    en: "Report received. Our team will review it.",
    ja: "通報を受け付けました。運営が確認します。",
    zh: "举报已受理，管理员将进行审核。",
  },
  닫기: {
    en: "Close",
    ja: "閉じる",
    zh: "关闭",
  },
  "신고 사유": {
    en: "Reason for reporting",
    ja: "通報理由",
    zh: "举报原因",
  },
  "문제가 되는 내용을 알려 주세요.": {
    en: "Describe the issue.",
    ja: "問題の内容を教えてください。",
    zh: "请描述具体问题。",
  },
  "신고 접수": {
    en: "Submit report",
    ja: "通報を送信",
    zh: "提交举报",
  },
  "처리 중이에요…": {
    en: "Processing your request…",
    ja: "処理しています…",
    zh: "正在处理请求…",
  },
  "처리 중…": {
    en: "Processing…",
    ja: "処理中…",
    zh: "处理中…",
  },
  "독자 {n}": {
    en: "Reader {n}",
    ja: "読者 {n}",
    zh: "读者 {n}",
  },
  "창작자 {n}": {
    en: "Creator {n}",
    ja: "作者 {n}",
    zh: "创作者 {n}",
  },
  "{n}화가 공개됐어요. 기다리던 이야기를 만나 보세요.": {
    en: "Episode {n} is out. Read the story you have been waiting for.",
    ja: "第{n}話が公開されました。待っていたお話を読んでみましょう。",
    zh: "第{n}话已公开，来看看期待的故事吧。",
  },
  "작품에 새 댓글이 달렸어요.": {
    en: "Your story has a new comment.",
    ja: "作品に新しいコメントが付きました。",
    zh: "你的作品收到了新评论。",
  },
  "내 캐릭터 ‘{name}’을 사용한 새 작품이 공개됐어요.": {
    en: "A new story using your character “{name}” is public.",
    ja: "あなたのキャラクター「{name}」を使った新しい作品が公開されました。",
    zh: "使用你的角色“{name}”创作的新作品已公开。",
  },
  "내 캐릭터 ‘{name}’로 만든 새 작품이 공개됐어요.": {
    en: "A new story using your character “{name}” is public.",
    ja: "あなたのキャラクター「{name}」を使った新しい作品が公開されました。",
    zh: "使用你的角色“{name}”创作的新作品已公开。",
  },
  "공개된 작품이나 내용을 찾을 수 없어요.": {
    en: "This public story or content is no longer available.",
    ja: "公開作品または内容が見つかりません。",
    zh: "找不到公开的作品或内容。",
  },
  "1자부터 {max}자까지 입력해 주세요.": {
    en: "Enter between 1 and {max} characters.",
    ja: "1文字から{max}文字で入力してください。",
    zh: "请输入1到{max}个字符。",
  },
  "로그인한 뒤 이용해 주세요.": {
    en: "Please sign in to continue.",
    ja: "ログインしてからご利用ください。",
    zh: "请登录后继续。",
  },
  "잠깐만요. 10초 뒤에 댓글을 남겨 주세요.": {
    en: "Please wait 10 seconds before posting another comment.",
    ja: "10秒待ってからコメントしてください。",
    zh: "请等待10秒后再次评论。",
  },
  "관리자만 이용할 수 있어요.": {
    en: "Only administrators can use this feature.",
    ja: "管理者のみ利用できます。",
    zh: "仅管理员可使用此功能。",
  },
  "완성된 캐릭터만 이용 범위에 동의한 뒤 공개할 수 있어요.": {
    en: "Only completed characters can be published after you agree to the usage terms.",
    ja: "完成したキャラクターのみ、利用範囲に同意して公開できます。",
    zh: "只有完成的角色才能在同意使用范围后公开。",
  },
  "요청을 완료하지 못했어요. 다시 시도해 주세요.": {
    en: "The request could not be completed. Please try again.",
    ja: "処理できませんでした。もう一度お試しください。",
    zh: "请求未能完成，请重试。",
  },
  /* ---- 빠졌던 번역 ---- */
  "구독": { en: "Subscribe", ja: "購読", zh: "订阅" },
  "구독 중": { en: "Subscribed", ja: "購読中", zh: "已订阅" },
  "댓글 불러오는 중…": { en: "Loading comments…", ja: "コメントを読み込み中…", zh: "正在加载评论…" },
  "댓글 입력": { en: "Write a comment", ja: "コメントを入力", zh: "输入评论" },
  "등록": { en: "Post", ja: "投稿", zh: "发布" },
  "아직 댓글이 없습니다.": { en: "No comments yet.", ja: "まだコメントはありません。", zh: "还没有评论。" },
  "모두 읽음": { en: "Mark all read", ja: "すべて既読", zh: "全部已读" },
  "소식": { en: "Updates", ja: "お知らせ", zh: "动态" },
  "처리할 신고가 없어요.": { en: "No reports to handle.", ja: "対応する通報はありません。", zh: "没有待处理的举报。" },
};
registerDict(dict);
