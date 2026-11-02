/* 서버가 보내는 글의 사전 — 한국어 원문이 키.
 *
 * 서버(webtoon/be)는 오류·실패 사유·단계 이름·피드백 태그·기본 캐릭터를 한국어로
 * 보낸다. 화면은 그 글을 t()·translateNow() 로 거쳐 보여 주고, 뜻은 여기 적는다.
 * 서버 문구를 고치면 여기 키도 같이 고쳐야 한다(안 고치면 원문이 그대로 나온다).
 *
 * 숫자가 끼는 문구는 그 자리를 `{n}` 처럼 적어 둔다 — lib/i18n.tsx 가 자리를 아무
 * 글자로 보고 맞춰 본 뒤 번역문의 같은 자리에 넣는다. */
import { registerComposer, registerDict, type Dict } from "./i18n";

const dict: Dict = {
  /* ---- 부르기 공통 (lib/api.ts · lib/photoFile.ts) ---- */
  "요청이 실패했습니다 ({n})": { en: "The request failed ({n})", ja: "リクエストに失敗しました（{n}）", zh: "请求失败（{n}）" },
  "사진을 올리지 못했습니다 ({n})": { en: "Couldn't upload the photo ({n})", ja: "写真をアップロードできませんでした（{n}）", zh: "照片上传失败（{n}）" },
  "이 브라우저가 못 여는 사진입니다. JPG 나 PNG 로 바꿔서 올려 주세요": { en: "This browser can't open that photo. Please convert it to JPG or PNG and upload again.", ja: "このブラウザでは開けない写真です。JPGかPNGに変換してアップロードしてください", zh: "此浏览器无法打开这张照片，请转换为 JPG 或 PNG 后再上传" },
  "오늘 무료 {n}편": { en: "{n} free today", ja: "今日の無料 {n}本", zh: "今日免费 {n} 部" },
  "오늘 무료 소진 · 로그인하면 이어서": { en: "Today's free runs used · Log in to keep going", ja: "今日の無料分は終了 · ログインすると続けられます", zh: "今日免费次数已用完 · 登录后可继续" },
  "한 편 {n}크레딧 · 보유 {m}C": { en: "{n} credits per episode · You have {m}C", ja: "1本 {n}クレジット · 所持 {m}C", zh: "每部 {n} 积分 · 持有 {m}C" },
  "© 2026 LORE · TRY팀": { en: "© 2026 LORE · Team TRY", ja: "© 2026 LORE · TRYチーム", zh: "© 2026 LORE · TRY 团队" },
  "TRY팀": { en: "Team TRY", ja: "TRYチーム", zh: "TRY 团队" },
  "그리기 방식": { en: "Options", ja: "進め方", zh: "方式" },

  /* ---- 어느 화면에서나 쓰는 조각 (ui/TopNav) — 영역 사전이 안 읽힌 화면에서도 뜨게 여기 둔다 ---- */
  "이전": { en: "Back", ja: "戻る", zh: "上一步" },
  "지금 위치": { en: "Current step", ja: "現在の位置", zh: "当前位置" },

  /* ---- 만들기 · 크레딧 (credit/ · job/JobService) ---- */
  "크레딧이 모자랍니다 (필요 {n} · 보유 {m})": { en: "Not enough credits (need {n} · have {m})", ja: "クレジットが足りません（必要 {n} · 所持 {m}）", zh: "积分不足（需要 {n} · 持有 {m}）" },
  "크레딧 잔액이 부족해요 (필요 {n} · 보유 {m})": { en: "Not enough credits (need {n} · have {m})", ja: "クレジット残高が足りません（必要 {n} · 所持 {m}）", zh: "积分余额不足（需要 {n} · 持有 {m}）" },
  "만들기를 시작하지 못했습니다": { en: "Couldn't start creating", ja: "作成を開始できませんでした", zh: "无法开始制作" },
  "오늘 무료로 만들 수 있는 {n}편을 다 쓰셨어요 — 로그인하시면 이어서 만들 수 있어요.": { en: "You've used today's {n} free episodes — log in to keep creating.", ja: "今日無料で作れる{n}本を使い切りました — ログインすると続けて作れます。", zh: "今天的 {n} 部免费次数已用完——登录后可以继续制作。" },
  "오늘 만들 수 있는 몫이 다 찼어요 — 내일 다시 와 주세요.": { en: "Today's creation limit is full — please come back tomorrow.", ja: "今日作れる分がいっぱいになりました — また明日お越しください。", zh: "今天的制作名额已满——请明天再来。" },
  "저작권 확인에 동의해야 만들 수 있습니다": { en: "Please confirm the copyright notice to continue.", ja: "著作権の確認に同意すると作成できます", zh: "请先同意版权确认才能制作" },
  "캐릭터를 알 수 있는 것이 하나는 필요합니다 — 이름 · 설명 · 항목 · 사진 중 아무거나요.": { en: "We need at least one thing about the character — a name, description, detail or photo.", ja: "キャラクターがわかるものが一つは必要です — 名前・説明・項目・写真のどれでも。", zh: "至少需要一项角色信息——名字、描述、条目或照片都可以。" },
  "이메일 주소를 다시 확인해 주세요": { en: "Please check the email address.", ja: "メールアドレスをもう一度確認してください", zh: "请再检查一下邮箱地址" },
  "지금 고를 차례가 아닙니다": { en: "It's not time to pick yet.", ja: "今は選ぶ段階ではありません", zh: "现在还不是选择的时候" },
  "그런 이야기가 없습니다": { en: "That story doesn't exist.", ja: "そのストーリーはありません", zh: "没有这个故事" },
  "지금 확인할 차례가 아닙니다": { en: "It's not time to review yet.", ja: "今は確認する段階ではありません", zh: "现在还不是确认的时候" },
  "사진은 {n}장까지 올릴 수 있습니다": { en: "You can upload up to {n} photos.", ja: "写真は{n}枚までアップロードできます", zh: "最多可上传 {n} 张照片" },
  "{n}번째 사진을 열지 못했습니다. 아이폰 사진(HEIC)이면 JPG 나 PNG 로 바꿔서 올려 주세요.": { en: "Couldn't open photo #{n}. If it's an iPhone photo (HEIC), convert it to JPG or PNG and upload again.", ja: "{n}枚目の写真を開けませんでした。iPhoneの写真（HEIC）ならJPGかPNGに変換してアップロードしてください。", zh: "无法打开第 {n} 张照片。如果是 iPhone 照片（HEIC），请转换为 JPG 或 PNG 后上传。" },
  "{n}번째 사진을 읽지 못했습니다": { en: "Couldn't read photo #{n}.", ja: "{n}枚目の写真を読み込めませんでした", zh: "无法读取第 {n} 张照片" },
  "{n}번째 사진이 너무 큽니다 (6MB 까지)": { en: "Photo #{n} is too large (up to 6MB).", ja: "{n}枚目の写真が大きすぎます（6MBまで）", zh: "第 {n} 张照片太大（最大 6MB）" },
  "사진을 읽지 못했습니다": { en: "Couldn't read the photo.", ja: "写真を読み込めませんでした", zh: "无法读取照片" },
  "사진이 너무 큽니다 (6MB 까지)": { en: "The photo is too large (up to 6MB).", ja: "写真が大きすぎます（6MBまで）", zh: "照片太大（最大 6MB）" },
  "게스트 열쇠를 만들 수 없습니다": { en: "Couldn't prepare a guest upload.", ja: "ゲスト用のアップロードを準備できませんでした", zh: "无法准备访客上传" },
  "그런 작업이 없습니다": { en: "That job doesn't exist.", ja: "その作業はありません", zh: "没有这个任务" },
  "이 내용으로는 만들 수 없어요. 선정적이거나 잔혹한 묘사, 혐오 표현이 들어가면 걸러져요. 표현을 바꿔서 다시 시도해 주세요.": { en: "We can't create with this content. Sexual or graphic violence and hateful expressions are filtered out. Please rephrase and try again.", ja: "この内容では作成できません。扇情的・残酷な描写やヘイト表現は除外されます。表現を変えてもう一度お試しください。", zh: "无法使用此内容制作。含有色情、残酷描写或仇恨言论的内容会被过滤。请修改表达后重试。" },
  "지금은 내용을 확인할 수 없어요. 잠시 뒤 다시 시도해 주세요.": { en: "We can't check the content right now. Please try again shortly.", ja: "今は内容を確認できません。しばらくしてからもう一度お試しください。", zh: "暂时无法检查内容，请稍后再试。" },

  /* ---- 만드는 중 단계 이름 · 멈춘 사유 (job/JobService · job/JobRunner) ---- */
  "장면 나누기 · 페이지 그림": { en: "Scenes · Drawing pages", ja: "シーン分け · ページ作画", zh: "分场景 · 绘制页面" },
  "검수 · 합본": { en: "Review · Binding", ja: "検査 · 合本", zh: "检查 · 合订" },
  "그리는 도중에 문제가 생겼습니다.": { en: "Something went wrong while drawing.", ja: "描いている途中で問題が起きました。", zh: "绘制过程中出现了问题。" },
  "만들기를 취소했습니다": { en: "Creation was canceled.", ja: "作成をキャンセルしました", zh: "已取消制作" },
  "서버가 다시 시작되어 만들기가 끊겼습니다": { en: "The server restarted and creation was interrupted.", ja: "サーバーが再起動したため作成が中断されました", zh: "服务器重启，制作被中断" },
  "캐릭터 시트를 만들지 못했습니다": { en: "Couldn't make the character sheet.", ja: "キャラクターシートを作れませんでした", zh: "无法制作角色设定图" },
  "캐릭터 시트를 다시 만들지 못했습니다": { en: "Couldn't remake the character sheet.", ja: "キャラクターシートを作り直せませんでした", zh: "无法重做角色设定图" },
  /* 실패 이유 — JobFailure.humanMessage (#531) */
  "올린 사진을 찾지 못했어요. 사진을 다시 올려 새로 만들어 주세요.": { en: "We couldn't find your uploaded photo. Please upload it again and start a new one.", ja: "アップロードした写真が見つかりませんでした。写真をもう一度アップロードして新しく作ってください。", zh: "找不到上传的照片。请重新上传照片并重新制作。" },
  "캐릭터 그림이 이미지 안전 기준(선정성)에 걸렸어요. 한 번 더 그려 봤지만 같았어요. 노출이 많은 옷차림이 원인일 수 있으니, 노출이 적은 옷을 입은 사진이나 설명으로 다시 만들어 주세요.": { en: "The character image was blocked by the image safety filter (sexual content). We tried once more with the same result. Revealing clothing may be the cause, so please try again with a photo or description showing less revealing clothes.", ja: "キャラクター画像が画像の安全基準（性的表現）に引っかかりました。もう一度描いてみましたが同じでした。露出の多い服装が原因かもしれないので、露出の少ない服の写真や説明でもう一度作ってください。", zh: "角色图片未通过图像安全审核（色情内容）。我们又画了一次，结果相同。可能是暴露的服装导致的，请换一张穿着较保守的照片或描述重新制作。" },
  "캐릭터 그림이 이미지 안전 기준에 걸렸어요. 한 번 더 그려 봤지만 같았어요. 다른 사진이나 설명으로 다시 만들어 주세요.": { en: "The character image was blocked by the image safety filter. We tried once more with the same result. Please try again with a different photo or description.", ja: "キャラクター画像が画像の安全基準に引っかかりました。もう一度描いてみましたが同じでした。別の写真や説明でもう一度作ってください。", zh: "角色图片未通过图像安全审核。我们又画了一次，结果相同。请换一张照片或描述重新制作。" },
  "페이지 그림이 이미지 안전 기준(선정성)에 걸렸어요. 한 번 더 그려 봤지만 같았어요. 캐릭터 옷차림이나 이야기 속 노출 장면이 원인일 수 있으니, 다른 이야기나 옷차림으로 다시 만들어 주세요.": { en: "A page image was blocked by the image safety filter (sexual content). We tried once more with the same result. The character's outfit or a revealing scene in the story may be the cause, so please try again with a different story or outfit.", ja: "ページ画像が画像の安全基準（性的表現）に引っかかりました。もう一度描いてみましたが同じでした。キャラクターの服装や物語の露出シーンが原因かもしれないので、別の物語や服装でもう一度作ってください。", zh: "页面图片未通过图像安全审核（色情内容）。我们又画了一次，结果相同。可能是角色服装或故事中的暴露场景导致的，请换一个故事或服装重新制作。" },
  "페이지 그림이 이미지 안전 기준에 걸렸어요. 한 번 더 그려 봤지만 같았어요. 다른 이야기로 다시 만들어 주세요.": { en: "A page image was blocked by the image safety filter. We tried once more with the same result. Please try again with a different story.", ja: "ページ画像が画像の安全基準に引っかかりました。もう一度描いてみましたが同じでした。別の物語でもう一度作ってください。", zh: "页面图片未通过图像安全审核。我们又画了一次，结果相同。请换一个故事重新制作。" },
  "이야기 후보를 만들지 못했습니다": { en: "Couldn't write story options.", ja: "ストーリー候補を作れませんでした", zh: "无法生成故事候选" },
  "이야기 후보를 하나도 못 읽었습니다": { en: "Couldn't read any story options.", ja: "ストーリー候補を一つも読み込めませんでした", zh: "一个故事候选都没能读取" },
  "이야기 후보를 다시 만들지 못했습니다": { en: "Couldn't rewrite story options.", ja: "ストーリー候補を作り直せませんでした", zh: "无法重新生成故事候选" },
  "고른 이야기를 저장하지 못했습니다": { en: "Couldn't save the chosen story.", ja: "選んだストーリーを保存できませんでした", zh: "无法保存所选故事" },
  "장면을 나누지 못했습니다": { en: "Couldn't split the scenes.", ja: "シーンを分けられませんでした", zh: "无法划分场景" },
  "그림을 만들지 못했습니다": { en: "Couldn't make the pictures.", ja: "絵を作れませんでした", zh: "无法生成图片" },
  "{n}번째 장을 그리지 못했습니다": { en: "Couldn't draw page {n}.", ja: "{n}枚目のページを描けませんでした", zh: "无法绘制第 {n} 页" },
  "이어 붙이기가 실패했습니다": { en: "Couldn't stitch the pages together.", ja: "ページのつなぎ合わせに失敗しました", zh: "页面拼接失败" },
  "이어 붙이기가 너무 오래 걸립니다": { en: "Stitching the pages is taking too long.", ja: "ページのつなぎ合わせに時間がかかりすぎています", zh: "页面拼接耗时过长" },
  "만들기가 너무 오래 걸립니다 ({n}초)": { en: "Creation is taking too long ({n}s).", ja: "作成に時間がかかりすぎています（{n}秒）", zh: "制作耗时过长（{n} 秒）" },
  "올릴 그림을 만드는 데 너무 오래 걸립니다": { en: "Preparing the pictures is taking too long.", ja: "アップロードする絵の準備に時間がかかりすぎています", zh: "准备上传图片耗时过长" },

  /* ---- 캐릭터 (character/) ---- */
  "캐릭터 만들기가 너무 오래 걸립니다": { en: "Creating the character is taking too long.", ja: "キャラクター作成に時間がかかりすぎています", zh: "角色制作耗时过长" },
  "캐릭터를 그리지 못했습니다": { en: "Couldn't draw the character.", ja: "キャラクターを描けませんでした", zh: "没能画出角色" },
  "캐릭터를 만들지 못했습니다": { en: "Couldn't create the character.", ja: "キャラクターを作れませんでした", zh: "无法创建角色" },
  "그런 캐릭터가 없습니다": { en: "That character doesn't exist.", ja: "そのキャラクターはいません", zh: "没有这个角色" },
  "그런 카드가 없습니다": { en: "That card doesn't exist.", ja: "そのカードはありません", zh: "没有这张卡片" },
  "브라우저를 알 수 없어 만들 수 없습니다 — 새로고침 후 다시 시도해 주세요.": { en: "We couldn't identify this browser — please refresh and try again.", ja: "ブラウザを識別できないため作成できません — 再読み込みしてもう一度お試しください。", zh: "无法识别此浏览器——请刷新后重试。" },
  "어떤 캐릭터인지 한 줄만 적어 주세요 — 사진은 없어도 됩니다.": { en: "Tell us about the character in a line — no photo needed.", ja: "どんなキャラクターか一行だけ書いてください — 写真はなくても大丈夫です。", zh: "用一句话介绍一下角色——不需要照片。" },
  "지금은 캐릭터를 만들 수 없습니다": { en: "Characters can't be created right now.", ja: "今はキャラクターを作成できません", zh: "现在无法创建角色" },
  "오늘 무료로 만들 수 있는 캐릭터를 다 쓰셨어요 — 로그인하시면 이어서 만들 수 있어요.": { en: "You've used today's free characters — log in to keep creating.", ja: "今日無料で作れるキャラクターを使い切りました — ログインすると続けて作れます。", zh: "今天的免费角色次数已用完——登录后可以继续制作。" },
  "이름 없는 캐릭터": { en: "Unnamed character", ja: "名前のないキャラクター", zh: "无名角色" },
  "기본 캐릭터는 고칠 수 없습니다": { en: "Built-in characters can't be edited.", ja: "基本キャラクターは編集できません", zh: "内置角色无法修改" },
  "기본 캐릭터는 지울 수 없습니다": { en: "Built-in characters can't be deleted.", ja: "基本キャラクターは削除できません", zh: "内置角色无法删除" },

  /* ---- 작품 · 편집실 (runs/ · regen/) ---- */
  "그런 작품이 없습니다": { en: "That webtoon doesn't exist.", ja: "その作品はありません", zh: "没有这部作品" },
  "예시 작품은 지울 수 없습니다": { en: "Example webtoons can't be deleted.", ja: "サンプル作品は削除できません", zh: "示例作品无法删除" },
  "내가 만든 작품만 지울 수 있습니다": { en: "You can only delete webtoons you made.", ja: "自分で作った作品だけ削除できます", zh: "只能删除自己创作的作品" },
  "내가 만든 작품만 되살릴 수 있습니다": { en: "You can only restore webtoons you made.", ja: "自分で作った作品だけ復元できます", zh: "只能恢复自己创作的作品" },
  "내가 만든 작품만 고칠 수 있습니다": { en: "You can only edit webtoons you made.", ja: "自分で作った作品だけ編集できます", zh: "只能修改自己创作的作品" },
  "내가 만든 작품만 바꿀 수 있습니다": { en: "You can only change webtoons you made.", ja: "自分で作った作品だけ変更できます", zh: "只能更改自己创作的作品" },
  "내가 만든 작품만 다시 올릴 수 있습니다": { en: "You can only re-upload webtoons you made.", ja: "自分で作った作品だけ再アップロードできます", zh: "只能重新上传自己创作的作品" },
  "만드는 중인 작품은 먼저 중단한 뒤 지울 수 있습니다": { en: "Stop a webtoon that's still being made before deleting it.", ja: "作成中の作品は先に中断してから削除できます", zh: "正在制作的作品需先停止才能删除" },
  "그림을 다시 올리지 못했습니다": { en: "Couldn't re-upload the pictures.", ja: "絵を再アップロードできませんでした", zh: "无法重新上传图片" },
  "편집실은 로그인해야 쓸 수 있어요. 로그인하면 이 브라우저로 만든 작품도 같이 따라옵니다.": { en: "Log in to use the editor. Webtoons made in this browser come along when you log in.", ja: "編集室はログインすると使えます。ログインすると、このブラウザで作った作品も一緒に引き継がれます。", zh: "登录后才能使用编辑室。登录后，此浏览器中制作的作品也会一起带过去。" },
  "그 회차에 그려진 장이 없습니다": { en: "That episode has no drawn pages.", ja: "その話には描かれたページがありません", zh: "该话没有已绘制的页面" },
  "그 판본이 없습니다": { en: "That version doesn't exist.", ja: "その版はありません", zh: "没有这个版本" },
  "그 페이지가 없습니다": { en: "That page doesn't exist.", ja: "そのページはありません", zh: "没有这一页" },
  "다시 그리지 못했습니다 — 원래 그림은 그대로입니다": { en: "Couldn't redraw — the original picture is unchanged.", ja: "描き直せませんでした — 元の絵はそのままです", zh: "重绘失败——原图保持不变" },

  /* ---- 설문 (feedback/) ---- */
  "관리자만 볼 수 있어요": { en: "Only admins can see this.", ja: "管理者のみ閲覧できます", zh: "仅管理员可见" },
  "로그인해야 보낼 수 있어요": { en: "Log in to send this.", ja: "ログインすると送信できます", zh: "登录后才能发送" },
  "내가 만든 작품에만 답할 수 있어요": { en: "You can only answer for webtoons you made.", ja: "自分で作った作品にだけ回答できます", zh: "只能为自己创作的作品作答" },
  "답이 없어요": { en: "No answers yet.", ja: "回答がありません", zh: "还没有回答" },
  "웹툰을 한 편 완성한 뒤에 답할 수 있어요": { en: "You can answer after finishing a webtoon.", ja: "ウェブトゥーンを1本完成させると回答できます", zh: "完成一部条漫后才能作答" },
  "모든 문항에 답해 주세요": { en: "Please answer every question.", ja: "すべての質問に答えてください", zh: "请回答所有问题" },

  /* ---- 편집실 다시 그리기 태그 (job/FeedbackTags 의 scene) ---- */
  "캐릭터가 이상해요": { en: "Character looks off", ja: "キャラクターがおかしい", zh: "角色不对劲" },
  "배경이 이상해요": { en: "Background looks off", ja: "背景がおかしい", zh: "背景不对劲" },
  "포즈가 어색해요": { en: "Awkward pose", ja: "ポーズが不自然", zh: "姿势别扭" },
  "표정이 안 맞아요": { en: "Wrong expression", ja: "表情が合っていない", zh: "表情不对" },
  "글자가 깨져요": { en: "Text is garbled", ja: "文字が崩れている", zh: "文字乱码" },
  "색·조명이 별로예요": { en: "Color or lighting is off", ja: "色・照明がいまいち", zh: "色彩或光线不好" },
  "이상한 게 그려졌어요": { en: "Something weird got drawn", ja: "変なものが描かれている", zh: "画出了奇怪的东西" },
  "기타": { en: "Other", ja: "その他", zh: "其他" },

  /* ---- 바로 써 볼 수 있는 캐릭터 (character/BuiltinCharacters) ---- */
  "클로에": { en: "Chloe", ja: "クロエ", zh: "克洛伊" },
  "겨울 무도회에서도 레이스 부채를 놓지 않는다. 숨이 가빠지면 부채부터 펼친다.": { en: "Never lets go of her lace fan, even at the winter ball. When her breath grows short, the fan comes open first.", ja: "冬の舞踏会でもレースの扇を手放さない。息が苦しくなると、まず扇を広げる。", zh: "即使在冬季舞会上也不放下蕾丝扇。一旦呼吸急促，先把扇子展开。" },
  "하린": { en: "Harin", ja: "ハリン", zh: "夏琳" },
  "국밥집 창가 자리가 자기 자리인 줄 안다. 누구에게나 잘 웃는데 정작 자기 얘기는 안 한다.": { en: "Acts like the window seat at the gukbap place is hers. Smiles at everyone, but never talks about herself.", ja: "クッパ屋の窓際の席を自分の席だと思っている。誰にでもよく笑うのに、自分の話はしない。", zh: "把汤饭店靠窗的位子当成自己的专座。对谁都笑，却从不谈自己。" },
  "리스엘": { en: "Riselle", ja: "リセル", zh: "莉丝艾尔" },
  "성을 내려다보는 발코니가 그의 자리다. 아래 도시 이름을 전부 외우는데, 그중 하나는 곧 사라진다.": { en: "His place is the balcony overlooking the castle. He knows every town below by name — and one of them is about to vanish.", ja: "城を見下ろすバルコニーが彼の居場所。眼下の街の名前をすべて覚えているが、そのひとつはもうすぐ消える。", zh: "俯瞰城堡的阳台是他的位置。他记得下方每座城市的名字，而其中一座即将消失。" },
  "서윤": { en: "Seoyun", ja: "ソユン", zh: "瑞允" },
  "밤에 문 닫는 헌책방을 혼자 지킨다. 묻는 말에는 답하지만 먼저 묻는 법이 없다.": { en: "Keeps a secondhand bookshop alone until it closes at night. Answers when asked, but never asks first.", ja: "夜に閉まる古本屋をひとりで守っている。聞かれれば答えるが、自分から聞くことはない。", zh: "独自守着一家夜里打烊的旧书店。有问必答，却从不先开口问。" },
  "루다": { en: "Ruda", ja: "ルダ", zh: "露达" },
  "싸우고 온 걸 숨기려고 더 크게 웃는다. 팔에 난 자국은 넘어져서 그런 거라고 한다.": { en: "Laughs louder to hide that she's been in a fight. Says the marks on her arm are from a fall.", ja: "けんかしてきたのを隠そうと、もっと大きく笑う。腕の傷は転んだせいだと言う。", zh: "为了掩饰打过架而笑得更大声。说手臂上的伤痕是摔的。" },
  "서진": { en: "Seojin", ja: "ソジン", zh: "瑞镇" },
  "밤에만 움직이는 해결사. 받은 일은 끝내고, 끝낸 일은 말하지 않는다.": { en: "A fixer who only moves at night. Finishes every job, and never talks about the finished ones.", ja: "夜にだけ動く解決屋。引き受けた仕事は終わらせ、終えた仕事は口にしない。", zh: "只在夜里行动的解决者。接下的活一定办完，办完的事从不提起。" },
  "도경": { en: "Dogyeong", ja: "ドギョン", zh: "道京" },
  "사무실에서 제일 조용한 사람. 웃는 걸 본 사람이 아직 없다.": { en: "The quietest person in the office. No one has seen him smile yet.", ja: "オフィスでいちばん静かな人。笑ったところを見た人はまだいない。", zh: "办公室里最安静的人。还没人见过他笑。" },
  "이현": { en: "Ihyeon", ja: "イヒョン", zh: "李贤" },
  "약속 시간에 늘 십 분 늦고, 늦은 이유는 매번 다르다.": { en: "Always ten minutes late, with a different excuse every time.", ja: "約束の時間にいつも10分遅れ、遅れた理由は毎回違う。", zh: "约会总是迟到十分钟，每次理由都不一样。" },
  "로젤": { en: "Rozelle", ja: "ロゼル", zh: "萝泽尔" },
  "다과회에서 제일 먼저 웃고 제일 늦게 돌아간다. 그날 오간 말을 하나도 안 잊는다.": { en: "First to laugh at the tea party and last to leave. Never forgets a single word said that day.", ja: "お茶会で誰より先に笑い、誰より遅く帰る。その日交わされた言葉をひとつも忘れない。", zh: "茶会上第一个笑、最后一个离开。那天说过的每句话都不会忘。" },
  "세아": { en: "Sea", ja: "セア", zh: "世雅" },
  "연습실 불을 마지막으로 끄는 사람. 거울 앞에서만 표정을 바꾼다.": { en: "The last one to switch off the practice-room lights. Only changes expression in front of the mirror.", ja: "練習室の明かりを最後に消す人。鏡の前でだけ表情を変える。", zh: "最后一个关掉练习室灯的人。只在镜子前才换表情。" },
  "다온": { en: "Daon", ja: "ダオン", zh: "多温" },
  "과제는 늘 산더미고 잠은 늘 부족하다. 그래도 창밖은 꼭 본다.": { en: "Assignments always piled high, sleep always short. Still never forgets to look out the window.", ja: "課題はいつも山積みで、睡眠はいつも足りない。それでも窓の外は必ず見る。", zh: "作业总是堆成山，觉总是不够睡。但一定会看看窗外。" },
  "윤겸": { en: "Yungyeom", ja: "ユンギョム", zh: "允谦" },
  "왕궁 문서를 나르는 일을 한다. 오늘 전할 두루마리 내용을 이미 읽어 버렸다.": { en: "Carries documents around the royal palace. Has already read today's scroll.", ja: "王宮の文書を運ぶ仕事をしている。今日届ける巻物の中身をもう読んでしまった。", zh: "负责在王宫里传送文书。已经偷看了今天要送的卷轴。" },
  "몽이": { en: "Mongi", ja: "モンイ", zh: "梦伊" },
  "로판 악역 영애로 태어난 강아지. 구두를 물어뜯은 것이 첫 번째 악행이다.": { en: "A puppy reborn as the villainess of a romance fantasy. Her first villainous deed: chewing up a shoe.", ja: "ロマンスファンタジーの悪役令嬢に生まれた子犬。靴をかじったのが最初の悪行。", zh: "转生成浪漫奇幻恶役千金的小狗。第一桩恶行是啃坏了鞋子。" },

  /* ---- 그림 안전 검사 (job/JobRunner · runs/RegenService) ---- */
  "모든 장면이 이미지 안전 기준(선정성)에 걸렸어요. 한 번 더 그려 봤지만 같았어요. 이야기 속 노출이나 선정적·잔혹한 장면이 원인일 수 있으니, 표현을 바꿔 다시 만들어 주세요.": { en: "Every scene was blocked by the image safety filter (sexual content). We tried once more with the same result. Nudity or sexual or violent scenes in the story may be the cause — please change the wording and create again.", ja: "すべての場面が画像の安全基準（性的表現）に引っかかりました。もう一度描いてみましたが同じでした。ストーリー中の露出や性的・残酷な場面が原因かもしれません。表現を変えてもう一度作ってください。", zh: "所有场景都未通过图片安全标准（色情）。我们又试了一次，结果相同。原因可能是故事里的暴露或色情、血腥场景，请修改表达后重新制作。" },
  "모든 장면이 이미지 안전 기준에 걸렸어요. 한 번 더 그려 봤지만 같았어요. 이야기 속 노출이나 선정적·잔혹한 장면이 원인일 수 있으니, 표현을 바꿔 다시 만들어 주세요.": { en: "Every scene was blocked by the image safety filter. We tried once more with the same result. Nudity or sexual or violent scenes in the story may be the cause — please change the wording and create again.", ja: "すべての場面が画像の安全基準に引っかかりました。もう一度描いてみましたが同じでした。ストーリー中の露出や性的・残酷な場面が原因かもしれません。表現を変えてもう一度作ってください。", zh: "所有场景都未通过图片安全标准。我们又试了一次，结果相同。原因可能是故事里的暴露或色情、血腥场景，请修改表达后重新制作。" },
  "이 장면이 이미지 안전 기준에 걸려 빈 장으로 두었어요. 편집실에서 장면 설명을 고쳐 다시 그려 주세요.": { en: "This scene was blocked by the image safety filter, so we left the page blank. Edit the scene description in the editor and redraw it.", ja: "この場面は画像の安全基準に引っかかったため、空白のページにしました。編集室で場面の説明を直して描き直してください。", zh: "此场景未通过图片安全标准，已留作空白页。请在编辑室修改场景描述后重新绘制。" },
  "다시 그린 그림이 이미지 안전 기준(선정성)에 걸렸어요. 빈 장은 그대로예요. 장면 설명에서 노출이나 선정적인 부분을 바꿔 다시 그려 주세요.": { en: "The redrawn picture was blocked by the image safety filter (sexual content). The blank page stays as it was. Change any nudity or sexual parts in the scene description and redraw.", ja: "描き直した絵が画像の安全基準（性的表現）に引っかかりました。空白のページはそのままです。場面の説明から露出や性的な部分を変えて描き直してください。", zh: "重绘的图片未通过图片安全标准（色情）。空白页保持不变。请修改场景描述中暴露或色情的部分后重新绘制。" },
  "다시 그린 그림이 이미지 안전 기준(선정성)에 걸렸어요. 원래 그림은 그대로예요. 장면 설명에서 노출이나 선정적인 부분을 바꿔 다시 그려 주세요.": { en: "The redrawn picture was blocked by the image safety filter (sexual content). The original picture is unchanged. Change any nudity or sexual parts in the scene description and redraw.", ja: "描き直した絵が画像の安全基準（性的表現）に引っかかりました。元の絵はそのままです。場面の説明から露出や性的な部分を変えて描き直してください。", zh: "重绘的图片未通过图片安全标准（色情）。原图保持不变。请修改场景描述中暴露或色情的部分后重新绘制。" },
  "다시 그린 그림이 이미지 안전 기준에 걸렸어요. 빈 장은 그대로예요. 장면 설명에서 노출이나 선정적인 부분을 바꿔 다시 그려 주세요.": { en: "The redrawn picture was blocked by the image safety filter. The blank page stays as it was. Change any nudity or sexual parts in the scene description and redraw.", ja: "描き直した絵が画像の安全基準に引っかかりました。空白のページはそのままです。場面の説明から露出や性的な部分を変えて描き直してください。", zh: "重绘的图片未通过图片安全标准。空白页保持不变。请修改场景描述中暴露或色情的部分后重新绘制。" },
  "다시 그린 그림이 이미지 안전 기준에 걸렸어요. 원래 그림은 그대로예요. 장면 설명에서 노출이나 선정적인 부분을 바꿔 다시 그려 주세요.": { en: "The redrawn picture was blocked by the image safety filter. The original picture is unchanged. Change any nudity or sexual parts in the scene description and redraw.", ja: "描き直した絵が画像の安全基準に引っかかりました。元の絵はそのままです。場面の説明から露出や性的な部分を変えて描き直してください。", zh: "重绘的图片未通过图片安全标准。原图保持不变。请修改场景描述中暴露或色情的部分后重新绘制。" },
  "이 장을 그리지 못했습니다 — 크레딧은 돌려드렸어요": { en: "Couldn't draw this page — your credits were refunded.", ja: "このページを描けませんでした — クレジットはお返ししました", zh: "未能绘制此页——积分已退还" },
  /* 빈 장마다 걸린 이유 — 서버는 코드(unsafe_kinds)로 주고 화면이 이 이름으로 바꾼다(UNSAFE_KIND_KO). */
  "미성년 관련": { en: "minors", ja: "未成年関連", zh: "涉及未成年人" },
  "선정성": { en: "sexual content", ja: "性的表現", zh: "色情" },
  "자해": { en: "self-harm", ja: "自傷", zh: "自残" },
  "폭력·잔혹": { en: "violence/gore", ja: "暴力・残酷", zh: "暴力・血腥" },
  "혐오": { en: "hate", ja: "ヘイト", zh: "仇恨" },
  "괴롭힘": { en: "harassment", ja: "嫌がらせ", zh: "骚扰" },
  "불법 행위": { en: "illegal activity", ja: "違法行為", zh: "违法行为" },
  "안전 기준": { en: "safety standards", ja: "安全基準", zh: "安全标准" },

  /* ---- 만드는 중 화면에서 빠져 있던 것 (screens/progress) ---- */
  "루와 놀기": { en: "Play with Lou", ja: "Louと遊ぶ", zh: "和 Lou 玩" },
  "둘러보기 하며 기다리기": { en: "Browse while you wait", ja: "見て回りながら待つ", zh: "边逛边等" },
  "마음에 안 드는 부분은 직접 고쳐도 돼요.": { en: "You can fix anything you don't like yourself.", ja: "気に入らない部分は自分で直してもかまいません。", zh: "不满意的地方可以自己修改。" },
  "완성되면 이메일로 알려드릴게요.": { en: "We'll email you when it's done.", ja: "完成したらメールでお知らせします。", zh: "完成后会发邮件通知你。" },
  "이 작품의 알림에만 써요.": { en: "Used only to notify you about this piece.", ja: "この作品の通知にだけ使います。", zh: "只用于本作品的通知。" },
};

