import type { FirstIntroKey } from './firstIntro';
import type { ZzalLocale } from '../../lib/locale';
const EN:Record<FirstIntroKey,string>={
  ATTENDANCE_INTRO: "Check the attendance gifts: backgrounds and motions.",
  "FEED": "Open the kitchen and offer a meal.",
  "SNACK": "Offer one snack. Too many can cause a stomachache.",
  "CLEAN": "Clean up the dust in the bathroom.",
  "BATH": "A bath can clear all three dust piles at once. You can bathe once a day. Shall we try it now?",
  "GAME": "Play one full game of left-or-right guessing in the yard.",
  "PET": "Tap your character to pet them.",
  "NAP_SLEEP": "Try putting your character to bed. You can wake them right away this time. Normally, bedtime is available from 7 p.m. Korea time, and they sleep automatically at 11 p.m.",
  "NAP_WAKE": "Wake your character in the bedroom now. Normally, you can wake them from 7 a.m. Korea time, and they wake automatically at 10 a.m.",
  "ALBUM": "Open Album, then open one motion to see it up close. Just finding Save and Share is enough.",
  "CHAT": "Open chat and send a message.",
  "PERSONALITY": "Open Character info to check their personality, settings, and what they call you.",
  "DIARY_INTRO": "Open Journal to see the first record. Your story together gathers here.",
  "FINISH_NOTICE": "You’ve tried every step. Read the final message to finish your first guide.",
  "REVIEW_SEEN": "Tell us how it felt. You can close this without sending a review.",
  "COMPLETE": ""
};
const JA:Record<FirstIntroKey,string>={
  ATTENDANCE_INTRO: "出席でもらえる背景や動きのプレゼントを確認してください。",
  "FEED": "キッチンでごはんをあげてみてください。",
  "SNACK": "おやつをひとつあげてみてください。あげすぎるとお腹をこわすことがあります。",
  "CLEAN": "バスルームで床のほこりを片づけてください。",
  "BATH": "お風呂ならほこり3個もまとめて片づけられます。1日1回なので、今やってみましょうか？",
  "GAME": "庭で左右あてを1ゲーム、最後まで遊んでみてください。",
  "PET": "キャラクターをタップして、なでてみてください。",
  "NAP_SLEEP": "寝室で寝かせてみてください。今回はすぐに起こせます。普段は韓国時間の19時から寝かせられ、23時に自動で眠ります。",
  "NAP_WAKE": "寝室で今すぐ起こしてください。普段は韓国時間の朝7時から起こせて、10時に自動で起きます。",
  "ALBUM": "アルバムで動きをひとつ押して、大きく見てみましょう。保存や共有は、ボタンを見るだけでも大丈夫です。",
  "CHAT": "会話ボタンを押して、話しかけてみてください。",
  "PERSONALITY": "キャラクター情報で性格や設定、あなたの呼び方を確認してください。",
  "DIARY_INTRO": "日記を開いて最初の記録を見てみましょう。一緒につづる物語がここに集まります。",
  "FINISH_NOTICE": "最初の体験がすべて終わりました。最後の案内を確認してください。",
  "REVIEW_SEEN": "使ってみた感想をお聞かせください。送信せずに閉じても大丈夫です。",
  "COMPLETE": ""
};
export function introText(key:FirstIntroKey,locale:ZzalLocale,fallback:string){return locale==='en'?EN[key]:locale==='ja'?JA[key]:fallback}
