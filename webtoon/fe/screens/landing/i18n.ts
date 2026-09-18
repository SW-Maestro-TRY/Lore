/* 첫 화면·입구·편집실 목업의 사전. 원문(한국어)이 키다 — lib/i18n.tsx 참고.
 * FAQ 답의 `**…**` 는 화면에서 굵게 그린다(언어마다 어순이 달라 조각내지 않는다). */
import { registerDict, type Dict } from "../../lib/i18n";

const dict: Dict = {
  /* ---- 히어로 ---- */
  "만들던 웹툰": { en: "Webtoon in progress", ja: "作りかけのウェブトゥーン", zh: "正在制作的漫画" },
  "{done} / {total}장": { en: "{done} / {total} pages", ja: "{done} / {total}枚", zh: "{done} / {total} 页" },
  "AI 웹툰 제작 서비스 LORE": { en: "LORE, the AI webtoon studio", ja: "AIウェブトゥーン制作サービス LORE", zh: "AI 漫画创作服务 LORE" },
  "캐릭터 · 사진 · 그림 · 좋아하는 사람": { en: "A character, a photo, a drawing, someone you love", ja: "キャラクター・写真・絵・好きな人", zh: "角色 · 照片 · 画作 · 喜欢的人" },
  "캐릭터 · 사진 · 그림 · 그 무엇이든!": { en: "A character, a photo, a drawing, anything at all!", ja: "キャラクター・写真・絵・なんでも！", zh: "角色 · 照片 · 画作 · 什么都可以！" },
  "지금 시작하기": { en: "Get started", ja: "はじめる", zh: "立即开始" },
  "오늘 무료 {n}편": { en: "{n} free today", ja: "本日無料 {n}話", zh: "今日免费 {n} 话" },
  "오늘 무료 소진 · 로그인하면 이어서": { en: "Today's free episodes are used up · sign in to keep going", ja: "本日の無料分は終了・ログインすると続けられます", zh: "今日免费次数已用完 · 登录后可继续" },
  "한 편 {cost}크레딧 · 보유 {balance}C": { en: "{cost} credits per episode · you have {balance}C", ja: "1話 {cost}クレジット・残高 {balance}C", zh: "每话 {cost} 积分 · 余额 {balance}C" },

  /* ---- 예시 작품 띠 ---- */
  "이 서비스로 만들어진 편": { en: "Episodes made here", ja: "このサービスで作られた作品", zh: "用这个服务做出的作品" },
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
  "몽이는 이 로맨스 웹툰에서, 강아지인 채로 악역 영애예요": {
    en: "In this romance webtoon, Mongi is the villainess, and still a puppy",
    ja: "モンイはこのロマンスウェブトゥーンで、子犬のまま悪役令嬢です",
    zh: "在这部爱情漫画里，蒙伊保持小狗的样子，成了反派千金",
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

  /* ---- FAQ ---- */
  "자주 묻는 것": { en: "FAQ", ja: "よくある質問", zh: "常见问题" },
  "이건 뭐하는 서비스인가요?": { en: "What is this service?", ja: "これは何をするサービスですか？", zh: "这是个什么服务？" },
  "사진 한 장과 이름만 주시면 **웹툰 한 화가 통째로** 나오는 스튜디오예요. 캐릭터를 그리고, 이야기를 짓고, 표지부터 마지막 장까지 순서대로 그려 드립니다. 그림을 못 그려도, 이야기를 안 써 봤어도 괜찮아요.": {
    en: "It's a studio where one photo and a name become **a whole webtoon episode**. We draw the character, write the story, and draw every page in order, from the cover to the last one. You don't need to draw, and you don't need to have written a story before.",
    ja: "写真1枚と名前だけで、**ウェブトゥーン1話がまるごと**できるスタジオです。キャラクターを描き、物語を作り、表紙から最後のページまで順番に描きます。絵が描けなくても、物語を書いたことがなくても大丈夫です。",
    zh: "只要一张照片和一个名字，就能做出**完整的一话漫画**的工作室。我们会画角色、写故事，从封面到最后一页按顺序画好。不会画画、没写过故事也没关系。",
  },
  "AI SW 마에스트로란 무엇인가요?": { en: "What is AI SW Maestro?", ja: "AI SWマエストロとは何ですか？", zh: "AI SW Maestro 是什么？" },
  "과학기술정보통신부가 주관하고 정보통신기획평가원(IITP)이 운영하는 AI 소프트웨어 인재 양성 과정이에요. LORE는 이 과정에서 실제로 기획하고 만들고 있는 프로젝트입니다.": {
    en: "It's an AI software talent program hosted by Korea's Ministry of Science and ICT and run by the Institute of Information & Communications Technology Planning & Evaluation (IITP). LORE is a project actually being planned and built in that program.",
    ja: "韓国の科学技術情報通信部が主催し、情報通信企画評価院(IITP)が運営するAIソフトウェア人材育成プログラムです。LOREはこのプログラムの中で実際に企画・開発しているプロジェクトです。",
    zh: "这是由韩国科学技术信息通信部主办、信息通信规划评价院(IITP)运营的 AI 软件人才培养项目。LORE 就是在这个项目里实际策划和开发的作品。",
  },
  "마음에 안 들면 다시 만들 수 있나요?": { en: "Can I redo it if I don't like it?", ja: "気に入らなければ作り直せますか？", zh: "不满意可以重做吗？" },
  "네, 가능해요. **「2번 확인하며」**를 고르면 캐릭터 시트와 이야기 단계에서 확인하면서 다시 만들 수 있고, 완성한 뒤에도 편집실에서 컷 단위로 다시 그릴 수 있어요.": {
    en: "Yes. If you choose **\"Check twice along the way\"**, you can review and redo the character sheet and story steps, and even after it's finished you can redraw individual panels in the editing room.",
    ja: "はい、できます。**「2回確認しながら」**を選ぶと、キャラクターシートと物語の段階で確認しながら作り直せますし、完成した後も編集室でコマ単位で描き直せます。",
    zh: "可以。选择**「确认两次」**的话，可以在角色设定表和故事阶段边确认边重做；完成之后也能在编辑室按格重画。",
  },
  "만들다가 창을 닫거나 다른 걸 하면 어떻게 되나요?": { en: "What if I close the window or do something else midway?", ja: "作っている途中でウィンドウを閉じたり、他のことをしたらどうなりますか？", zh: "做到一半关掉窗口或去做别的事，会怎样？" },
  "괜찮아요. 진행 상황은 서버가 갖고 있어서, 나중에 같은 작업으로 다시 들어오면 하던 데서 그대로 이어집니다.": {
    en: "That's fine. The server keeps your progress, so when you come back to the same job later, it picks up right where you left off.",
    ja: "大丈夫です。進行状況はサーバーが持っているので、後で同じ作業に戻ってくると、途中からそのまま続きます。",
    zh: "没关系。进度保存在服务器上，之后回到同一个任务，会从原来的地方接着做。",
  },
  "얼마나 걸리나요?": { en: "How long does it take?", ja: "どのくらいかかりますか？", zh: "需要多长时间？" },
  "한 편에 보통 10분 안팎 걸려요. 그림체나 이야기 길이에 따라 조금씩 달라질 수 있어요.": {
    en: "Usually around 10 minutes per episode. It can vary a little with the art style and the length of the story.",
    ja: "1話につき、だいたい10分前後です。画風や物語の長さによって多少変わることがあります。",
    zh: "一话通常 10 分钟左右。会因画风和故事长度略有不同。",
  },
  "그림을 하나도 못 그려도 쓸 수 있나요?": { en: "Can I use it if I can't draw at all?", ja: "絵がまったく描けなくても使えますか？", zh: "完全不会画画也能用吗？" },
  "네, 그림을 못 그리셔도 괜찮아요. 사진 한 장과 이름만 있으면 버튼 하나로 끝까지 완성됩니다.": {
    en: "Yes, you don't need to draw. With one photo and a name, a single button takes it all the way to a finished episode.",
    ja: "はい、絵が描けなくても大丈夫です。写真1枚と名前があれば、ボタンひとつで最後まで完成します。",
    zh: "可以，不会画画也没关系。只要一张照片和一个名字，按一个按钮就能做到完成。",
  },
  "로그인 안 해도 만들 수 있나요?": { en: "Can I make one without signing in?", ja: "ログインしなくても作れますか？", zh: "不登录也能做吗？" },
  "네, 로그인 없이도 게스트로 끝까지 만들 수 있어요. 나중에 로그인해서 저장하면 마이페이지에서 다시 볼 수 있어요.": {
    en: "Yes, you can make one all the way through as a guest. If you sign in and save it later, you can see it again on My Page.",
    ja: "はい、ログインなしでもゲストとして最後まで作れます。後でログインして保存すれば、マイページでまた見られます。",
    zh: "可以，不登录也能以访客身份做到完成。之后登录并保存，就能在我的页面再次查看。",
  },
  "여러 캐릭터로 여러 편 만들 수 있나요?": { en: "Can I make several episodes with several characters?", ja: "複数のキャラクターで複数の話を作れますか？", zh: "可以用多个角色做多话吗？" },
  "네, 얼마든지요. 로그인하면 그동안 만든 작품이 마이페이지에 모두 모여요.": {
    en: "Yes, as many as you like. When you sign in, everything you've made is gathered on My Page.",
    ja: "はい、いくらでも。ログインすると、これまで作った作品がマイページにすべて集まります。",
    zh: "可以，想做多少都行。登录后，做过的所有作品都会汇集在我的页面。",
  },
  "완성한 웹툰은 다른 사람도 볼 수 있나요?": { en: "Can other people see my finished webtoon?", ja: "完成したウェブトゥーンは他の人も見られますか？", zh: "做好的漫画别人也能看吗？" },
  "기본적으로 **둘러보기**에 공개돼요. 마이페이지에서 언제든 비공개로 바꿀 수 있어요.": {
    en: "By default it's public in **Browse**. You can make it private any time from My Page.",
    ja: "基本的に**見てまわる**に公開されます。マイページでいつでも非公開に変えられます。",
    zh: "默认会公开在**浏览**里。随时可以在我的页面改成不公开。",
  },
  "올린 사진은 어떻게 되나요?": { en: "What happens to the photo I upload?", ja: "アップロードした写真はどうなりますか？", zh: "上传的照片会怎么处理？" },
  "캐릭터를 만드는 데만 사용하고, 시트가 완성되면 서버에서 바로 삭제해요.": {
    en: "It's used only to make the character, and it's deleted from the server as soon as the sheet is finished.",
    ja: "キャラクターを作るためだけに使い、シートが完成したらすぐサーバーから削除します。",
    zh: "只用于制作角色，设定表完成后会立刻从服务器删除。",
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