registerDict(dict);

/* ---- 빈 장의 이유 (RunResult.unsafe_kinds) ---------------------------------
 * 서버가 주는 코드 → 한국어 이름. 이름은 위 사전의 키이고 서버 JobRunner.KIND_KO 와 같다. */
export const UNSAFE_KIND_KO: Record<string, string> = {
  minor: "미성년 관련",
  sexual: "선정성",
  self_harm: "자해",
  violence: "폭력·잔혹",
  hate: "혐오",
  harassment: "괴롭힘",
  illicit: "불법 행위",
};

/** 화면 언어의 나열 구분자. 일본어·중국어는 「、」. */
function listSep(lang?: string): string {
  const l = lang ?? (typeof document !== "undefined" ? document.documentElement.lang : "ko");
  return /^(ja|zh)/.test(l) ? "、" : ", ";
}

/**
 * 빈 장 하나의 이유를 화면 언어로. 코드가 있으면 코드로, 옛 서버라 한국어 문장만 있으면 그 문장을 t 로
 * 옮겨 본다. 둘 다 없으면 빈 글자.
 */
export function unsafeWhy(t: (s: string) => string, kinds?: string[] | null, fallback?: string | null): string {
  if (Array.isArray(kinds)) {
    const names = kinds.map((k) => UNSAFE_KIND_KO[k]).filter(Boolean);
    return (names.length ? names : ["안전 기준"]).map((n) => t(n)).join(listSep());
  }
  return fallback ? t(fallback) : "";
}

