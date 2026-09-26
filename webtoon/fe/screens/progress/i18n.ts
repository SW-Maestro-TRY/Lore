/* 만드는 중 · 한도 소진 화면 사전 — 한국어 원문이 키. lib/progressData.ts 의
 * 단계 이름·마스코트 한 줄도 쓰는 자리에서 t() 하므로 원문 그대로 넣어 둔다. */
import { registerDict, type Dict } from "../../lib/i18n";

const dict: Dict = {
  /* ---- 빵부스러기 · 공통 ---- */
  "캐릭터": { en: "Character", ja: "キャラクター", zh: "角色" },
  "이야기 · 장르": { en: "Story · Genre", ja: "ストーリー · ジャンル", zh: "故事 · 题材" },
  "그림체": { en: "Art style", ja: "画風", zh: "画风" },
  "방식": { en: "Options", ja: "進め方", zh: "方式" },
  "만들기": { en: "Create", ja: "作成", zh: "制作" },
  "완성": { en: "Done", ja: "完成", zh: "完成" },
  "지금 위치": { en: "Current step", ja: "現在の位置", zh: "当前位置" },
  "다시 시도": { en: "Try again", ja: "もう一度", zh: "重试" },
  "닫기": { en: "Close", ja: "閉じる", zh: "关闭" },
  "크게 보기": { en: "View larger", ja: "拡大表示", zh: "放大查看" },

  /* ---- 왼쪽 줄 단계 ---- */
  "이야기 짓기": { en: "Writing the story", ja: "ストーリー作り", zh: "编写故事" },
  "축을 뽑고 방향 4개를 씁니다": { en: "Finds the core and writes four directions", ja: "軸を決めて4つの方向を書きます", zh: "确定主轴并写出 4 个方向" },
  "캐릭터 그리기": { en: "Drawing the character", ja: "キャラクターを描く", zh: "绘制角色" },
  "앞·옆·뒤 모습과 표정을 한 장에": { en: "Front, side, back and expressions on one sheet", ja: "正面・横・後ろ姿と表情を一枚に", zh: "正面、侧面、背面与表情画在一张上" },
  "회차 짜기": { en: "Planning the episode", ja: "話の構成", zh: "编排本话" },
  "장면 순서와 이번 화에서 남겨 둘 것": { en: "Scene order and what to save for later", ja: "場面の順番と今回残しておくこと", zh: "场景顺序与本话留下的伏笔" },
  "페이지 그림": { en: "Drawing pages", ja: "ページの絵", zh: "绘制页面" },
  "표지와 장면을 차례로, 한 장마다 검수": { en: "Cover and scenes in order, each page checked", ja: "表紙と場面を順に、一枚ごとに検査", zh: "封面与场景依次绘制，每页检查" },

  /* ---- 오류 ---- */
  "상태를 받지 못했습니다": { en: "Couldn't get the status", ja: "状態を取得できませんでした", zh: "无法获取状态" },
  "보내지 못했습니다": { en: "Couldn't send that", ja: "送信できませんでした", zh: "发送失败" },
  "연결이 잠깐 끊겼어요 — 다시 받아오는 중입니다.": { en: "Connection dropped for a moment — reconnecting.", ja: "接続が一時的に切れました — 再取得しています。", zh: "连接暂时中断 — 正在重新获取。" },

  /* ---- 루 카드 ---- */
  "잠깐 봐 주세요": { en: "Take a look", ja: "ちょっと見てください", zh: "请看一下" },
  "앞에 대기자가 많아…": { en: "There's a line ahead…", ja: "前に待っている人が多くて…", zh: "前面排队的人有点多…" },
  "{n}번째 장을 그리고 있어요": { en: "Drawing page {n}", ja: "{n}枚目を描いています", zh: "正在画第 {n} 页" },
  "루가 만들고 있어요": { en: "Lou is working on it", ja: "Louが作っています", zh: "Lou 正在制作" },
  "닫아도 괜찮아요. 다 되면 이메일로 알려드려요.": { en: "You can close this. We'll email you when it's done.", ja: "閉じても大丈夫です。できたらメールでお知らせします。", zh: "可以关闭页面。完成后会发邮件通知你。" },
  "닫아도 괜찮아요.": { en: "You can close this.", ja: "閉じても大丈夫です。", zh: "可以关闭页面。" },
  "현재 대기자 {n}명 · 약 {m}분 뒤 시작": { en: "{n} ahead of you · starts in about {m} min", ja: "現在{n}人待ち · 約{m}分後に開始", zh: "前方 {n} 人 · 约 {m} 分钟后开始" },
  "{pct}% · {time} 경과": { en: "{pct}% · {time} elapsed", ja: "{pct}% · {time}経過", zh: "{pct}% · 已用 {time}" },
  "{pct}% · {time} 경과 · 약 {n}분 남았어요": { en: "{pct}% · {time} elapsed · about {n} min left", ja: "{pct}% · {time}経過 · 残り約{n}分", zh: "{pct}% · 已用 {time} · 还剩约 {n} 分钟" },
  "사용된 크레딧은 자동으로 환불되었어요.": { en: "The credits used have been refunded automatically.", ja: "使用したクレジットは自動的に返金されました。", zh: "已使用的点数已自动退还。" },
  "사용한 무료 생성 횟수는 자동으로 복구되었어요.": { en: "Your free creation has been restored automatically.", ja: "使用した無料生成回数は自動的に戻りました。", zh: "已使用的免费次数已自动恢复。" },

  /* lib/progressData — STAGE_SPEC · MASCOT_MOODS (서버 stage_label 로도 올 수 있다) */
  "루가 이야기를 만들고 있어요": { en: "Lou is writing the story", ja: "Louがストーリーを作っています", zh: "Lou 正在编故事" },
  "루가 캐릭터를 디자인하고 있어요": { en: "Lou is designing the character", ja: "Louがキャラクターをデザインしています", zh: "Lou 正在设计角色" },
  "루가 콘티를 짜고 있어요": { en: "Lou is planning the storyboard", ja: "Louがネームを組んでいます", zh: "Lou 正在画分镜" },
  "루가 그림을 그리고 있어요": { en: "Lou is drawing", ja: "Louが絵を描いています", zh: "Lou 正在画画" },
  "루가 완성도를 확인하고 있어요": { en: "Lou is checking the result", ja: "Louが仕上がりを確認しています", zh: "Lou 正在检查成品" },

  /* ---- 왼쪽 줄 (2026-09-19 재편 — 회차 짜기를 없애고 검수하기를 더함) ---- */
  "페이지 그리기": { en: "Drawing pages", ja: "ページを描く", zh: "绘制页面" },
  "컷을 나누고 표지와 장면을 차례로": { en: "Splitting panels, then cover and scenes in order", ja: "コマを割り、表紙と場面を順に", zh: "分格后依次绘制封面与场景" },
  "검수하기": { en: "Reviewing", ja: "検査", zh: "检查" },
  "그린 장을 잇고 마지막으로 살펴봅니다": { en: "Joins the pages and takes one last look", ja: "描いたページをつなぎ、最後に確認します", zh: "拼接页面并做最后检查" },
  "기다리는 동안 루를 놀아주세요!": { en: "Play with Lou while you wait!", ja: "待っている間Louと遊んであげて！", zh: "等待时陪 Lou 玩一下吧！" },
  "웹툰 보면서 기다리기": { en: "Read webtoons while you wait", ja: "ウェブトゥーンを見ながら待つ", zh: "边看条漫边等" },
  "루가 이야기를 짜고 있어요": { en: "Lou is plotting the story", ja: "Louがストーリーを組んでいます", zh: "Lou 正在构思故事" },
  "루가 캐릭터를 그리고 있어요": { en: "Lou is drawing the character", ja: "Louがキャラクターを描いています", zh: "Lou 正在画角色" },
  "루가 페이지를 그리고 있어요": { en: "Lou is drawing the pages", ja: "Louがページを描いています", zh: "Lou 正在画页面" },
  "루가 검수하고 있어요": { en: "Lou is reviewing it", ja: "Louが検査しています", zh: "Lou 正在检查" },
  "검수하고 있어요": { en: "Reviewing it", ja: "検査中です", zh: "正在检查" },
  "루를 눌러 보세요": { en: "Try tapping Lou", ja: "Louを押してみてください", zh: "点点看 Lou" },
  "루를 눌러 보기": { en: "Tap Lou", ja: "Louを押す", zh: "点击 Lou" },
  "누르기 · 연달아 누르기 · 꾹 누르기 · 끌어당기기": { en: "Tap · double-tap · hold · drag", ja: "押す · 連打 · 長押し · 引っぱる", zh: "点击 · 连点 · 长按 · 拖拽" },
  "팁": { en: "Tip", ja: "ヒント", zh: "小贴士" },

  /* ---- 이메일 ---- */
  "완성되면 {email} 으로 알림을 드릴게요": { en: "We'll notify {email} when it's done", ja: "完成したら{email}にお知らせします", zh: "完成后会通知 {email}" },
  "지금 약 {n}분 남았어요.": { en: "About {n} min left.", ja: "残り約{n}分です。", zh: "还剩约 {n} 分钟。" },
  "마이페이지 설정": { en: "My page settings", ja: "マイページの設定", zh: "我的页面设置" },
  "에서 끌 수 있어요!": { en: " — you can turn this off there.", ja: "でオフにできます！", zh: "中可以关闭！" },
  "완성되면 계정 이메일로 알림을 드릴게요": { en: "We'll notify your account email when it's done", ja: "完成したらアカウントのメールにお知らせします", zh: "完成后会通知你的账户邮箱" },
  "이메일을 입력해 주시면 완성되면 결과물을 보여드릴게요!": { en: "Enter your email and we'll send you the result when it's done!", ja: "メールを入力すると、完成したら結果をお届けします！", zh: "填写邮箱，完成后把成品发给你！" },
  "이메일": { en: "Email", ja: "メール", zh: "邮箱" },
  "알림 받기": { en: "Notify me", ja: "通知を受け取る", zh: "接收通知" },
  "이 작품의 알림에만 써요. 광고는 보내지 않아요. 안 적으셔도 만들기는 그대로 진행돼요.": { en: "Used only to notify you about this piece. No ads. Creation continues even if you skip this.", ja: "この作品の通知にだけ使います。広告は送りません。入力しなくても作成はそのまま進みます。", zh: "只用于本作品的通知，不发广告。不填也会继续制作。" },

  /* ---- 단추 ---- */
  "이 얼굴로 갈게요": { en: "Go with this face", ja: "この顔で進める", zh: "就用这张脸" },
  "다시 만들기": { en: "Redo", ja: "作り直す", zh: "重新生成" },
  "선택 완료 · {n}번으로": { en: "Choose · go with #{n}", ja: "選択完了 · {n}番で", zh: "选好了 · 用第 {n} 个" },
  "후보 다시 만들기": { en: "Regenerate options", ja: "候補を作り直す", zh: "重新生成候选" },
  "이대로 진행하기": { en: "Continue as is", ja: "このまま進める", zh: "就这样继续" },
  "다른 이야기 보기": { en: "See other stories", ja: "別のストーリーを見る", zh: "看其他故事" },
  "홈으로 가기": { en: "Go home", ja: "ホームへ", zh: "回到首页" },
  "다른 사람 웹툰 둘러보기": { en: "Browse other people's webtoons", ja: "他の人のウェブトゥーンを見る", zh: "看看别人的条漫" },
  "기다리는 동안 웹툰 보기": { en: "Read webtoons while you wait", ja: "待つ間にウェブトゥーンを見る", zh: "等待时看看条漫" },
  "만들기 중단": { en: "Stop creating", ja: "作成を中止", zh: "中止制作" },
  "계속 만들기": { en: "Keep going", ja: "続ける", zh: "继续制作" },
  "중단하기": { en: "Stop", ja: "中止する", zh: "中止" },
  "접기": { en: "Collapse", ja: "たたむ", zh: "收起" },
  "펼쳐 보기": { en: "Expand", ja: "広げて見る", zh: "展开" },
  "눌러서 크게 보기": { en: "Tap to enlarge", ja: "押して拡大", zh: "点击放大" },

  /* ---- 실패 ---- */
  "멈췄습니다": { en: "Stopped", ja: "停止しました", zh: "已停止" },
  "웹툰 생성에 실패했어요": { en: "Couldn't create the webtoon", ja: "ウェブトゥーンの生成に失敗しました", zh: "条漫生成失败" },

  /* ---- 캐릭터 시트 확인 ---- */
  "캐릭터 시트를 확인해 주세요": { en: "Check the character sheet", ja: "キャラクターシートを確認してください", zh: "请确认角色设定图" },
  "이제부터 모든 페이지가 이 얼굴을 따라갑니다. 원본과 다르면 여기서 다시 만들어요. 확인 전까지는 아무것도 안 돌아가요.": { en: "Every page from here on follows this face. If it doesn't match the original, redo it here. Nothing runs until you confirm.", ja: "これからすべてのページがこの顔に従います。元と違うならここで作り直してください。確認するまでは何も進みません。", zh: "从现在起所有页面都会沿用这张脸。和原图不像的话，请在这里重做。确认之前不会有任何进展。" },
  "고칠 점을 적고 다시 만들기 · 예: 머리를 더 길게": { en: "Note what to fix and redo · e.g. longer hair", ja: "直したい点を書いて作り直す · 例：髪をもっと長く", zh: "写下要改的地方再重做 · 例如：头发再长一点" },
  "다시 만들기 메모": { en: "Redo note", ja: "作り直しメモ", zh: "重做备注" },

  /* ---- 이야기 고르기 ---- */
  "어느 이야기로 갈까요?": { en: "Which story should we go with?", ja: "どのストーリーにしますか？", zh: "选哪个故事？" },
  "넷 중 하나를 고르면 그 뒤로는 안 멈춰요. 고른 이야기는 다음 화면에서 본문을 직접 고칠 수 있어요.": { en: "Pick one of the four and it won't stop again. You can edit the chosen story's text on the next screen.", ja: "4つから1つ選ぶと、その後は止まりません。選んだストーリーは次の画面で本文を直接直せます。", zh: "四选一之后就不会再停。所选故事可在下一屏直接修改正文。" },
  "바라는 방향을 적고 후보 다시 만들기": { en: "Note the direction you want and regenerate", ja: "望む方向を書いて候補を作り直す", zh: "写下想要的方向并重新生成候选" },

  /* ---- 본문 확인 ---- */
  "내용을 확인하세요. 마음에 안 드는 부분이 있으면 직접 고쳐도 됩니다 — 안 고쳐도 됩니다.": { en: "Read it over. Edit anything you don't like — or leave it as is.", ja: "内容を確認してください。気に入らない部分は直接直しても構いません — 直さなくても大丈夫です。", zh: "请确认内容。不满意的地方可以直接修改 — 不改也可以。" },
  "이야기 본문": { en: "Story text", ja: "ストーリー本文", zh: "故事正文" },
  "고친 내용은 원래 본문과 다를 때만 실려 가서, 다음 단계(장면 나누기)부터 그 내용을 씁니다.": { en: "Edits are sent only if they differ from the original, and are used from the next step (scene splitting) on.", ja: "修正内容は元の本文と異なる場合のみ送られ、次の段階（場面分け）からその内容を使います。", zh: "修改内容只有与原文不同时才会发送，并从下一步（分场景）开始使用。" },

  /* ---- 그리는 중 ---- */
  "페이지를 그리고 있어요": { en: "Drawing the pages", ja: "ページを描いています", zh: "正在画页面" },
  "만들고 있어요": { en: "Working on it", ja: "作っています", zh: "正在制作" },
  "한 장을 그릴 때마다 앞 장과 이어지는지, 글이 그림에 담겼는지 검수하고 걸리면 다시 그려요.": { en: "After each page we check that it follows the last one and that the text made it into the picture — and redraw if not.", ja: "一枚描くごとに前のページとつながるか、文が絵に入っているかを検査し、引っかかれば描き直します。", zh: "每画一页都会检查是否衔接上一页、文字是否进入画面，有问题就重画。" },
  "약 {n}분 남았어요.": { en: "About {n} min left.", ja: "残り約{n}分です。", zh: "还剩约 {n} 分钟。" },
  "만들기는 서버에서 계속 돌아요. 나갔다 와도 이어집니다.": { en: "Creation keeps running on the server. You can leave and come back.", ja: "作成はサーバーで続きます。離れて戻っても続いています。", zh: "制作会在服务器上继续。离开再回来也不会中断。" },
  "고른 이야기 · {title}": { en: "Chosen story · {title}", ja: "選んだストーリー · {title}", zh: "所选故事 · {title}" },
  "이미 이 이야기로 그리는 중이라 다시 고를 수 없어요.": { en: "Already drawing with this story, so it can't be changed.", ja: "すでにこのストーリーで描いているため、選び直せません。", zh: "已经在用这个故事绘制，无法再更改。" },
  "그려진 장": { en: "Pages drawn", ja: "描かれたページ", zh: "已画页面" },
  "{done} / {total}장 · 그려진 순서대로, 완성본과 같은 폭으로": { en: "{done} / {total} · in drawing order, same width as the final", ja: "{done} / {total}枚 · 描かれた順に、完成版と同じ幅で", zh: "{done} / {total} 页 · 按绘制顺序，与成品同宽" },
  "정말로 중단하시겠습니까?": { en: "Really stop?", ja: "本当に中止しますか？", zh: "确定要中止吗？" },
  "크레딧은 환불되지 않습니다.": { en: "Credits won't be refunded.", ja: "クレジットは返金されません。", zh: "点数不会退还。" },
  "지금까지 그려 둔 장은 그대로 남습니다 — 편집실에서 볼 수 있습니다.": { en: "Pages drawn so far are kept — you can see them in the editor.", ja: "これまでに描いたページはそのまま残ります — 編集室で見られます。", zh: "已画好的页面会保留 — 可在编辑室查看。" },

  /* ---- 지나온 단계 ---- */
  "지어낸 이야기": { en: "Generated stories", ja: "作ったストーリー", zh: "生成的故事" },
  "회차 짜기 · {title}": { en: "Episode plan · {title}", ja: "話の構成 · {title}", zh: "本话编排 · {title}" },
  "{n}쪽": { en: "Page {n}", ja: "{n}ページ", zh: "第 {n} 页" },
  "{n}번째 장이 걸려서 다시 그리고 있어요": { en: "Page {n} didn't pass, redrawing it", ja: "{n}枚目が引っかかったので描き直しています", zh: "第 {n} 页没通过，正在重画" },

  /* ---- 한도 소진 (GuestLimit) ---- */
  "게스트": { en: "Guest", ja: "ゲスト", zh: "访客" },
  "오늘 무료 편수를 다 썼어요": { en: "You've used today's free creations", ja: "今日の無料枠を使い切りました", zh: "今天的免费次数已用完" },
  "게스트는 하루 2편까지 무료예요. 로그인하면 크레딧으로 바로 이어서 만들 수 있어요. 지금까지 만든 것은 그대로 남아요.": { en: "Guests get 2 free creations a day. Sign in to keep going with credits. Everything you've made so far stays.", ja: "ゲストは1日2本まで無料です。ログインするとクレジットですぐに続けられます。これまでに作ったものはそのまま残ります。", zh: "访客每天可免费制作 2 部。登录后可用点数立即继续。已制作的内容会保留。" },
  "로그인하고 이어서 만들기": { en: "Sign in and continue", ja: "ログインして続ける", zh: "登录后继续制作" },
  "둘러보기 하며 내일 다시": { en: "Explore and come back tomorrow", ja: "見て回って、また明日", zh: "先浏览，明天再来" },
  "전체 마감": { en: "Daily limit reached", ja: "本日分終了", zh: "今日总量已满" },
  "오늘은 여기까지예요": { en: "That's all for today", ja: "今日はここまでです", zh: "今天就到这里" },
  "오늘 만들 수 있는 전체 편수가 찼어요. 자정이 지나면 다시 만들 수 있어요.": { en: "Today's total creation limit has been reached. You can create again after midnight.", ja: "今日作れる全体の本数に達しました。深夜0時を過ぎるとまた作れます。", zh: "今天可制作的总量已满。过了午夜就可以再制作。" },
  "완성본 미리 보기": { en: "Preview the finished pages", ja: "完成版をプレビュー", zh: "预览成品" },
};

registerDict(dict);
