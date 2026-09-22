/* 첫 화면·입구·편집실 목업의 사전. 원문(한국어)이 키다 — lib/i18n.tsx 참고.
 * FAQ 답의 `**…**` 는 화면에서 굵게 그린다(언어마다 어순이 달라 조각내지 않는다). */
import { registerDict, type Dict } from "../../lib/i18n";

const dict: Dict = {
  /* ---- 히어로 ---- */
  "만들던 웹툰": { en: "Webtoon in progress", ja: "作りかけのウェブトゥーン", zh: "正在制作的漫画" },
  "{done} / {total}장": { en: "{done} / {total} pages", ja: "{done} / {total}枚", zh: "{done} / {total} 页" },
  "AI 웹툰 스튜디오, LORE": { en: "LORE, the AI webtoon studio", ja: "AIウェブトゥーン制作サービス LORE", zh: "AI 漫画创作服务 LORE" },
  "내 캐릭터가 이야기 속에서 살아 움직이는 순간.": {
    en: "The moment my character comes to life in the story.",
    ja: "私のキャラクターが物語の中で生き生きと動き出す瞬間。",
    zh: "我的角色在故事中鲜活起来的瞬间。"
  },
  "지금 시작하기": { en: "Get started", ja: "はじめる", zh: "立即开始" },
  "오늘 무료 {n}편": { en: "{n} free today", ja: "本日無料 {n}話", zh: "今日免费 {n} 话" },
  "오늘 무료 소진 · 로그인하면 이어서": { en: "Today's free episodes are used up · sign in to keep going", ja: "本日の無料分は終了・ログインすると続けられます", zh: "今日免费次数已用完 · 登录后可继续" },
  "한 편 {cost}크레딧 · 보유 {balance}C": { en: "{cost} credits per episode · you have {balance}C", ja: "1話 {cost}クレジット・残高 {balance}C", zh: "每话 {cost} 积分 · 余额 {balance}C" },

  /* ---- 예시 작품 띠 ---- */
  "웹툰 전체 보러가기": { en: "See all webtoons", ja: "ウェブトゥーンをすべて見る", zh: "查看全部漫画" },
  "작품을 못 불러왔습니다": { en: "Couldn't load the works", ja: "作品を読み込めませんでした", zh: "无法加载作品" },
  "다시 시도": { en: "Try again", ja: "もう一度", zh: "重试" },
  "{title} 표지": { en: "{title} cover", ja: "{title} の表紙", zh: "{title} 封面" },

  /* ---- 4단계 ---- */
  "고르면, 이렇게 만들어져요": { en: "Pick, and here's how it's made", ja: "選ぶと、こうして作られます", zh: "选好后，就会这样做出来" },
  "나만의 이야기를 만들어 보세요!": { en: "Make a story that's all yours!", ja: "自分だけの物語を作ってみましょう！", zh: "创作属于你的故事吧！" },
  "세라핀": { en: "Seraphine", ja: "セラフィン", zh: "塞拉芬" },
  "로맨스": { en: "Romance", ja: "ロマンス", zh: "爱情" },
  "판타지": { en: "Fantasy", ja: "ファンタジー", zh: "奇幻" },
  "액션": { en: "Action", ja: "アクション", zh: "动作" },
  "일상": { en: "Slice of life", ja: "日常", zh: "日常" },
  "스릴러": { en: "Thriller", ja: "スリラー", zh: "惊悚" },
  "웹툰 만들기": { en: "Make a webtoon", ja: "ウェブトゥーンを作る", zh: "制作漫画" },
  "시작": { en: "Start", ja: "はじめる", zh: "开始" },
  "캐릭터": { en: "Character", ja: "キャラクター", zh: "角色" },
  "컷": { en: "Panels", ja: "コマ", zh: "分镜" },
  "완성": { en: "Done", ja: "完成", zh: "完成" },

  /* ---- 니즈 카드 둘 ---- */
  "이야기가 웹툰이 되는 과정,": { en: "From a story to a finished webtoon,", ja: "物語がウェブトゥーンになるまで、", zh: "从故事到漫画的全过程，" },
  "이미지 한장이 웹툰이 되는 과정,": { en: "From a single image to a finished webtoon,", ja: "1枚の画像がウェブトゥーンになるまで、", zh: "从一张图片到漫画的全过程，" },
  "LORE 하나로 충분합니다": { en: "LORE is all you need", ja: "LOREひとつで十分です", zh: "有 LORE 就够了" },
  "LORE 하나로 충분합니다.": { en: "LORE is all you need.", ja: "LOREひとつで十分です。", zh: "有 LORE 就够了。" },
  " 하나로 충분합니다": { en: " is all you need", ja: "ひとつで十分です", zh: " 就够了" },
  " 하나로 충분합니다.": { en: " is all you need.", ja: "ひとつで十分です。", zh: " 就够了。" },
  "내 캐릭터가 살아 움직이는 걸 보세요": { en: "Watch your character come to life", ja: "あなたのキャラクターが動き出すのを見てください", zh: "看你的角色活起来" },
  "설정만 있던 캐릭터가 이야기 속에서 말하고 움직여요. 캐릭터를 넣으면 그 캐릭터가 주인공인 웹툰이 나옵니다.": {
    en: "A character that was only a description starts talking and moving inside a story. Put in a character, and you get a webtoon with them as the lead.",
    ja: "設定だけだったキャラクターが、物語の中で話し、動き出します。キャラクターを入れると、そのキャラクターが主人公のウェブトゥーンができます。",
    zh: "只有设定的角色，会在故事里开口说话、动起来。放入角色，就会得到以这个角色为主角的漫画。",
  },
  "설정만 있던 캐릭터가 이야기 속에서 말하고 움직여요.": {
    en: "A character that was only a description starts talking and moving inside a story.",
    ja: "設定だけだったキャラクターが、物語の中で話し、動き出します。",
    zh: "只有设定的角色，会在故事里开口说话、动起来。",
  },
  "캐릭터를 넣으면, 그 캐릭터가 주인공인 웹툰이 나옵니다.": {
    en: "Put in a character, and you get a webtoon with them as the lead.",
    ja: "キャラクターを入れると、そのキャラクターが主人公のウェブトゥーンができます。",
    zh: "放入角色，就会得到以这个角色为主角的漫画。",
  },
  "뭐든 넣으면 웹툰 속 캐릭터가 돼요": { en: "Put in anything, and it becomes a webtoon character", ja: "何を入れても、ウェブトゥーンのキャラクターになります", zh: "放进什么，都会变成漫画角色" },
  "어떤 캐릭터가 나올지, 뽑아볼까요?": { en: "Curious which character you'd get? Draw one", ja: "どんなキャラクターが出るか、引いてみませんか？", zh: "会出来什么角色？抽一个看看吧" },
  "내 사진도, 최애도, 강아지도, 아무것도 없어도 돼요. 의인화 없이 그대로 웹툰 세계관에 들어가요.": {
    en: "Your photo, your fave, your dog, or nothing at all. It enters the webtoon world as it is, with no humanizing.",
    ja: "自分の写真でも、推しでも、犬でも、何もなくても大丈夫。擬人化せず、そのままウェブトゥーンの世界観に入ります。",
    zh: "你的照片、本命、小狗，或者什么都不放都可以。不做拟人化，原样进入漫画的世界观。",
  },
  "사진을 넣어도, 이야기를 적어도, 아무것도 없이 시작해도 좋아요.": {
    en: "Add a photo, write a story, or start with nothing at all.",
    ja: "写真を入れても、物語を書いても、何もなしで始めても大丈夫です。",
    zh: "放照片、写故事，或者什么都不放直接开始，都可以。",
  },
  "당신이 고른 세계관에 맞춰 새로운 캐릭터를 만들어드려요.": {
    en: "We'll make a new character to fit the world you choose.",
    ja: "あなたが選んだ世界観に合わせて、新しいキャラクターを作ります。",
    zh: "会按你选的世界观，做出一个新角色。",
  },
  "웹툰 한 컷": { en: "A webtoon panel", ja: "ウェブトゥーンの1コマ", zh: "漫画的一格" },
  "로판": { en: "Rom-fantasy", ja: "ロマファン", zh: "浪漫奇幻" },
  "멍!": { en: "Woof!", ja: "ワン！", zh: "汪！" },
  "몽이는 이 로맨스 판타지 웹툰에서 아주 악마같은 악역 영애예요": {
    en: "In this romance-fantasy webtoon, Mongi is a devilishly wicked villainess",
    ja: "モンイはこのロマンスファンタジーウェブトゥーンで、まるで悪魔のような悪役令嬢です",
    zh: "在这部浪漫奇幻漫画里，蒙伊是个像恶魔一样凶狠的反派千金",
  },
  "이 캐릭터로 1화 보기": { en: "See episode 1 with this character", ja: "このキャラクターで第1話を見る", zh: "用这个角色看第 1 话" },

  /* ---- 약속 셋 ---- */
  "웹툰 1화를 위해서": { en: "For your first episode,", ja: "ウェブトゥーン第1話のために", zh: "为了漫画第 1 话，" },
  "LORE가 약속 하는 세 가지": { en: "three things LORE promises", ja: "LOREが約束する3つのこと", zh: "LORE 承诺的三件事" },
  "같은 얼굴, 마지막 컷까지": { en: "The same face, right to the last panel", ja: "同じ顔で、最後のコマまで", zh: "同一张脸，直到最后一格" },
  "캐릭터 시트를 먼저 만들어 두어, 어느 장면에서도 얼굴과 옷이 흐트러지지 않아요.": {
    en: "The character sheet is made first, so the face and outfit hold steady in every scene.",
    ja: "先にキャラクターシートを作っておくので、どの場面でも顔と服が崩れません。",
    zh: "先做好角色设定表，任何场景里脸和衣服都不会走样。",
  },
  "마음에 안 드는 컷만 다시": { en: "Redo only the panels you don't like", ja: "気に入らないコマだけ描き直し", zh: "只重画不满意的格子" },
  "한 편을 다시 만들지 않아요. 그 컷만 콕 집어 다시 그려요.": {
    en: "You never remake the whole episode. Point at the panel, and only that one is redrawn.",
    ja: "1話まるごと作り直しはしません。そのコマだけを選んで描き直します。",
    zh: "不用整话重做。只挑出那一格，重新画它。",
  },
  "넣은 그대로, 그 세계관 안에": { en: "As you put it in, inside that world", ja: "入れたそのままで、その世界観の中へ", zh: "原样不变，进入那个世界观" },
  "강아지는 강아지인 채로 악역 영애가 돼요. 사람으로 바꾸지도, 다른 얼굴로 바꾸지도 않아요.": {
    en: "A puppy becomes the villainess while staying a puppy. We don't turn it into a person or swap in a different face.",
    ja: "子犬は子犬のまま悪役令嬢になります。人間に変えることも、別の顔に変えることもありません。",
    zh: "小狗保持小狗的样子成为反派千金。不会变成人，也不会换成别的脸。",
  },

  /* ---- 마지막 CTA ---- */
  "당신의 이야기를 기다리고 있어요": { en: "Your story is waiting", ja: "あなたの物語を待っています", zh: "等待着你的故事" },
  "당신의 이야기를\n기다리고 있어요": { en: "Your story\nis waiting", ja: "あなたの物語を\n待っています", zh: "等待着\n你的故事" },
  "만들러가기": { en: "Start making", ja: "作りに行く", zh: "去创作" },

  /* ---- FAQ ---- */
  "자주 묻는 것": { en: "FAQ", ja: "よくある質問", zh: "常见问题" },

  "LORE는 어떤 서비스인가요?": { en: "What is LORE?", ja: "LOREはどんなサービスですか？", zh: "LORE 是什么服务？" },
  "사진 한 장과 이름만 주시면 **웹툰 한 화가 통째로** 나오는 스튜디오예요. 캐릭터를 그리고, 이야기를 짓고, 표지부터 마지막 장까지 순서대로 그려 드립니다. 그림을 못 그려도, 이야기를 안 써 봤어도 괜찮아요.": {
    en: "It's a studio where one photo and a name become **a whole webtoon episode**. We draw the character, write the story, and draw every page in order, from the cover to the last one. You don't need to draw, and you don't need to have written a story before.",
    ja: "写真1枚と名前だけで、**ウェブトゥーン1話がまるごと**できるスタジオです。キャラクターを描き、物語を作り、表紙から最後のページまで順番に描きます。絵が描けなくても、物語を書いたことがなくても大丈夫です。",
    zh: "只要一张照片和一个名字，就能做出**完整的一话漫画**的工作室。我们会画角色、写故事，从封面到最后一页按顺序画好。不会画画、没写过故事也没关系。",
  },

  "LORE의 개발자는 누구인가요?": { en: "Who builds LORE?", ja: "LOREは誰が作っていますか？", zh: "LORE 是谁开发的？" },
  "**AI·SW 마에스트로 17기**에서 만들고 있는 프로젝트예요. 과학기술정보통신부가 주관하고 정보통신기획평가원(IITP)이 운영하는 AI 소프트웨어 인재 양성 과정에서, 실제로 기획하고 개발하고 있습니다.": {
    en: "It's a project being built in the **17th class of AI·SW Maestro**, an AI software talent program hosted by Korea's Ministry of Science and ICT and run by the Institute of Information & Communications Technology Planning & Evaluation (IITP). We plan and build it there for real.",
    ja: "**AI・SWマエストロ17期**で開発しているプロジェクトです。韓国の科学技術情報通信部が主催し、情報通信企画評価院(IITP)が運営するAIソフトウェア人材育成プログラムの中で、実際に企画・開発しています。",
    zh: "这是**AI·SW Maestro 第17期**正在开发的项目。在韩国科学技术信息通信部主办、信息通信规划评价院(IITP)运营的 AI 软件人才培养项目中实际策划和开发。",
  },

  "그림을 하나도 못 그려도 쓸 수 있나요?": { en: "Can I use it if I can't draw at all?", ja: "絵がまったく描けなくても使えますか？", zh: "完全不会画画也能用吗？" },
  "네, 그림을 못 그리셔도 괜찮아요. 사진 한 장과 이름만 있으면 버튼 하나로 끝까지 완성됩니다.": {
    en: "Yes, you don't need to draw. With one photo and a name, a single button takes it all the way to a finished episode.",
    ja: "はい、絵が描けなくても大丈夫です。写真1枚と名前があれば、ボタンひとつで最後まで完成します。",
    zh: "可以，不会画画也没关系。只要一张照片和一个名字，按一个按钮就能做到完成。",
  },

  "캐릭터를 만드는 데 얼마나 걸리나요?": { en: "How long does it take to make a character?", ja: "キャラクターを作るのにどのくらいかかりますか？", zh: "做一个角色要多久？" },
  "보통 **몇 분이면** 끝나요. 올린 사진과 그때의 대기 상황에 따라 조금 더 걸릴 수 있어요. 만드는 동안 화면을 보고 있지 않아도 됩니다.": {
    en: "Usually just **a few minutes**. It can take a little longer depending on the photo and how busy things are. You don't have to watch the screen while it works.",
    ja: "だいたい**数分**で終わります。アップロードした写真やそのときの混み具合によって、少し長くなることがあります。作っている間、画面を見ている必要はありません。",
    zh: "通常**几分钟**就好。会因照片情况和当时的排队情况稍有变化。制作期间不用一直盯着画面。",
  },

  "웹툰 1화를 만드는 데 얼마나 걸리나요?": { en: "How long does one webtoon episode take?", ja: "ウェブトゥーン1話を作るのにどのくらいかかりますか？", zh: "做一话漫画要多久？" },
  "이야기를 짓고 컷을 한 장씩 그리기까지 **보통 5~15분**이 걸려요. 고른 화질이 높을수록, 앞에 기다리는 작업이 있을수록 더 걸립니다. 진행 화면에 지금 몇 번째 걸음인지와 대기 순번이 나와요.": {
    en: "From writing the story to drawing each panel, **usually 5 to 15 minutes**. Higher image quality takes longer, and so does waiting behind other jobs. The progress screen shows which step you're on and your place in the queue.",
    ja: "物語を作り、コマを1枚ずつ描き上げるまで**普通は5〜15分**かかります。選んだ画質が高いほど、前に待っている作業が多いほど長くなります。進行画面に今どの段階かと待ち順が表示されます。",
    zh: "从写故事到一格格画完，**通常 5~15 分钟**。选的画质越高、前面排队的任务越多，就越久。进度画面会显示当前处于哪一步以及排队位置。",
  },

  "생성하는 동안 다른 일을 해도 되나요?": { en: "Can I do something else while it's generating?", ja: "生成中に別のことをしてもいいですか？", zh: "生成的时候可以去做别的事吗？" },
  "네. 생성이 시작된 뒤에는 **화면을 계속 보고 있을 필요가 없어요.** 진행 상황은 서버가 갖고 있어서 다른 페이지를 보거나 창을 닫아도 계속 진행되고, 나중에 다시 들어오면 하던 데서 이어집니다. 로그인했거나 알림 받을 이메일을 남겨 두면 다 됐을 때 메일로 알려 드려요.": {
    en: "Yes. Once it starts, **you don't have to keep watching the screen.** The server holds your progress, so it keeps going even if you browse elsewhere or close the window, and it picks up where it left off when you return. If you're signed in or leave an email address, we'll email you when it's done.",
    ja: "はい。生成が始まったら、**画面を見続ける必要はありません。**進行状況はサーバーが持っているので、他のページを見てもウィンドウを閉じても処理は続き、後で戻れば途中から再開します。ログインしているか、通知用のメールアドレスを残しておけば、完成時にメールでお知らせします。",
    zh: "可以。开始生成后，**不需要一直看着画面。**进度保存在服务器上，就算去看别的页面或关掉窗口也会继续，之后回来会从原处接着显示。如果已登录或留了通知邮箱，完成时我们会发邮件告诉你。",
  },

  "마음에 들지 않으면 다시 만들 수 있나요?": { en: "Can I redo it if I don't like it?", ja: "気に入らなければ作り直せますか？", zh: "不满意可以重做吗？" },
  "네. **「2번 확인하며」**를 고르면 캐릭터 시트와 이야기 단계에서 확인하고 다시 만들 수 있어요. 이야기를 다시 지을 때는 크레딧이 더 들지 않고, 원하는 방향을 메모로 적어 줄 수 있습니다. 완성한 뒤에는 편집실에서 마음에 안 드는 컷만 골라 다시 그릴 수 있어요(**한 컷 3크레딧**).": {
    en: "Yes. If you choose **\"Check twice along the way\"**, you can review and redo the character sheet and story steps. Rewriting the story costs no extra credits, and you can leave a note about the direction you want. After it's finished, you can pick just the panels you don't like and redraw them in the editing room (**3 credits per panel**).",
    ja: "はい。**「2回確認しながら」**を選ぶと、キャラクターシートと物語の段階で確認して作り直せます。物語を作り直すときは追加のクレジットはかからず、希望の方向をメモで伝えられます。完成後は、気に入らないコマだけを選んで編集室で描き直せます（**1コマ3クレジット**）。",
    zh: "可以。选择**「确认两次」**的话，能在角色设定表和故事阶段确认后重做。重写故事不会额外扣积分，还能留言说明想要的方向。完成之后，可以在编辑室只挑不满意的那一格重画（**每格 3 积分**）。",
  },

  "크레딧은 무엇인가요?": { en: "What are credits?", ja: "クレジットとは何ですか？", zh: "积分是什么？" },
  "생성 기능을 쓸 때 줄어드는 서비스 안의 이용 단위예요. 가입할 때 **12크레딧**을 드리고, 로그인해 있으면 **날마다 20크레딧**이 자동으로 채워집니다. 웹툰 한 편은 **12크레딧**(가장 높은 화질 「너울」은 18), 컷 하나 다시 그리기는 **3크레딧**, 캐릭터 만들기는 **하루 3번까지 무료**이고 그 뒤로는 2크레딧이에요. 만들다가 취소하거나 도중에 실패하면 **자동으로 돌려드립니다.**": {
    en: "They're the in-service unit that gets used up when you generate something. You get **12 credits** when you sign up, and while you're signed in, **20 credits are topped up every day** automatically. One episode costs **12 credits** (18 for the highest quality, \"Swell\"), redrawing one panel costs **3 credits**, and making a character is **free up to 3 times a day**, then 2 credits. If you cancel partway or it fails, the credits are **refunded automatically.**",
    ja: "生成機能を使うと減る、サービス内の利用単位です。登録時に**12クレジット**を差し上げ、ログインしていれば**毎日20クレジット**が自動で補充されます。ウェブトゥーン1話は**12クレジット**（最高画質「うねり」は18）、コマ1枚の描き直しは**3クレジット**、キャラクター作成は**1日3回まで無料**で、それ以降は2クレジットです。途中でキャンセルしたり失敗した場合は**自動で返却されます。**",
    zh: "这是使用生成功能时会消耗的服务内单位。注册时赠送**12 积分**，登录状态下**每天自动补充 20 积分**。一话漫画是**12 积分**（最高画质「涌浪」为 18），重画一格是**3 积分**，创建角色**每天前 3 次免费**，之后每次 2 积分。中途取消或失败会**自动退还。**",
  },

  "돈을 내야 하나요?": { en: "Do I have to pay?", ja: "お金はかかりますか？", zh: "需要付费吗？" },
  "아니요. **지금은 모든 기능이 무료**이고, 결제 수단을 넣는 곳도 없어요. 크레딧을 돈으로 사는 기능은 아직 준비 중입니다. 유료 기능이 생기면 미리 공지하고 안내해 드릴게요.": {
    en: "No. **Everything is free right now**, and there's nowhere to enter payment details. Buying credits isn't available yet. If paid features arrive, we'll announce them in advance.",
    ja: "いいえ。**現在はすべての機能が無料**で、支払い情報を入力する場所もありません。クレジットの購入はまだ準備中です。有料機能を始める際は、事前にお知らせします。",
    zh: "不需要。**目前所有功能都免费**，也没有输入付款信息的地方。积分购买功能还在准备中。如果将来推出付费功能，我们会提前公告。",
  },

  "로그인하지 않아도 만들 수 있나요?": { en: "Can I make one without signing in?", ja: "ログインしなくても作れますか？", zh: "不登录也能做吗？" },
  "네. **로그인하지 않아도 웹툰을 끝까지 만들어 볼 수 있어요**(같은 인터넷 연결에서 하루 2편까지). 다만 로그인하지 않고 만든 작품은 **그 브라우저에만 묶여 있어서**, 저장 기록을 지우거나 다른 기기에서 열면 다시 찾지 못할 수 있어요. 만들기 전에 로그인하시거나, 진행 화면에서 알림 받을 이메일을 남겨 두시길 권해요.": {
    en: "Yes. **You can make a webtoon all the way through without signing in** (up to 2 episodes a day per internet connection). But a work made without signing in is **tied to that one browser**, so if you clear your browser data or open the site on another device, you may not find it again. We recommend signing in first, or leaving an email address on the progress screen.",
    ja: "はい。**ログインしなくてもウェブトゥーンを最後まで作れます**（同じインターネット回線から1日2話まで）。ただしログインせずに作った作品は**そのブラウザだけに紐づく**ため、保存データを消したり別の端末で開いたりすると、二度と見つけられないことがあります。作る前にログインするか、進行画面で通知用メールアドレスを残すことをおすすめします。",
    zh: "可以。**不登录也能把漫画做到完成**（同一网络连接每天最多 2 话）。不过没登录做的作品**只绑定在那个浏览器上**，清除浏览器数据或换设备打开，可能就再也找不到了。建议先登录，或在进度画面留下通知邮箱。",
  },

  "만든 웹툰은 어디에서 볼 수 있나요?": { en: "Where can I find the webtoons I've made?", ja: "作ったウェブトゥーンはどこで見られますか？", zh: "做好的漫画在哪里看？" },
  "로그인했다면 **마이페이지**에 그동안 만든 작품이 모두 모여요. 로그인하지 않았다면 같은 브라우저로 다시 들어왔을 때 목록에 보이고, 알림 메일을 받았다면 그 메일의 링크로 언제든 열 수 있습니다.": {
    en: "If you're signed in, everything you've made is gathered on **My Page**. If you're not, you'll see them in the list when you come back in the same browser, and if you got a notification email, its link opens the work any time.",
    ja: "ログインしていれば、これまで作った作品が**マイページ**にすべて集まります。ログインしていない場合は、同じブラウザで再訪したときに一覧に表示され、通知メールを受け取っていれば、そのリンクからいつでも開けます。",
    zh: "已登录的话，做过的作品都会汇集在**我的页面**。没登录的话，用同一个浏览器再进来就能在列表里看到；如果收到过通知邮件，随时可以用邮件里的链接打开。",
  },

  "내가 만든 웹툰은 다른 사람에게도 공개되나요?": { en: "Will my webtoon be visible to other people?", ja: "作ったウェブトゥーンは他の人にも公開されますか？", zh: "我做的漫画会公开给别人看吗？" },
  "완성한 웹툰은 **기본적으로 둘러보기에 공개돼요.** 로그인한 계정으로 만들었다면 마이페이지에서 언제든 **비공개로 바꿀 수 있습니다.** 로그인 없이 만든 작품은 공개 설정을 바꿀 수 없으니, 남에게 보이면 곤란한 사진이나 이야기는 로그인한 뒤에 만들어 주세요.": {
    en: "Finished webtoons are **public in Browse by default.** If you made it while signed in, you can **switch it to private** any time from My Page. Works made without signing in can't have their visibility changed, so if the photo or story is something you'd rather not show, please sign in first.",
    ja: "完成したウェブトゥーンは**基本的に「見てまわる」に公開されます。**ログインしたアカウントで作った場合は、マイページでいつでも**非公開に変えられます。**ログインせずに作った作品は公開設定を変えられないので、人に見られたくない写真や物語は、ログインしてから作ってください。",
    zh: "做好的漫画**默认会公开在浏览里。**如果是登录账号做的，随时可以在我的页面**改成不公开。**没登录做的作品无法更改公开设置，所以不方便给别人看的照片或故事，请先登录再做。",
  },

  "업로드한 사진은 어떻게 처리되나요?": { en: "What happens to the photo I upload?", ja: "アップロードした写真はどう扱われますか？", zh: "上传的照片会怎么处理？" },
  "올린 사진은 **캐릭터를 만드는 데만** 써요. 캐릭터를 그리려면 사진을 AI 모델에 보내야 해서, 이 과정에서 사진이 국외(미국)의 AI 사업자에게 전송됩니다. 사진 원본은 다시 만들기·문제 확인을 위해 서버에 보관하고 있고, **지워 달라고 요청하시면 지워 드려요.** 자세한 내용은 개인정보처리방침에 적어 두었습니다.": {
    en: "Your photo is used **only to make the character.** Drawing the character means sending the photo to an AI model, so it is transferred to an AI provider abroad (in the United States) during that step. We keep the original on our servers so you can regenerate and so we can investigate problems, and **we'll delete it if you ask us to.** The details are in our Privacy Policy.",
    ja: "アップロードした写真は**キャラクターを作るためだけに**使います。キャラクターを描くには写真をAIモデルに送る必要があるため、この過程で写真が国外（米国）のAI事業者に転送されます。元の写真は作り直しや不具合の確認のためサーバーに保管しており、**削除をご依頼いただければ削除します。**詳しくはプライバシーポリシーに記載しています。",
    zh: "上传的照片**只用于制作角色。**画角色需要把照片发送给 AI 模型，因此这个过程中照片会传输到境外（美国）的 AI 服务商。原始照片会保存在服务器上，用于重新生成和排查问题，**如果你要求删除，我们会删除。**详情写在隐私政策里。",
  },

  "올린 사진이 AI 학습에 쓰이나요?": { en: "Is my photo used to train AI?", ja: "アップロードした写真はAIの学習に使われますか？", zh: "上传的照片会被用来训练 AI 吗？" },
  "아니요. **올린 사진과 만들어진 결과물을 AI 모델 학습에 쓰지 않습니다.** 다른 사람의 생성에 참고 이미지로 재사용하지도 않고, 학습용 데이터로 팔거나 넘기지도 않아요. 바깥 AI 사업자에게 보낼 때도 **학습에 쓰지 않는 조건의 방식**으로만 보냅니다.": {
    en: "No. **We do not use your photos or the results to train AI models.** We don't reuse them as reference images for anyone else's generation, and we don't sell or hand them over as training data. When we send them to outside AI providers, we only use **methods that don't allow training on them.**",
    ja: "いいえ。**アップロードされた写真や生成物をAIモデルの学習には使いません。**他の人の生成の参考画像として再利用することもなく、学習用データとして販売・提供することもありません。外部のAI事業者に送る際も、**学習に使われない条件の方式**でのみ送信します。",
    zh: "不会。**我们不会用你的照片和生成结果来训练 AI 模型。**也不会把它们当作参考图用在别人的生成上，更不会作为训练数据出售或提供。发送给外部 AI 服务商时，也只采用**不允许用于训练的方式。**",
  },

  "어떤 사진을 올려야 하나요?": { en: "What kind of photo should I upload?", ja: "どんな写真をアップロードすればいいですか？", zh: "该上传什么样的照片？" },
  "**본인이 찍었거나 쓸 권리가 있는 사진**만 올려 주세요. 다른 사람의 얼굴이 담긴 사진은 그 사람의 동의가 필요하고, 만화·애니메이션·게임 캐릭터처럼 남의 저작물은 올리시면 안 돼요. 올린 사진으로 생긴 문제의 책임은 올린 분에게 있습니다.": {
    en: "Please upload **only photos you took yourself or have the right to use.** A photo with someone else's face needs that person's consent, and you must not upload other people's works such as characters from comics, anime or games. Responsibility for problems caused by an uploaded photo lies with the person who uploaded it.",
    ja: "**ご自身で撮影した、または使用する権利のある写真**だけをアップロードしてください。他の人の顔が写った写真にはその方の同意が必要で、漫画・アニメ・ゲームのキャラクターなど他人の著作物はアップロードできません。アップロードした写真によって生じた問題の責任は、アップロードした方にあります。",
    zh: "请只上传**自己拍摄的、或有权使用的照片。**含有他人面孔的照片需要本人同意，漫画、动画、游戏角色等他人作品不可上传。因上传照片引起的问题由上传者承担责任。",
  },

  "만든 웹툰을 판매하거나 광고에 써도 되나요?": { en: "Can I sell my webtoon or use it in ads?", ja: "作ったウェブトゥーンを販売したり広告に使ったりできますか？", zh: "做好的漫画可以拿去卖或用在广告上吗？" },
  "개인적으로 간직하거나 **SNS에 올려 공유하는 것은 자유롭게** 하셔도 됩니다. 다만 결과물을 **팔거나, 굿즈·이모티콘 상품으로 만들거나, 광고에 쓰는 것은 지금은 허용되지 않아요.** AI가 만든 그림은 저작권이 인정되지 않을 수 있다는 점도 함께 알아 두시면 좋아요.": {
    en: "Keeping it for yourself and **sharing it on social media is entirely fine.** But **selling the results, turning them into merchandise or sticker products, or using them in advertising is not allowed for now.** It's also worth knowing that AI-generated images may not be recognized as copyrightable.",
    ja: "個人で楽しんだり、**SNSに投稿して共有したりするのは自由**です。ただし、生成物を**販売したり、グッズ・スタンプ商品にしたり、広告に使ったりすることは現在は認められていません。**AIが作った絵には著作権が認められない場合があることも、あわせて知っておいてください。",
    zh: "自己保存或**发到社交平台分享都可以。**但**出售成品、制作成周边或表情包商品、用于广告，目前都不允许。**另外也请了解：AI 生成的图像有可能不被认定享有著作权。",
  },

  "몇 살부터 쓸 수 있나요?": { en: "What's the minimum age?", ja: "何歳から使えますか？", zh: "多大年龄可以用？" },
  "**만 14세 이상**부터 이용하실 수 있어요. 회원 가입을 할 때 확인받습니다.": {
    en: "You need to be **14 or older**. We confirm this when you sign up.",
    ja: "**満14歳以上**からご利用いただけます。会員登録の際に確認します。",
    zh: "需要**满 14 周岁**才能使用。注册会员时会进行确认。",
  },

  /* ---- 푸터 ---- */
  "LORE 웹툰 스튜디오": { en: "LORE Webtoon Studio", ja: "LORE ウェブトゥーンスタジオ", zh: "LORE 漫画工作室" },
  "이용약관": { en: "Terms of Service", ja: "利用規約", zh: "服务条款" },
  "개인정보처리방침": { en: "Privacy Policy", ja: "プライバシーポリシー", zh: "隐私政策" },
  "1:1 문의": { en: "Contact us", ja: "お問い合わせ", zh: "联系我们" },
  "과학기술정보통신부": { en: "Ministry of Science and ICT", ja: "科学技術情報通信部", zh: "科学技术信息通信部" },
  "정보통신기획평가원(IITP)": { en: "Institute of Information & Communications Technology Planning & Evaluation (IITP)", ja: "情報通信企画評価院(IITP)", zh: "信息通信规划评价院(IITP)" },

  /* ---- 입구 ---- */
  "LORE에서 무엇을 해볼까요?": { en: "What would you like to do on LORE?", ja: "LOREで何をしてみますか？", zh: "在 LORE 想做点什么？" },
  "LORE에서\n무엇을 해볼까요?": { en: "What would you like\nto do on LORE?", ja: "LOREで\n何をしてみますか？", zh: "在 LORE\n想做点什么？" },
  "둘 다 마지막엔 웹툰이에요.": { en: "Both end in a webtoon.", ja: "どちらも最後はウェブトゥーンになります。", zh: "两条路最后都会做成漫画。" },
  "둘 다 마지막엔 웹툰이에요. 만든 캐릭터는 저장돼서 다음엔 바로 웹툰으로 갑니다.": {
    en: "Both end in a webtoon. Characters you make are saved, so next time you can go straight to the webtoon.",
    ja: "どちらも最後はウェブトゥーンになります。作ったキャラクターは保存され、次は直接ウェブトゥーンに進めます。",
    zh: "两条路最后都会做成漫画。做好的角色会保存下来，下次可以直接做漫画。",
  },
  "캐릭터 만들어보기": { en: "Try a character", ja: "キャラクターを作ってみる", zh: "试做一个角色" },
  "내 캐릭터로 바로 웹툰을 만들고 싶어요": { en: "I want to make a webtoon with my character right away", ja: "自分のキャラクターですぐウェブトゥーンを作りたい", zh: "我想用自己的角色直接做漫画" },
  "내가 가진 캐릭터, 최애, 이미지, 설정으로 바로 웹툰을 만들어요.": {
    en: "Start from a character you already have: a fave, an image, or a bit of lore.",
    ja: "手持ちのキャラクター、推し、画像、設定から、すぐにウェブトゥーンを作ります。",
    zh: "用你已有的角色、本命、图片或设定，直接做成漫画。",
  },
  "이걸로 만들기": { en: "Make with this", ja: "これで作る", zh: "用这个开始" },
  "캐릭터를 만들어보고 싶어요": { en: "I want to try making a character", ja: "キャラクターを作ってみたい", zh: "我想试着做一个角色" },
  "사진이든 설명이든, 아무것도 없어도 돼요. 뭐든 웹툰 속 캐릭터가 돼요.": {
    en: "A photo, a description, or nothing at all. Anything becomes a webtoon character.",
    ja: "写真でも説明でも、何もなくても大丈夫。何でもウェブトゥーンのキャラクターになります。",
    zh: "照片、文字描述，或者什么都不放都可以。什么都能变成漫画角色。",
  },
  "내 사진도, 최애도, 강아지도, 아무것도 없어도 돼요. 뭐든 넣으면 웹툰 속 캐릭터가 돼요.": {
    en: "Your photo, your fave, your dog, or nothing at all. Put in anything and it becomes a webtoon character.",
    ja: "自分の写真でも、推しでも、犬でも、何もなくても大丈夫。何を入れてもウェブトゥーンのキャラクターになります。",
    zh: "你的照片、本命、小狗，或者什么都不放都可以。放进什么，都会变成漫画角色。",
  },
  "처음으로": { en: "Back to start", ja: "はじめに戻る", zh: "回到首页" },

  /* ---- 편집실 목업 ---- */
  "편집실": { en: "Editing room", ja: "編集室", zh: "编辑室" },
  "컷과 말풍선이 들어간 페이지": { en: "A page with panels and speech bubbles", ja: "コマと吹き出しの入ったページ", zh: "带分镜和对话气泡的页面" },
  "캐릭터 시트": { en: "Character sheet", ja: "キャラクターシート", zh: "角色设定表" },
  "캐릭터 시트 · {who}": { en: "Character sheet · {who}", ja: "キャラクターシート・{who}", zh: "角色设定表 · {who}" },
  "한 컷만 다시 그리기": { en: "Redraw just one panel", ja: "1コマだけ描き直す", zh: "只重画一格" },
  "이 컷만 다시 그리기": { en: "Redraw this panel only", ja: "このコマだけ描き直す", zh: "只重画这一格" },
  "진심이 아니었던 적은 단 한 번도 없어. 다만 그 진심이, 매번 다른 사람을 향했을 뿐이지.": {
    en: "I've never once been insincere. It's just that my sincerity pointed at someone different every time.",
    ja: "本気じゃなかったことは一度もない。ただその本気が、毎回別の人に向いていただけさ。",
    zh: "我从来没有一次不是真心的。只是那份真心，每次都指向了不同的人。",
  },
  "몽이": { en: "Mongi", ja: "モンイ", zh: "蒙伊" },
  "세이엘": { en: "Seiel", ja: "セイエル", zh: "赛伊尔" },
};

registerDict(dict);