/* ---- 글 검사에 걸린 곳 (safety/SafetyGuard.messageFor) ----------------------
 * 서버는 「MESSAGE 걸린 곳: 「칸」 칸 「문장」 · 이유: 종류, 종류」 를 정해진 조각으로 잇는다. 칸 이름과
 * 종류도 정해진 말이라 조각마다 옮기고, 문장은 사용자가 쓴 글이라 그대로 둔다. 서버의 조각을 바꾸면
 * 여기도 같이 바꾼다(SafetyGuardTest 가 서버 쪽 이름이 여기 다 있는지 본다). */
const SAFETY_MESSAGE = "이 내용으로는 만들 수 없어요. 선정적이거나 잔혹한 묘사, 혐오 표현이 들어가면 걸러져요. 표현을 바꿔서 다시 시도해 주세요.";
const SAFETY_SPOT = " 걸린 곳: ";
const SAFETY_WHY = " · 이유: ";

type Tri = { en: string; ja: string; zh: string };

/** 만들기·캐릭터 화면의 칸 이름(JobService · CharacterService 의 Field.of). */
const SAFETY_FIELDS: Record<string, Tri> = {
  "이름": { en: "Name", ja: "名前", zh: "名字" },
  "캐릭터 설명": { en: "Character description", ja: "キャラクター説明", zh: "角色描述" },
  "세계관": { en: "World", ja: "世界観", zh: "世界观" },
  "장르": { en: "Genre", ja: "ジャンル", zh: "类型" },
  "이야기": { en: "Story", ja: "ストーリー", zh: "故事" },
  "사진 메모": { en: "Photo note", ja: "写真メモ", zh: "照片备注" },
  "추가 설정": { en: "Extra settings", ja: "追加設定", zh: "补充设定" },
  "제목": { en: "Title", ja: "タイトル", zh: "标题" },
  "회차": { en: "Episode", ja: "話数", zh: "话数" },
  "캐릭터 카드": { en: "Character card", ja: "キャラクターカード", zh: "角色卡" },
};

