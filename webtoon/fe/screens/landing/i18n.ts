/* 첫 화면·입구·편집실 목업의 사전. 원문(한국어)이 키다 — lib/i18n.tsx 참고.
 * FAQ 답의 `**…**` 는 화면에서 굵게 그린다(언어마다 어순이 달라 조각내지 않는다). */
import { registerDict, type Dict } from "../../lib/i18n";

const dict: Dict = {
  /* ---- 입구 — 만들고 싶은 내용이 있나요?(#548) ---- */
  "무엇을 만들고 싶나요?": { en: "What would you like to make?", ja: "何を作りたいですか？", zh: "想做什么？" },
  "아이디어부터 시작할게요": { en: "Start from an idea", ja: "アイデアから始める", zh: "从灵感开始" },
  "캐릭터를 바탕으로 AI 가 스토리를 만들어드려요.": { en: "AI writes a story from your character.", ja: "キャラクターをもとに AI がストーリーを作ります。", zh: "AI 会根据角色为你创作故事。" },
  "AI 와 함께 만들기": { en: "Make it with AI", ja: "AI と一緒に作る", zh: "和 AI 一起创作" },
  "만들고 싶은 내용이 있어요": { en: "I know what I want to make", ja: "作りたい内容がある", zh: "我已经有想做的内容" },
  "내가 생각한 내용을 바탕으로 장면을 만들고 웹툰으로 완성해요.": { en: "Build scenes from what you have in mind and finish the webtoon.", ja: "考えた内容をもとに場面を作り、ウェブトゥーンに仕上げます。", zh: "根据你的构思生成场景，完成漫画。" },
  "내 내용으로 시작하기": { en: "Start with my own", ja: "自分の内容で始める", zh: "用我的内容开始" },
  "로그인하면 내 내용으로 만들 수 있어요": { en: "Sign in to make it with your own content", ja: "ログインすると自分の内容で作れます", zh: "登录后即可用自己的内容创作" },

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
  "무협": { en: "Wuxia", ja: "武侠", zh: "武侠" },
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
  "캐릭터를 먼저 정해 두니까, 장면이 바뀌어도 내 캐릭터 그대로 이어져요.": {
    en: "The character is settled first, so your character stays the same even when the scene changes.",
    ja: "先にキャラクターを決めておくので、場面が変わっても自分のキャラクターのまま続きます。",
    zh: "先把角色定下来，所以场景再怎么变，也还是你的角色。",
  },
  "머릿속 이야기를, 장면으로 먼저": { en: "The story in your head, as scenes first", ja: "頭の中のストーリーを、まず場面に", zh: "先把脑海里的故事变成场景" },
  "이야기를 적으면 AI가 장면과 대사로 나눠 보여 줘요. 직접 고쳐 본 뒤 웹툰으로 만들 수 있어요.": {
    en: "Write your story and the AI splits it into scenes and lines for you to see. Edit them yourself, then make the webtoon.",
    ja: "ストーリーを書くと、AIが場面とセリフに分けて見せてくれます。自分で直してから、ウェブトゥーンにできます。",
    zh: "写下故事，AI 会把它分成场景和台词展示给你。亲自修改之后，再制作漫画。",
  },
  "마음에 드는 장면만 남기기": { en: "Keep only the scenes you like", ja: "気に入った場面だけを残す", zh: "只留下满意的场景" },
  "아쉬운 장면이 있다면 그 장면만 다시 그려요. 처음부터 전부 다시 만들 필요 없어요.": {
    en: "If a scene falls short, redraw just that scene. No need to start over from scratch.",
    ja: "惜しい場面があれば、その場面だけ描き直します。最初から全部やり直す必要はありません。",
    zh: "如果有不满意的场景，只重新绘制那个场景。不用从头全部重来。",
  },

  "장면 {n} / {total}": { en: "Scene {n} / {total}", ja: "場面 {n} / {total}", zh: "场景 {n} / {total}" },
  "장소와 상황": { en: "Place & situation", ja: "場所と状況", zh: "地点与情境" },
  "벌어지는 일": { en: "What happens", ja: "起きること", zh: "发生的事" },
  "행동과 표정": { en: "Actions & expressions", ja: "行動と表情", zh: "动作与表情" },
  "이 장면 다시 그리기": { en: "Redraw this scene", ja: "この場面を描き直す", zh: "重新绘制这个场景" },
  "장면 하나를 다시 그리기": { en: "Redrawing a single scene", ja: "場面を一つ描き直す", zh: "重新绘制单个场景" },
  "{n}번째 장 다시 그리기": { en: "Redraw page {n}", ja: "{n}ページ目を描き直す", zh: "重新绘制第 {n} 页" },
  "다시 그리기": { en: "Redraw", ja: "描き直す", zh: "重新绘制" },
  "무엇이 마음에 안 드나요?": { en: "What don't you like?", ja: "どこが気に入りませんか？", zh: "哪里不满意？" },
  "캐릭터가 이상해요": { en: "The character looks off", ja: "キャラクターがおかしい", zh: "角色不对劲" },
  "표정이 안 맞아요": { en: "The expression is wrong", ja: "表情が合っていない", zh: "表情不对" },
  "포즈가 어색해요": { en: "The pose is awkward", ja: "ポーズが不自然", zh: "姿势别扭" },
  "배경이 이상해요": { en: "The background looks off", ja: "背景がおかしい", zh: "背景不对劲" },
  "더 하고 싶은 말": { en: "Anything else", ja: "ほかに伝えたいこと", zh: "还想补充的话" },
  "무대 문 앞이라 조금 더 긴장한 표정으로": { en: "A bit more nervous, since she's at the stage door", ja: "ステージの扉の前なので、もう少し緊張した表情で", zh: "在舞台门前，表情再紧张一点" },
  "말풍선 없이 그림만": { en: "Picture only, no speech bubbles", ja: "吹き出しなしで絵だけ", zh: "只要画面，不要对话气泡" },
  "취소": { en: "Cancel", ja: "キャンセル", zh: "取消" },
  "표지": { en: "Cover", ja: "表紙", zh: "封面" },
  "대기실 거울 앞, 무대 직전": { en: "In front of the waiting-room mirror, just before the stage", ja: "控え室の鏡の前、ステージ直前", zh: "休息室镜子前，登台之前" },
  "마지막 무대임을 떠올리며 임시 센터 계약서를 확인한다": { en: "Remembering it's her last stage, she checks the temporary-center contract", ja: "最後のステージだと思い出しながら、臨時センター契約書を確認する", zh: "想起这是最后一场舞台，查看临时 C 位合约" },
  "휴대폰 속 D-day를 읽고, 입꼬리가 처진다": { en: "She reads the D-day on her phone, the corners of her mouth drooping", ja: "スマホのD-dayを読み、口角が下がる", zh: "读着手机里的 D-day，嘴角垂了下来" },

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
  "네. **「2번 확인하며」**를 고르면 캐릭터 시트와 이야기 단계에서 확인하고 다시 만들 수 있어요. 이야기를 다시 지을 때는 크레딧이 더 들지 않고, 원하는 방향을 메모로 적어 줄 수 있습니다. 완성한 뒤에는 편집실에서 마음에 안 드는 장면만 골라 다시 그릴 수 있어요(**한 장면 3크레딧, 「너울」은 4크레딧**).": {
    en: "Yes. If you choose **\"Check twice along the way\"**, you can review and redo the character sheet and story steps. Rewriting the story costs no extra credits, and you can leave a note about the direction you want. After it's finished, you can pick just the scenes you don't like and redraw them in the editing room (**3 credits per scene, 4 for Swell**).",
    ja: "はい。**「2回確認しながら」**を選ぶと、キャラクターシートと物語の段階で確認して作り直せます。物語を作り直すときは追加のクレジットはかからず、希望の方向をメモで伝えられます。完成後は、気に入らない場面だけを選んで編集室で描き直せます（**1場面3クレジット、「うねり」は4**）。",
    zh: "可以。选择**「确认两次」**的话，能在角色设定表和故事阶段确认后重做。重写故事不会额外扣积分，还能留言说明想要的方向。完成之后，可以在编辑室只挑不满意的场景重新绘制（**每个场景 3 积分，「涌浪」为 4**）。",
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

  /* ---- 자주 묻는 질문 본문 — GalleryHome 의 faqs (2026-10-26 개편 · 출석 #863) ---- */
  "LORE는 무료로 이용할 수 있나요?": { en: "Can I use LORE for free?", ja: "LOREは無料で使えますか？", zh: "LORE 可以免费使用吗？" },
  "네. 가입하면 웹툰 한 편을 만들 수 있는 15크레딧을 드리고, 출석하면 매일 2크레딧(4일째 5 · 7일째 10크레딧)을 더 드려요. 일주일 다 오면 25크레딧이에요. 가입하지 않아도 하루 2편까지 만들어 볼 수 있어요.": {
    en: "Yes. When you sign up you get 15 credits, enough for one webtoon episode, and checking in gives you 2 more credits every day (5 on day 4 and 10 on day 7). Check in all week and that's 25 credits. Even without signing up, you can make up to 2 episodes a day.",
    ja: "はい。登録するとウェブトゥーン1話を作れる15クレジットを差し上げ、出席すると毎日2クレジット（4日目は5・7日目は10クレジット）をさらに差し上げます。1週間すべて出席すると25クレジットです。登録しなくても1日2話まで作ってみられます。",
    zh: "可以。注册即送可制作一话漫画的 15 积分，每天签到还能再领 2 积分（第 4 天 5 积分 · 第 7 天 10 积分）。一周全部签到共 25 积分。不注册也能每天最多制作 2 话。",
  },

  "웹툰 한 편을 만드는 데 얼마나 걸리나요?": { en: "How long does it take to make one episode?", ja: "ウェブトゥーン1話を作るのにどれくらいかかりますか？", zh: "制作一话漫画需要多久？" },
  "고른 화질에 따라 약 6~15분 걸려요(물결 6분 · 파도 8분 · 너울 15분). 앞에 기다리는 작업이 있으면 조금 더 걸리고, 진행 화면에서 지금 단계와 대기 순번을 볼 수 있어요.": {
    en: "About 6–15 minutes depending on the quality you choose (Ripple 6 min · Wave 8 min · Swell 15 min). It takes a little longer if other jobs are waiting ahead of yours, and the progress screen shows the current step and your place in line.",
    ja: "選んだ画質によって約6〜15分かかります（さざ波6分・波8分・うねり15分）。先に待っている作業があると少し長くなり、進行画面で今の段階と待ち順を確認できます。",
    zh: "根据所选画质大约需要 6~15 分钟（涟漪 6 分钟 · 波浪 8 分钟 · 涌浪 15 分钟）。如果前面有排队的任务会稍久一些，可以在进度画面查看当前步骤和排队顺序。",
  },

  "웹툰을 만들다가 나가도 괜찮나요?": { en: "Is it OK to leave while it's being made?", ja: "作っている途中で離れても大丈夫ですか？", zh: "制作途中离开也没关系吗？" },
  "괜찮아요. 만드는 일은 서버에서 계속되니까 창을 닫거나 다른 화면으로 가도 멈추지 않아요. 로그인했거나 알림 받을 이메일을 남겨 두면 다 됐을 때 메일로 알려 드려요.": {
    en: "Yes. The work keeps going on our server, so it won't stop if you close the window or go to another screen. If you're signed in or leave an email for notifications, we'll email you when it's done.",
    ja: "大丈夫です。制作はサーバーで続くので、ウィンドウを閉じたり別の画面に移っても止まりません。ログインしているか、通知用のメールアドレスを残しておけば、完成したときにメールでお知らせします。",
    zh: "没关系。制作在服务器上继续进行，关掉窗口或切到别的画面也不会停止。如果已登录或留下接收通知的邮箱，完成时会发邮件告诉你。",
  },

  "결과가 마음에 들지 않으면 어떻게 하나요?": { en: "What if I don't like the result?", ja: "結果が気に入らないときはどうすればいいですか？", zh: "对结果不满意怎么办？" },
  "완성한 뒤 편집실에서 마음에 안 드는 장면만 골라 다시 그릴 수 있어요. 무엇이 아쉬운지 고르고 하고 싶은 말을 덧붙이면 돼요. 한 장면에 3크레딧이고, 너울로 만든 작품은 6크레딧이에요. 다시 그릴 때 화질을 파도나 물결로 낮추면 3크레딧이에요.": {
    en: "Once it's finished, you can pick just the scenes you don't like and redraw them in the editing room. Choose what's off and add anything you want to say. It's 3 credits per scene, or 6 credits for episodes made in Swell. If you lower the quality to Wave or Ripple when redrawing, it's 3 credits.",
    ja: "完成後、編集室で気に入らない場面だけを選んで描き直せます。何が気になるかを選び、伝えたいことを書き添えてください。1場面3クレジットで、うねりで作った作品は6クレジットです。描き直すときに画質を波やさざ波に下げると3クレジットです。",
    zh: "完成后可以在编辑室只挑不满意的场景重新绘制。选出哪里不满意，再补充想说的话就行。每个场景 3 积分，用涌浪制作的作品为 6 积分。重画时把画质降到波浪或涟漪则为 3 积分。",
  },

  "크레딧은 언제 차감되나요?": { en: "When are credits deducted?", ja: "クレジットはいつ差し引かれますか？", zh: "积分什么时候扣除？" },
  "웹툰 만들기를 시작할 때 차감돼요. 한 편에 15크레딧(너울은 30크레딧), 장면 다시 그리기는 3크레딧(너울은 6크레딧)이고, 8장을 넘는 장은 한 장에 2크레딧(너울은 6크레딧)이에요. 무료로 받은 크레딧부터 먼저 쓰여요.": {
    en: "They're deducted when you start making a webtoon. One episode is 15 credits (30 for Swell), redrawing a scene is 3 credits (6 for Swell), and each page beyond 8 is 2 credits (6 for Swell). Free credits are used first.",
    ja: "ウェブトゥーンの制作を始めたときに差し引かれます。1話15クレジット（うねりは30クレジット）、場面の描き直しは3クレジット（うねりは6クレジット）、8枚を超えるページは1枚2クレジット（うねりは6クレジット）です。無料でもらったクレジットから先に使われます。",
    zh: "开始制作漫画时扣除。一话 15 积分（涌浪为 30 积分），重画一个场景 3 积分（涌浪为 6 积分），超过 8 页的部分每页 2 积分（涌浪为 6 积分）。会优先使用免费获得的积分。",
  },

  "생성에 실패하면 크레딧은 어떻게 되나요?": { en: "What happens to my credits if generation fails?", ja: "生成に失敗したらクレジットはどうなりますか？", zh: "生成失败的话积分怎么办？" },
  "만들다가 취소하거나 오류로 실패하면 차감된 크레딧이 자동으로 돌아와요. 다만 정상적으로 완성된 뒤 결과가 마음에 들지 않는 경우는 돌려드리지 않아요.": {
    en: "If you cancel partway or it fails because of an error, the deducted credits come back automatically. However, we don't refund credits when it finished normally and you just don't like the result.",
    ja: "途中でキャンセルしたり、エラーで失敗した場合は、差し引かれたクレジットが自動で戻ります。ただし、正常に完成したあとで結果が気に入らない場合は返却しません。",
    zh: "中途取消或因错误失败时，扣除的积分会自动退还。但正常完成后只是对结果不满意的情况，不予退还。",
  },

  "구매한 크레딧은 환불할 수 있나요?": { en: "Can I get a refund for credits I bought?", ja: "購入したクレジットは返金できますか？", zh: "购买的积分可以退款吗？" },
  "결제하고 7일 안에 하나도 쓰지 않았다면 전액 환불돼요. 그 밖에는 남은 유료 크레딧만큼에서 수수료 10%를 뺀 금액을 돌려드려요. 이미 쓴 크레딧과 무료로 받은 크레딧은 환불되지 않아요.": {
    en: "If you haven't used any of them within 7 days of paying, you get a full refund. Otherwise, we refund the value of your remaining paid credits minus a 10% fee. Credits already used and free credits can't be refunded.",
    ja: "決済から7日以内に1つも使っていなければ全額返金されます。それ以外の場合は、残っている有料クレジット分から手数料10%を引いた金額をお返しします。すでに使ったクレジットと無料でもらったクレジットは返金されません。",
    zh: "付款后 7 天内一点都没用的话可全额退款。其他情况下，按剩余付费积分扣除 10% 手续费后退还。已使用的积分和免费获得的积分不予退款。",
  },

  "구매한 크레딧에 유효기간이 있나요?": { en: "Do purchased credits expire?", ja: "購入したクレジットに有効期限はありますか？", zh: "购买的积分有有效期吗？" },
  "유료 크레딧은 결제한 날부터 5년 동안 쓸 수 있어요. 무료로 받은 크레딧은 따로 유효기간이 없어요. 탈퇴하면 남은 크레딧은 모두 사라지니, 남은 유료 크레딧이 있으면 먼저 환불을 신청해 주세요.": {
    en: "Paid credits can be used for 5 years from the date of payment. Free credits don't have a separate expiry date. All remaining credits disappear when you delete your account, so if you have paid credits left, please request a refund first.",
    ja: "有料クレジットは決済日から5年間使えます。無料でもらったクレジットには特に有効期限はありません。退会すると残りのクレジットはすべて消えるので、有料クレジットが残っている場合は先に返金を申請してください。",
    zh: "付费积分自付款之日起 5 年内可用。免费获得的积分没有单独的有效期。注销账号后剩余积分会全部清空，如果还有付费积分，请先申请退款。",
  },

  "마이페이지의 「내 웹툰」에 그동안 만든 작품이 모두 모여요. 알림 메일을 받았다면 메일 속 링크로도 열 수 있어요. 가입하지 않고 만든 작품은 만든 브라우저에서만 다시 찾을 수 있으니, 방문 기록을 지우기 전에 가입하거나 알림 이메일을 남겨 두세요.": {
    en: "Everything you've made is collected in \"My webtoons\" on My page. If you got a notification email, you can also open it from the link in the email. Works made without signing up can only be found again in the browser you made them in, so sign up or leave a notification email before clearing your browsing data.",
    ja: "マイページの「マイウェブトゥーン」にこれまで作った作品がすべて集まります。通知メールを受け取っていれば、メール内のリンクからも開けます。登録せずに作った作品は作ったブラウザでしか見つけられないので、閲覧履歴を消す前に登録するか、通知用のメールアドレスを残しておいてください。",
    zh: "你做过的所有作品都会集中在我的页面的「我的漫画」里。如果收到了通知邮件，也可以通过邮件里的链接打开。未注册时做的作品只能在制作时用的浏览器里找到，清除浏览记录前请先注册或留下通知邮箱。",
  },

  "다른 사람도 내 작품을 볼 수 있나요?": { en: "Can other people see my work?", ja: "ほかの人も私の作品を見られますか？", zh: "别人也能看到我的作品吗？" },
  "완성한 작품은 기본으로 둘러보기에 공개돼요. 로그인해서 만든 작품은 마이페이지에서 언제든 비공개로 바꿀 수 있어요. 로그인 없이 만든 작품은 공개 설정을 바꿀 수 없으니, 남에게 보이면 곤란한 내용은 로그인한 뒤 만들어 주세요.": {
    en: "Finished works are public in Browse by default. Works you made while signed in can be made private anytime from My page. The visibility of works made without signing in can't be changed, so please sign in before making anything you wouldn't want others to see.",
    ja: "完成した作品は、基本的に「見て回る」に公開されます。ログインして作った作品は、マイページからいつでも非公開に変更できます。ログインせずに作った作品は公開設定を変更できないので、人に見られると困る内容はログインしてから作ってください。",
    zh: "完成的作品默认会公开在「浏览」中。登录后制作的作品可以随时在我的页面改为不公开。未登录制作的作品无法更改公开设置，不想让别人看到的内容请登录后再制作。",
  },

  "만든 웹툰을 SNS에 올리거나 상업적으로 이용해도 되나요?": { en: "Can I post my webtoon on social media or use it commercially?", ja: "作ったウェブトゥーンをSNSに載せたり、商用利用してもいいですか？", zh: "做好的漫画可以发到社交平台或用于商业用途吗？" },
  "개인 SNS · 커뮤니티 · 메신저에 공유하는 건 자유예요. 판매하거나 굿즈 · 광고 같은 영리 목적으로 쓰려면 미리 동의를 받아야 해요. 자세한 범위는 이용약관 제11조에 있어요.": {
    en: "You're free to share it on your personal social media, communities, and messengers. To sell it or use it for profit, such as merchandise or ads, you need our consent in advance. The details are in Article 11 of the Terms of Service.",
    ja: "個人のSNS・コミュニティ・メッセンジャーで共有するのは自由です。販売やグッズ・広告などの営利目的で使う場合は、事前に同意を得る必要があります。詳しい範囲は利用規約第11条にあります。",
    zh: "在个人社交平台 · 社区 · 聊天软件上分享是自由的。如果要销售或用于周边 · 广告等营利目的，需要事先取得同意。具体范围请见用户协议第 11 条。",
  },

  "이용하다 문제가 생기면 어디로 문의하나요?": { en: "Where do I go if I run into a problem?", ja: "利用中に問題が起きたらどこに問い合わせればいいですか？", zh: "使用中遇到问题该去哪里咨询？" },
  "마이페이지의 「1:1 문의하기」에서 이메일로 보내 주시거나, lore@lorecomic.com 으로 바로 메일을 주세요. 화면을 찍은 사진을 같이 보내 주시면 더 빨리 확인할 수 있어요.": {
    en: "Send us an email from \"Contact us\" on My page, or write directly to lore@lorecomic.com. If you attach a screenshot, we can look into it faster.",
    ja: "マイページの「1:1お問い合わせ」からメールで送るか、lore@lorecomic.com に直接メールしてください。画面のスクリーンショットを一緒に送っていただくと、より早く確認できます。",
    zh: "请在我的页面的「1:1 咨询」里通过邮件发送，或直接发邮件到 lore@lorecomic.com。附上屏幕截图的话，我们能更快确认。",
  },

  /* ---- 푸터 ---- */
  /* ---- 푸터 네 칸 · 자주 묻는 질문 분류 (2026-10-26) ---- */
  "내 캐릭터가 주인공인 웹툰을 만드는 곳": { en: "Where your character becomes the lead of a webtoon", ja: "自分のキャラクターが主人公のウェブトゥーンを作る場所", zh: "让你的角色成为漫画主角的地方" },
  "서비스": { en: "Service", ja: "サービス", zh: "服务" },
  "둘러보기": { en: "Browse", ja: "見て回る", zh: "浏览" },
  "내 웹툰": { en: "My webtoons", ja: "マイウェブトゥーン", zh: "我的漫画" },
  "자주 묻는 질문": { en: "FAQ", ja: "よくある質問", zh: "常见问题" },
  "법적 고지": { en: "Legal", ja: "法的情報", zh: "法律信息" },
  "고객지원": { en: "Support", ja: "サポート", zh: "客户支持" },
  "대표": { en: "CEO", ja: "代表", zh: "负责人" },
  "주소": { en: "Address", ja: "住所", zh: "地址" },
  "전화": { en: "Phone", ja: "電話", zh: "电话" },
  "이메일": { en: "Email", ja: "メール", zh: "邮箱" },
  "통신판매업": { en: "Mail-order reg.", ja: "通信販売業", zh: "通信销售业" },
  "© 2026 LORE · TRY팀. All rights reserved.": { en: "© 2026 LORE · Team TRY. All rights reserved.", ja: "© 2026 LORE · TRYチーム. All rights reserved.", zh: "© 2026 LORE · TRY 团队. All rights reserved." },
  "LORE는 AI 창작 도구를 제공해요. 올린 사진·그림과 만든 작품의 저작권 관련 책임은 이용자에게 있어요.": {
    en: "LORE provides AI creative tools. Users are responsible for copyright matters concerning the photos and drawings they upload and the works they make.",
    ja: "LOREはAI創作ツールを提供します。アップロードした写真・絵と作った作品の著作権に関する責任は利用者にあります。",
    zh: "LORE 提供 AI 创作工具。上传的照片、图片以及所做作品的著作权相关责任由用户承担。",
  },
  "주요": { en: "Top", ja: "主な質問", zh: "主要" },
  "이용 안내": { en: "Using LORE", ja: "ご利用案内", zh: "使用指南" },
  "크레딧·결제": { en: "Credits & payment", ja: "クレジット・決済", zh: "积分与支付" },
  "작품 관리": { en: "Your works", ja: "作品の管理", zh: "作品管理" },

  /* ---- 캐릭터 일관성 — 시트 머리줄 · 구역 이름 ---- */
  "전신 · 다섯 방향": { en: "Full body · 5 angles", ja: "全身・5方向", zh: "全身 · 五个角度" },
  "얼굴 각도": { en: "Face angles", ja: "顔の角度", zh: "脸部角度" },
  "표정": { en: "Expressions", ja: "表情", zh: "表情" },
  "디테일": { en: "Details", ja: "ディテール", zh: "细节" },

  "LORE 웹툰 스튜디오": { en: "LORE Webtoon Studio", ja: "LORE ウェブトゥーンスタジオ", zh: "LORE 漫画工作室" },
  "이용약관": { en: "Terms of Service", ja: "利用規約", zh: "服务条款" },
  "개인정보처리방침": { en: "Privacy Policy", ja: "プライバシーポリシー", zh: "隐私政策" },
  "1:1 문의": { en: "Contact us", ja: "お問い合わせ", zh: "联系我们" },
  "운영": { en: "Operated by", ja: "運営", zh: "运营" },
  "문의": { en: "Contact", ja: "連絡先", zh: "联系" },
  "AI SW MAESTRO 17기 프로젝트": { en: "An AI SW MAESTRO (17th) project", ja: "AI SW MAESTRO 17期プロジェクト", zh: "AI SW MAESTRO 第17期项目" },
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
  "캐릭터 만들기": { en: "Create character", ja: "キャラクターを作る", zh: "创建角色" },
  "캐릭터와 스토리로 웹툰 1화를 생성해보아요": {
    en: "Turn a character and a story into episode 1 of your webtoon",
    ja: "キャラクターとストーリーでウェブトゥーン第1話を作ってみましょう",
    zh: "用角色和故事生成漫画第 1 话",
  },
  "내가 ○○에 들어간다면? 재미있는 캐릭터를 만들어보아요": {
    en: "What if I stepped into ○○? Make a fun character",
    ja: "もし私が○○に入ったら？楽しいキャラクターを作ってみましょう",
    zh: "如果我走进○○会怎样？来做一个有趣的角色吧",
  },
  "바로 웹툰을 만들고 싶어요": { en: "I want to make a webtoon right away", ja: "すぐウェブトゥーンを作りたい", zh: "我想直接做漫画" },
  "남은 무료 {n}": { en: "{n} free left", ja: "無料残り {n}", zh: "剩余免费 {n}" },
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
  "진심이 아니었던 적은 단 한 번도 없어. 다만 그 진심이, 매번 다른 사람을 향했을 뿐이지.": {
    en: "I've never once been insincere. It's just that my sincerity pointed at someone different every time.",
    ja: "本気じゃなかったことは一度もない。ただその本気が、毎回別の人に向いていただけさ。",
    zh: "我从来没有一次不是真心的。只是那份真心，每次都指向了不同的人。",
  },
  "몽이": { en: "Mongi", ja: "モンイ", zh: "蒙伊" },
  "세이엘": { en: "Seiel", ja: "セイエル", zh: "赛伊尔" },
  /* ---- 빠졌던 번역 ---- */
  "AI 웹툰 스튜디오": { en: "AI webtoon studio", ja: "AIウェブトゥーンスタジオ", zh: "AI 漫画工作室" },
  "같은 얼굴,": { en: "Same face,", ja: "同じ顔のまま、", zh: "同一张脸，" },
  "그리기 전에": { en: "Before drawing,", ja: "描く前に", zh: "绘制之前，" },
  "내 캐릭터로": { en: "With my character,", ja: "自分のキャラクターで", zh: "用我的角色" },
  "다른 장르의 웹툰": { en: "Webtoons in other genres", ja: "ほかのジャンルのウェブトゥーン", zh: "其他题材的漫画" },
  "다시 그려요": { en: "get redrawn", ja: "描き直します", zh: "重新绘制" },
  "마지막 컷까지": { en: "to the last panel", ja: "最後のコマまで", zh: "直到最后一格" },
  "선택한 장면": { en: "Selected scene", ja: "選んだ場面", zh: "选中的场景" },
  "아쉬운 장면만": { en: "Only the scenes you don't like", ja: "気に入らない場面だけ", zh: "只把不满意的场景" },
  "원래 컷 보기": { en: "See original panel", ja: "元のコマを見る", zh: "查看原画格" },
  "웹툰 둘러보기": { en: "Browse webtoons", ja: "ウェブトゥーンを見て回る", zh: "浏览漫画" },
  "웹툰 속 캐릭터": { en: "Characters in the webtoons", ja: "ウェブトゥーンのキャラクター", zh: "漫画里的角色" },
  "장면 {n}": { en: "Scene {n}", ja: "場面 {n}", zh: "场景 {n}" },
  "장면과 대사": { en: "Scenes and dialogue", ja: "場面とセリフ", zh: "场景与台词" },
  "장면부터 확인": { en: "check the scenes first", ja: "まず場面を確認", zh: "先确认场景" },
  "캐릭터 일관성": { en: "Character consistency", ja: "キャラクターの一貫性", zh: "角色一致性" },
  "캐릭터를 넣으면, 그 캐릭터가 주인공인 웹툰이 나와요.": { en: "Add a character, and you get a webtoon starring that character.", ja: "キャラクターを入れると、そのキャラクターが主人公のウェブトゥーンができます。", zh: "放入角色，就会生成以该角色为主角的漫画。" },
  "1화를 시작하세요": { en: "start episode 1", ja: "第1話を始めましょう", zh: "开始第 1 话" },
  "당신의 캐릭터로": { en: "With your character,", ja: "あなたのキャラクターで", zh: "用你的角色" },
  "LORE 웹툰": { en: "LORE Webtoon", ja: "LOREウェブトゥーン", zh: "LORE 漫画" },
  "그 캐릭터로 그린 웹툰이 나와요.": { en: "you get a webtoon drawn with that character.", ja: "そのキャラクターで描いたウェブトゥーンができます。", zh: "就会生成用该角色画的漫画。" },
  "그리기 전에 장면을 고칠 수 있어요": { en: "You can edit scenes before drawing", ja: "描く前に場面を直せます", zh: "绘制前可以修改场景" },
  "내 캐릭터가 주인공인": { en: "Starring my character:", ja: "自分のキャラクターが主人公の", zh: "以我的角色为主角的" },
  "사진이나 설정, 내가 만든 캐릭터": { en: "A photo, details, or a character you made", ja: "写真や設定、自分で作ったキャラクター", zh: "照片、设定或自己创作的角色" },
  "이야기 적기": { en: "Write the story", ja: "ストーリーを書く", zh: "写故事" },
  "장면 확인하고 그리기": { en: "Check scenes, then draw", ja: "場面を確認して描く", zh: "确认场景后绘制" },
  "캐릭터 넣기": { en: "Add a character", ja: "キャラクターを入れる", zh: "放入角色" },
  "캐릭터와 하고 싶은 이야기를 넣으면,": { en: "Add a character and the story you want,", ja: "キャラクターと描きたいストーリーを入れると、", zh: "放入角色和想讲的故事，" },
  "하고 싶은 장면이나 줄거리": { en: "Scenes or a plot you have in mind", ja: "描きたい場面やあらすじ", zh: "想要的场景或剧情" },
};

registerDict(dict);
