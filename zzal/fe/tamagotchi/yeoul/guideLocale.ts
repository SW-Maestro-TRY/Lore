import {windowCopy} from '../../lib/windowCopy';
import {guideCopy,guideTopics,type GuideTopic}from './guideTopics';
import type{ZzalLocale}from '../../lib/locale';
type GuideCopy={ [K in keyof typeof guideCopy]:string };
const EN:GuideCopy={open:'Help',title:'How to use',close:'Close',current:'Current status',intro:'Choose a feature to learn more.',sample:'The sample room lets you try things. It is separate from your character’s real records.',unavailable:'Couldn’t check the status. Please check the latest room information.',where:'Where to find it',related:'About this status',traveling:'Away on a trip. Care and conversation resume after returning.',sleeping:'Asleep now. Check the bedroom to see when waking is available.',awake:'Awake now.',food:'{count} meals left',refill:'Next refill in about {minutes} minutes',trash:'{count} piles to clean',bathed:'Already bathed today.',sick:'Feeling sick. Check the medicine button in the room.'};
const JA:GuideCopy={open:'使い方',title:'使い方',close:'閉じる',current:'今の状態',intro:'気になる機能を選んでください。',sample:'体験ルームでは機能を試せます。あなたのキャラクターの生活記録とは別です。',unavailable:'状態を確認できませんでした。部屋の最新の案内を確認してください。',where:'場所',related:'この状態について',traveling:'旅行中です。帰ってくるとお世話や会話を再開できます。',sleeping:'今は眠っています。起こせるかどうかは寝室で確認できます。',awake:'今は起きています。',food:'ごはん残り{count}個',refill:'次の補充まで約{minutes}分',trash:'片づけるほこり{count}個',bathed:'今日はもうお風呂に入りました。',sick:'具合が悪いようです。部屋の薬ボタンを確認してください。'};
const TOPICS_EN:Record<string,[string,string,string]>={
food:['Meals and refills','Kitchen','Meals increase fullness. The kitchen shows your remaining food and the next refill. If an action is unavailable, check the reason beside its button.'],
snack:['Snacks and stomachaches','Kitchen','Snacks are separate from meals. Too many in one day may cause a stomachache. Snacks are unavailable while sick. Other actions do not erase that day’s snack count.'],
pet:['Petting','Tap your character in the room','Your character reacts to petting. Reactions and the daily number that increases your bond can differ. Repeated taps do not keep earning rewards.'],
clean:['Cleaning and bathing','Bathroom','Cleaning removes one dust pile at a time. Bathing removes all piles and is available once a day. A bath is possible even with no piles, but cleaning is not. You cannot bathe again after today’s bath.'],
medicine:['Illness and medicine','Medicine button in the room','When your character is sick, check the medicine button in the room. Medicine is available while sick. Check the recovery state afterward.'],
gauges:['Hunger, mood and cleanliness','Room bubbles and care menus','Room information changes with your character’s condition. Open a menu to see available actions. Reading this guide does not change their condition.'],
sleep:['Bedtime and waking','Bedroom','Daily times use Korea time. You can put your character to bed from 7 p.m. until before 11 p.m.; they sleep automatically at 11 p.m. You can wake them from 7 a.m. until before 10 a.m.; they wake automatically at 10 a.m. The first guided nap is separate. Check the bedroom’s current information.'],
travel:['Trips and returning','Trip information in the room','Care and conversation are unavailable while traveling. Check the room for their return. Earlier conversation and relationship records remain.'],
game:["Starting and resuming games", "Yard", "Starting a game uses an attempt. You can resume a game in progress. Starting a new game ends the unfinished game."],
running:['Running','Running tab in the yard','Running is not ready yet. Guess the hand is available now. Opening a coming-soon tab does not use a game attempt.'],
display:["Button labels and indicators", "Settings under Character info", "Choose button colors, shapes, and text. Conversation indicators inside the room are separate from phone push notifications."],
chat:['Conversation and what they call you','Chat button','Conversations continue in the same record. Send messages while your character is awake and in the room. Change what they call you in character info. Their name and what they call you are different.'],
delivery:["Sending and checking replies", "Chat input", "Wait while your message is being sent. If the result is uncertain, your message stays on this screen. Use Check reply to look for the result of your earlier message."],
return:['Greetings when you return','Room','After leaving the service for at least an hour, your character may greet you again. Return within an hour to keep an unanswered greeting. Reloading or opening and closing chat does not repeatedly create new greetings.'],
history:['Reading past conversations','Conversation history inside chat','Load earlier messages to keep reading. Older greeting records have their own tab. Records stay in the language used when written. Reading does not generate a reply.'],
album:["Album and owned animations", "Album", "Choose from animations you own. Earlier animations remain in your album."],
save:['Save and share files','Choose an animation in the album','Save downloads a file to your device. Share opens the device share sheet. Canceling the sheet does not send it to anyone. Saving and sharing vary by browser.'],
identity:["Character info and settings", "Character info at the top of the room", "Edit their personality, world, and other settings, as well as what they call you. Use Save settings to keep your changes."],
background:["Backgrounds", "Album → Room", "Choose a background you own to preview it, then use Use this background to apply it. Your choice stays when you return. Backgrounds you have not received cannot be selected."],
attendance:["Attendance rewards", "Motions: Album → Album · Backgrounds: Album → Room", "Finishing your first guide counts as your first attendance. Meet again after a night’s sleep and waking up for the next one. Receive a background on your second attendance and custom motions on attendances 3, 4, 5, 7, 9, 11, 13 and 15. Attendance keeps counting if a gift takes longer; it arrives when ready."],
diary:["Your character’s journal", "Album → Journal", "While you are away from the service, four hours of your character’s awake time starts a journal entry. Sleep and travel do not count. An unread entry prevents new entries from piling up. Open an entry from the list to read it. Read entries remain available."],
feedback:["Reviews and feedback", "Leave feedback in the room", "Tell us what you thought. Use Send to submit it, or close without sending."],
recovery:['Connection trouble or slow loading','The feature’s error message','If a care action or save result is uncertain, check the latest state and recovery button first. Check before repeatedly pressing again. Image loading and chat delivery have separate recovery messages.']
};
const TOPICS_JA:Record<string,[string,string,string]>={
food:['ごはんと補充','キッチン','ごはんをあげると満腹度が上がります。残りの数と次の補充までの時間はキッチンで確認できます。使えない場合はボタンの横の理由を見てください。'],
snack:['おやつとお腹の調子','キッチン','おやつはごはんとは別です。同じ日に何度もあげるとお腹をこわすことがあります。具合が悪いときはあげられません。間に別の行動をしても、その日のおやつの回数は残ります。'],
pet:['なでる','部屋のキャラクターをタップ','なでるとキャラクターが反応します。反応する回数と、その日の親密度に反映される回数は同じとは限りません。何度も押しても報酬が増え続けるわけではありません。'],
clean:['片づけとお風呂','バスルーム','片づけはほこりを1個ずつ取り除きます。お風呂はすべて片づけられ、1日1回使えます。ほこりがなくてもお風呂には入れますが、片づけはできません。今日のお風呂が済んだら、もう一度は使えません。'],
medicine:['病気と薬','部屋の薬ボタン','具合が悪いときは部屋に出る薬ボタンを確認してください。薬は病気のときに使えます。使ったあとに回復した状態を確認できます。'],
gauges:['空腹・気分・清潔さ','部屋の吹き出しとお世話メニュー','生活の状態によって部屋の案内が変わります。メニューを開くと今できる行動がわかります。説明を読むだけでは状態は変わりません。'],
sleep:['寝かせる・起こす','寝室','生活時間は韓国時間です。19時から23時前まで寝かせられ、23時に自動で眠ります。朝7時から10時前まで起こせて、10時には自然に起きます。最初のガイド中のお昼寝は別です。寝室の現在の案内を確認してください。'],
travel:['旅行と帰宅','部屋の旅行案内','旅行中はお世話や会話ができません。帰宅は部屋で確認できます。これまでの会話や関係の記録は消えません。'],
game:["ゲームの開始と続き", "庭", "ゲームを始めると回数を1回使います。途中のゲームは続きから遊べます。新しいゲームを始めると、途中のゲームは終了します。"],
running:['かけっこ','庭のかけっこタブ','かけっこは準備中です。今は左右あてで遊べます。準備中のタブを開くだけでは、ゲームの回数は減りません。'],
display:["ボタン表示とお知らせ", "キャラクター情報の下にある設定", "ボタンの色・形・文字の表示を選べます。部屋の会話のお知らせは、スマートフォンのプッシュ通知とは別です。"],
chat:['会話と呼び方','会話ボタン','同じ記録のまま会話が続きます。起きていて部屋にいるときに話せます。呼び方はキャラクター情報で変更できます。キャラクターの名前とあなたの呼び方は別です。'],
delivery:["送信中・返事を確認できないとき", "会話の入力欄", "送信中は少しお待ちください。結果を確認できないときも、この画面の入力は残っています。返事を確認するボタンで、先に送ったメッセージの結果を確認できます。"],
return:['戻ったときの呼びかけ','部屋','サービスを離れて1時間以上たって戻ると、新しく呼びかけることがあります。1時間以内なら未回答の呼びかけが続きます。再読み込みや会話の開閉だけでは新しい呼びかけは増えません。'],
history:['過去の会話を見る','会話内の履歴','前のメッセージを読み込んで続けて読めます。昔の呼びかけには別のタブがあります。記録は書かれた言語のまま残ります。読むだけでは新しい返事は作られません。'],
album:["アルバムと持っている動き", "アルバム", "持っている動きを選んで見られます。以前もらった動きもアルバムに残っています。"],
save:['ファイルの保存と共有','アルバムで動きを選ぶ','保存は端末にファイルをダウンロードし、共有は端末の共有画面を開きます。キャンセルした場合は誰にも送られません。方法はブラウザによって異なります。'],
identity:["キャラクター情報と設定", "部屋の上部にあるキャラクター情報", "性格や世界などの設定、あなたの呼び方を変更できます。変更した内容は保存ボタンで保存してください。"],
background:["背景", "アルバム → 部屋", "持っている背景を選んでプレビューし、この背景を使うボタンで部屋に適用できます。次に戻ってきても選んだ背景はそのままです。まだ受け取っていない背景は選べません。"],
attendance:["出席特典", "動き：アルバム → アルバム・背景：アルバム → 部屋", "最初のガイドを終えると1回目の出席です。その後は夜に眠って目覚めてから、また会うと次の出席になります。2回目は背景、3・4・5・7・9・11・13・15回目にはカスタムの動きが届きます。贈り物の準備が遅れても出席は続き、準備できたら届きます。"],
diary:["この子の日記", "アルバム → 日記", "サービスを離れている間、この子が起きている時間が4時間たまると日記を準備します。睡眠や旅行の時間は数えません。未読の日記がある間は新しい日記は増えません。一覧から日記を開いて読めます。読んだ日記もまた見られます。"],
feedback:["感想を送る", "部屋の感想ボタン", "使ってみた感想やご意見を送れます。送信しない場合は、そのまま閉じても大丈夫です。"],
recovery:['接続が切れた・表示が遅い','各機能のエラー案内','お世話や保存の結果が不明なときは、最新の状態と確認ボタンを先に見てください。繰り返し押す前に結果を確かめましょう。画像の読み込みと会話の送信には、それぞれの案内があります。']
};
export function localizedGuide(locale:ZzalLocale){const map=locale==='en'?TOPICS_EN:TOPICS_JA;return{copy:locale==='ko'?guideCopy:locale==='en'?EN:JA,topics:guideTopics.map(t=>{const v=map[t.id];const base=locale==='ko'||!v?t:{id:t.id,title:v[0],where:v[1],body:v[2]}as GuideTopic;const zoned=t.id==='sleep'?windowCopy('guideSleep',locale):null;return zoned===null?base:{...base,body:zoned}as GuideTopic})}}