/** 글 검사의 이유 종류(SafetyGuard.KIND). */
const SAFETY_KINDS: Record<string, Tri> = {
  "선정적인 표현": { en: "sexual content", ja: "性的な表現", zh: "色情内容" },
  "미성년자를 성적으로 다루는 표현": { en: "sexual content involving minors", ja: "未成年者を性的に扱う表現", zh: "涉及未成年人的色情内容" },
  "혐오 표현": { en: "hate speech", ja: "ヘイト表現", zh: "仇恨言论" },
  "위협이 담긴 혐오 표현": { en: "threatening hate speech", ja: "脅迫を含むヘイト表現", zh: "带有威胁的仇恨言论" },
  "위협·괴롭힘": { en: "threats or harassment", ja: "脅迫・嫌がらせ", zh: "威胁或骚扰" },
  "잔혹한 폭력 묘사": { en: "graphic violence", ja: "残酷な暴力描写", zh: "血腥暴力描写" },
  "자해 묘사": { en: "self-harm", ja: "自傷の描写", zh: "自残描写" },
  "자해 의도": { en: "intent to self-harm", ja: "自傷の意図", zh: "自残意图" },
  "자해 방법": { en: "self-harm instructions", ja: "自傷の方法", zh: "自残方法" },
  "폭력적인 불법 행위": { en: "violent illegal activity", ja: "暴力的な違法行為", zh: "暴力违法行为" },
};

