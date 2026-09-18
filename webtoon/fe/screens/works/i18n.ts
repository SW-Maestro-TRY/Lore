import { registerDict, type Dict } from "../../lib/i18n";

/* 둘러보기 — 화면의 한국어 원문이 키다. 작품 제목·장르·그림체 라벨은 서버 값이라 여기 없다. */
const dict: Dict = {
  "둘러보기": { en: "Browse", ja: "見て回る", zh: "浏览" },
  "다른 사람들의 웹툰": { en: "Webtoons by others", ja: "みんなのウェブトゥーン", zh: "大家的网络漫画" },
  "표지를 누르면 그대로 읽을 수 있어요.": { en: "Tap a cover to start reading.", ja: "表紙を押すとそのまま読めます。", zh: "点击封面即可阅读。" },
  "내 웹툰 만들기": { en: "Make my webtoon", ja: "自分のウェブトゥーンを作る", zh: "制作我的网络漫画" },
  "예시 작품 보기": { en: "Show example works", ja: "サンプル作品を表示", zh: "显示示例作品" },
  "예시 작품도 보기": { en: "Include examples", ja: "サンプル作品も見る", zh: "同时显示示例作品" },
  "전체": { en: "All", ja: "すべて", zh: "全部" },
  "내 작품": { en: "My works", ja: "自分の作品", zh: "我的作品" },
  "최신순": { en: "Newest first", ja: "新しい順", zh: "最新优先" },
  "오래된순": { en: "Oldest first", ja: "古い順", zh: "最早优先" },
  "최신순 — 누르면 오래된순": { en: "Newest first — tap for oldest first", ja: "新しい順 — 押すと古い順", zh: "最新优先 — 点击改为最早优先" },
  "오래된순 — 누르면 최신순": { en: "Oldest first — tap for newest first", ja: "古い順 — 押すと新しい順", zh: "最早优先 — 点击改为最新优先" },
  "목록을 가져오지 못했어요": { en: "Couldn't load the list.", ja: "一覧を取得できませんでした", zh: "无法获取列表" },
  "서버에 닿지 못했어요. 잠시 뒤 다시 시도해 주세요 — 만들어 둔 작품은 그대로 있어요.": {
    en: "We couldn't reach the server. Please try again in a moment — your works are safe.",
    ja: "サーバーに接続できませんでした。しばらくしてからもう一度お試しください — 作った作品はそのまま残っています。",
    zh: "无法连接服务器。请稍后再试 — 已制作的作品都还在。",
  },
  "다시 시도": { en: "Try again", ja: "もう一度", zh: "重试" },
  "홈으로": { en: "Home", ja: "ホームへ", zh: "回到首页" },
  "아직 구경할 웹툰이 없어요": { en: "No webtoons to browse yet", ja: "まだ見られるウェブトゥーンがありません", zh: "还没有可以浏览的网络漫画" },
  "첫 작품이 이 자리에 걸립니다. 캐릭터 하나와 이야기 한 줄이면 10분 안에 한 편이 나와요.": {
    en: "The first work will hang right here. One character and one line of story make an episode in under 10 minutes.",
    ja: "最初の作品がここに並びます。キャラクター一人とストーリー一行があれば、10分以内に一話ができます。",
    zh: "第一部作品会出现在这里。一个角色加一句故事，10 分钟内就能做出一话。",
  },
  "내 캐릭터로 웹툰 만들기": { en: "Make a webtoon with my character", ja: "自分のキャラクターでウェブトゥーンを作る", zh: "用我的角色制作网络漫画" },
  "예시를 빼니 볼 게 없어요": { en: "Nothing to see without the examples", ja: "サンプルを外すと見るものがありません", zh: "去掉示例后就没有内容了" },
  "작품이 없는 게 아니라 「예시 작품도 보기」를 꺼 둔 탓이에요. 실제 작품이 걸리면 이 자리에 먼저 보입니다.": {
    en: "It's not that there are no works — \"Include examples\" is switched off. Real works will show up here first.",
    ja: "作品がないのではなく、「サンプル作品も見る」がオフになっているためです。実際の作品が並ぶと、ここに先に表示されます。",
    zh: "并不是没有作品，而是关闭了「同时显示示例作品」。有真实作品时会优先显示在这里。",
  },
  "예시 작품 다시 보기": { en: "Show examples again", ja: "サンプル作品をもう一度見る", zh: "重新显示示例作品" },
  "바꾸지 못했어요": { en: "Couldn't change it.", ja: "変更できませんでした", zh: "无法更改" },
  "{title} 열기": { en: "Open {title}", ja: "{title} を開く", zh: "打开 {title}" },
  "예시": { en: "Example", ja: "サンプル", zh: "示例" },
  "제목 없음": { en: "Untitled", ja: "タイトルなし", zh: "无标题" },
  "{n}화": { en: "Ep. {n}", ja: "第{n}話", zh: "第 {n} 话" },
  "둘러보기에 공개": { en: "Show in Browse", ja: "見て回るに公開", zh: "在浏览中公开" },
  "공개": { en: "Public", ja: "公開", zh: "公开" },
  "비공개": { en: "Private", ja: "非公開", zh: "不公开" },
  "편집실": { en: "Editor", ja: "編集室", zh: "编辑室" },
};

registerDict(dict);