const SAFETY_PARTS: Record<string, Tri> = {
  spot: { en: "Flagged: ", ja: "引っかかった箇所: ", zh: "被拦截的位置：" },
  why: { en: " · Reason: ", ja: " · 理由: ", zh: " · 原因：" },
  wrote: { en: "what you wrote", ja: "入力した内容", zh: "填写的内容" },
  field: { en: "the {label} field", ja: "「{label}」欄", zh: "「{label}」栏" },
  fieldSentence: { en: "\u201c{sentence}\u201d in the {label} field", ja: "「{label}」欄の「{sentence}」", zh: "「{label}」栏中的「{sentence}」" },
  sentence: { en: "\u201c{sentence}\u201d", ja: "「{sentence}」", zh: "「{sentence}」" },
};

function safetyBlocked(src: string, tr: (s: string) => string, lang: "en" | "ja" | "zh"): string | null {
  if (!src.startsWith(SAFETY_MESSAGE + SAFETY_SPOT)) return null;
  let rest = src.slice(SAFETY_MESSAGE.length + SAFETY_SPOT.length);
  let kinds: string[] = [];
  const at = rest.lastIndexOf(SAFETY_WHY);
  if (at >= 0) {
    kinds = rest.slice(at + SAFETY_WHY.length).split(", ").filter(Boolean);
    rest = rest.slice(0, at);
  }
  const part = (k: string, v: Record<string, string> = {}) =>
    SAFETY_PARTS[k][lang].replace(/\{(\w+)\}/g, (m, n) => (n in v ? v[n] : m));
  let where: string;
  const field = /^「([^」]+)」 칸(?: 「([\s\S]*)」)?$/.exec(rest);
  if (field) {
    const label = SAFETY_FIELDS[field[1]]?.[lang] ?? field[1];
    where = field[2] != null ? part("fieldSentence", { label, sentence: field[2] }) : part("field", { label });
  } else if (rest === "적은 내용") {
    where = part("wrote");
  } else if (rest.startsWith("「") && rest.endsWith("」")) {
    where = part("sentence", { sentence: rest.slice(1, -1) });
  } else {
    return null;
  }
  const why = kinds.map((k) => SAFETY_KINDS[k]?.[lang] ?? k).join(listSep(lang));
  return tr(SAFETY_MESSAGE) + " " + part("spot") + where + (why ? part("why") + why : "");
}

registerComposer(safetyBlocked);

