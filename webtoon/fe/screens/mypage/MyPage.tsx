"use client";

/* 마이페이지 — 디자인 캔버스의 MyPage.dc.html / MMyPage.dc.html.
 *
 * 왼쪽 줄(계정 · 크레딧 · 메뉴)과 오른쪽(내 웹툰 그리드 + 내 캐릭터 줄)이다.
 * 작품 카드의 공개 스위치는 둘러보기(Works)와 **같은 규칙**을 쓴다 — 서버에
 * 먼저 보내고 실패하면 되돌린다. 두 화면이 달라지면 같은 작품이 화면마다 다른
 * 상태로 보인다.
 *
 * 「충전」·「내역」은 공용 컴포넌트를 그대로 붙였다(#84) — 계정 크레딧이라
 * 도메인마다 따로 만들 것이 아니고, 잔액도 헤더와 같은 곳을 읽어 값이 맞는다.
 *
 * 「1:1 문의하기」·「이용약관」은 예전(리라이트 전) 이 화면이 공용 껍데기
 * (`@common/mypage/MyPage`)를 빌려 쓸 때 그 껍데기가 고정으로 주던 것이다.
 * 리라이트하며 디자인 캔버스에 없어서 빠졌는데, 실제 서비스에 필요한
 * 자리라 여기서 직접 붙였다(2026-09-19, 사용자 지적). */
import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@common/auth/useAuth";
import { creditBalance } from "@common/api/credits";
import CreditCharge from "@common/mypage/CreditCharge";
import CreditHistory from "@common/mypage/CreditHistory";
import { LEGAL_LINKS, CONTACT_CHANNEL } from "@common/links";
import {
  browseRuns, coverUrl, deleteRun, forgetMyRun, listCharacters, myAccountRuns, myActiveJobs, myBrowserRuns, sheetImageUrl, sendWebtoonContact, type WebtoonContactCategory, type NhActiveCard, myLikes, myTrash, readAllowance, recentRuns,
  readNotifySetting, restoreRun, setNotifySetting, setVisibility, withdrawAccount,
  type Allowance, type Character, type NhJob, type RunCard, type TrashCard,
  mySurveyStatus, type SurveyStatus, mySeriesDrafts, type SeriesDraftCard,
} from "../../lib/api";
import { activeJobLabel, activeJobTitle } from "../../lib/jobLabel";
import type { Go } from "../../lib/nav";
import PushOptIn from "../../ui/PushOptIn";
import RunStrip from "../../ui/RunStrip";
import { LangSwitch, registerDict, useT } from "../../lib/i18n";
import { track } from "../../lib/track";
import { IconCheck, IconChevronRight, IconMail, IconUser } from "../../ui/Icons";
import { InstagramMark, KakaoTalkMark, XMark } from "../../ui/BrandMarks";
import { ConfirmDialog, Dialog } from "../../ui/Dialog";
import { louArt } from "../../lib/louArt";
import AdminSurvey from "./AdminSurvey";
import CommunityBell from "../../ui/community/CommunityBell";
import "./MyPage.css";

/** 공용 카카오 채널과 이 서비스의 공식 SNS 계정. */
const CONTACT_LINKS = [
  { key: "instagram", label: "Instagram", href: "https://www.instagram.com/lorecomic_/" },
  { key: "x", label: "X (Twitter)", href: "https://x.com/lorecomic_" },
] as const;
const WEBTOON_SUPPORT_EMAIL = "lore@lorecomic.com";

registerDict({
  "마이페이지": { en: "My page", ja: "マイページ", zh: "我的页面" },
  "{n}화 초안": { en: "Episode {n} draft", ja: "第{n}話の下書き", zh: "第{n}话草稿" },
  "관리자 비공개": { en: "Hidden by admin", ja: "管理者が非公開", zh: "管理员设为不公开" },
  "관리자가 비공개 처리했어요.": { en: "An admin made this private.", ja: "管理者が非公開にしました。", zh: "管理员已设为不公开。" },
  "관리자가 삭제 처리했어요.": { en: "An admin removed this work.", ja: "管理者が削除しました。", zh: "管理员已删除这部作品。" },
  "운영 정책 경고가 있어요.": { en: "This work has a policy warning.", ja: "運営ポリシーの警告があります。", zh: "这部作品有运营政策警告。" },
  "사유: {reason}": { en: "Reason: {reason}", ja: "理由: {reason}", zh: "原因：{reason}" },
  "로그인 안 함": { en: "Not signed in", ja: "未ログイン", zh: "未登录" },
  "크레딧": { en: "Credits", ja: "クレジット", zh: "点数" },
  "한 편 {n} C": { en: "{n} C per episode", ja: "1話 {n} C", zh: "每话 {n} C" },
  "만든 것": { en: "MADE", ja: "作ったもの", zh: "已创作" },
  "재료": { en: "MATERIALS", ja: "素材", zh: "素材" },
  "계정": { en: "ACCOUNT", ja: "アカウント", zh: "账号" },
  "내 웹툰": { en: "My webtoons", ja: "マイウェブトゥーン", zh: "我的漫画" },
  "모두 보기": { en: "See all", ja: "すべて見る", zh: "查看全部" },
  "새 캐릭터 만들기": { en: "New character", ja: "新しいキャラクター", zh: "新建角色" },
  "아직 만든 캐릭터가 없어요": { en: "No characters yet", ja: "まだキャラクターがいません", zh: "还没有角色" },
  "그리는 중": { en: "Drawing", ja: "描画中", zh: "绘制中" },
  "못 그렸어요": { en: "Failed", ja: "描けませんでした", zh: "未能绘制" },
  "이 캐릭터로 웹툰": { en: "Make a webtoon", ja: "このキャラでウェブトゥーン", zh: "用此角色做漫画" },
  "내 캐릭터": { en: "My characters", ja: "マイキャラクター", zh: "我的角色" },
  "로그아웃": { en: "Sign out", ja: "ログアウト", zh: "退出登录" },
  "계정 탈퇴": { en: "Delete account", ja: "退会", zh: "注销账号" },
  "탈퇴하면 바로 로그인할 수 없게 되고, 30일이 지나면 계정·작품·캐릭터·올린 사진이 지워져요. 남은 크레딧도 함께 사라져요. 필요한 작품은 먼저 내려받아 두세요.": {
    en: "You'll be signed out right away. After 30 days your account, webtoons, characters and uploaded photos are deleted, and any remaining credits are gone. Download anything you want to keep first.",
    ja: "退会するとすぐにログインできなくなり、30日後にアカウント・作品・キャラクター・写真が削除されます。残りのクレジットも消えます。必要な作品は先に保存してください。",
    zh: "注销后将立即无法登录，30天后账号、作品、角色和上传的照片会被删除，剩余点数也会一并清除。请先下载需要保留的作品。",
  },
  "탈퇴하기": { en: "Delete", ja: "退会する", zh: "注销" },
  "정말 탈퇴할까요? 되돌릴 수 없어요.": { en: "Really delete your account? This can't be undone.", ja: "本当に退会しますか？元に戻せません。", zh: "确定要注销吗？此操作无法撤销。" },
  "탈퇴": { en: "Delete account", ja: "退会", zh: "注销" },
  "충전한 크레딧이 남아 있어요": { en: "You still have purchased credits", ja: "購入したクレジットが残っています", zh: "你还有已购买的点数" },
  "탈퇴하면 환불받을 수 없어요. 먼저 1:1 문의로 환불을 신청해 주세요.": {
    en: "They can't be refunded after you delete your account. Please request a refund via 1:1 support first.",
    ja: "退会すると返金できません。先に1:1お問い合わせから返金を申請してください。",
    zh: "注销后将无法退款。请先通过1:1咨询申请退款。",
  },
  "환불 신청하기": { en: "Request a refund", ja: "返金を申請する", zh: "申请退款" },
  "취소": { en: "Cancel", ja: "キャンセル", zh: "取消" },
  "지우기": { en: "Delete", ja: "削除", zh: "删除" },
  "휴지통": { en: "Trash", ja: "ゴミ箱", zh: "回收站" },
  "휴지통으로 옮길까요?": { en: "Move to trash?", ja: "ゴミ箱に移しますか？", zh: "移到回收站吗？" },
  "{n}일 안에는 휴지통에서 되살릴 수 있어요.": { en: "You can restore it from the trash within {n} days.", ja: "{n}日以内ならゴミ箱から元に戻せます。", zh: "{n} 天内可以从回收站恢复。" },
  "휴지통이 비어 있어요.": { en: "The trash is empty.", ja: "ゴミ箱は空です。", zh: "回收站是空的。" },
  "닫기": { en: "Close", ja: "閉じる", zh: "关闭" },
  "지운 웹툰은 {n}일 동안 여기 있다가 영구 삭제돼요.": { en: "Deleted webtoons stay here for {n} days, then are removed for good.", ja: "削除した作品は{n}日間ここに残り、その後完全に削除されます。", zh: "删除的漫画会在这里保留 {n} 天，之后永久删除。" },
  "{n}일 뒤 영구 삭제": { en: "Deleted for good in {n} days", ja: "{n}日後に完全削除", zh: "{n} 天后永久删除" },
  "오늘 영구 삭제": { en: "Deleted for good today", ja: "本日完全削除", zh: "今天永久删除" },
  "되살리기": { en: "Restore", ja: "元に戻す", zh: "恢复" },
  "되살리지 못했습니다": { en: "Couldn't restore", ja: "元に戻せませんでした", zh: "恢复失败" },
  "지우지 못했습니다": { en: "Couldn't delete", ja: "削除できませんでした", zh: "删除失败" },
  "탈퇴하지 못했어요. 잠시 뒤 다시 시도해 주세요.": { en: "Couldn't delete the account. Please try again shortly.", ja: "退会できませんでした。しばらくしてからもう一度お試しください。", zh: "注销失败，请稍后再试。" },
  "언어": { en: "Language", ja: "言語", zh: "语言" },
  "둘러보기": { en: "Browse", ja: "見てまわる", zh: "浏览" },
  "새 웹툰 만들기": { en: "New webtoon", ja: "新しいウェブトゥーン", zh: "新建漫画" },
  "최근 본 웹툰": { en: "Recently read", ja: "最近読んだ作品", zh: "最近看过" },
  "찜한 웹툰": { en: "Saved webtoons", ja: "お気に入りの作品", zh: "收藏的漫画" },
  "아직 찜한 웹툰이 없어요. 둘러보기에서 하트를 눌러 보세요.": { en: "Nothing saved yet. Tap a heart in Browse.", ja: "まだお気に入りがありません。見て回るでハートを押してみてください。", zh: "还没有收藏。去浏览里点个爱心吧。" },
  "캐릭터 탭으로": { en: "Go to characters", ja: "キャラクタータブへ", zh: "前往角色页" },
  "{n}화": { en: "EP.{n}", ja: "第{n}話", zh: "第{n}话" },
  "공개": { en: "Public", ja: "公開", zh: "公开" },
  "비공개": { en: "Private", ja: "非公開", zh: "私密" },
  "둘러보기에 공개": { en: "Show in Browse", ja: "見てまわるに公開", zh: "在浏览中公开" },
  "편집실": { en: "Editor", ja: "編集室", zh: "编辑室" },
  "바꾸지 못했습니다": { en: "Couldn't change it", ja: "変更できませんでした", zh: "无法更改" },
  "만들기": { en: "Create", ja: "作る", zh: "制作" },
  "아직 만든 웹툰이 없어요": { en: "No webtoons yet", ja: "まだ作品がありません", zh: "还没有作品" },
  "제목 없음": { en: "Untitled", ja: "無題", zh: "无标题" },
  "충전": { en: "Top up", ja: "チャージ", zh: "充值" },
  "내역": { en: "History", ja: "履歴", zh: "记录" },
  "1:1 문의하기": { en: "Contact us", ja: "1:1お問い合わせ", zh: "1:1 咨询" },
  "무엇을 도와드릴까요?": { en: "How can we help?", ja: "どのようなお手伝いが必要ですか？", zh: "有什么可以帮您？" },
  "LORE 이용 중 궁금한 점이 있으신가요?": { en: "Questions about using LORE?", ja: "LOREの利用についてご質問はありますか？", zh: "使用 LORE 时有疑问吗？" },
  "카카오톡 문의": { en: "Contact on KakaoTalk", ja: "KakaoTalkでお問い合わせ", zh: "通过 KakaoTalk 联系" },
  "채팅으로 편하게 문의하기": { en: "Chat with us", ja: "チャットで気軽にお問い合わせ", zh: "通过聊天轻松咨询" },
  "이메일 문의": { en: "Email inquiry", ja: "メールでお問い合わせ", zh: "邮件咨询" },
  "문의 이메일": { en: "Contact email", ja: "お問い合わせメール", zh: "联系邮箱" },
  "오류 제보 · 계정·크레딧 · 기타 문의": { en: "Report a problem, account and credits, or anything else", ja: "不具合・アカウント・その他のお問い合わせ", zh: "问题反馈、账号与点数或其他咨询" },
  "LORE의 소식도 만나보세요": { en: "Follow LORE", ja: "LOREの最新情報", zh: "关注 LORE 动态" },
  "문의 유형": { en: "Topic", ja: "お問い合わせの種類", zh: "咨询类型" },
  "웹툰 제작": { en: "Webtoon creation", ja: "ウェブトゥーン制作", zh: "漫画制作" },
  "계정·크레딧": { en: "Account and credits", ja: "アカウント・クレジット", zh: "账号与点数" },
  "오류 제보": { en: "Report a problem", ja: "不具合の報告", zh: "问题反馈" },
  "기타 문의": { en: "Something else", ja: "その他", zh: "其他" },
  "답변 받을 이메일": { en: "Email for our reply", ja: "返信先メールアドレス", zh: "接收回复的邮箱" },
  "문의 내용을 적어 주세요": { en: "Write your message", ja: "お問い合わせ内容をご記入ください", zh: "请填写咨询内容" },
  "예: 크레딧을 충전했는데 반영되지 않아요. 결제한 시간과 화면에 나온 내용을 적어 주세요.": { en: "Example: My credits haven't appeared after a top-up. Please include when you paid and what the screen shows.", ja: "例：チャージしたクレジットが反映されません。決済した時刻と画面に表示された内容をご記入ください。", zh: "例如：充值后点数没有到账。请填写付款时间和页面显示的内容。" },
  "문의 내용은 5자 이상 적어 주세요.": { en: "Please write at least 5 characters.", ja: "5文字以上でご記入ください。", zh: "请至少输入 5 个字符。" },
  "첨부 파일": { en: "Attachments", ja: "添付ファイル", zh: "附件" },
  "이미지 또는 PDF · 최대 3개, 파일당 1MB": { en: "Images or PDF · up to 3 files, 1MB each", ja: "画像またはPDF・最大3件、1件1MB", zh: "图片或 PDF · 最多 3 个，每个 1MB" },
  "파일을 선택해 주세요": { en: "Choose files", ja: "ファイルを選択", zh: "选择文件" },
  "첨부 파일은 최대 3개까지 올릴 수 있어요.": { en: "You can attach up to 3 files.", ja: "添付ファイルは最大3件までです。", zh: "最多可上传 3 个附件。" },
  "파일 하나는 1MB 이하로 첨부해 주세요.": { en: "Each file must be 1MB or smaller.", ja: "ファイルは1件1MB以下にしてください。", zh: "单个文件不能超过 1MB。" },
  "첨부 파일 전체 용량은 3MB 이하로 올려 주세요.": { en: "Attachments must total 3MB or less.", ja: "添付ファイルの合計は3MB以下にしてください。", zh: "附件总大小不能超过 3MB。" },
  "PNG, JPG, WEBP 이미지와 PDF 파일만 첨부할 수 있어요.": { en: "Only PNG, JPG, WEBP images and PDF files are allowed.", ja: "PNG、JPG、WEBP画像とPDFのみ添付できます。", zh: "仅支持 PNG、JPG、WEBP 图片和 PDF 文件。" },
  "삭제": { en: "Remove", ja: "削除", zh: "删除" },
  "개인정보 수집 및 이용 동의 (필수)": { en: "Personal information consent (required)", ja: "個人情報の収集・利用への同意（必須）", zh: "个人信息收集与使用同意（必填）" },
  "수집 항목: 이메일 주소, 문의 내용, 첨부 파일 · 이용 목적: 문의 확인 및 답변 · 보유 기간: 문의 해결 후 1년": { en: "Items: email address, message, attachments · Purpose: review and reply · Retention: 1 year after resolution", ja: "項目：メールアドレス、お問い合わせ内容、添付ファイル・目的：確認と返信・保管期間：解決後1年間", zh: "收集项目：邮箱、咨询内容、附件 · 用途：确认并回复 · 保存期限：问题解决后 1 年" },
  "위 내용에 동의합니다. (필수)": { en: "I agree to the above. (Required)", ja: "上記に同意します。（必須）", zh: "我同意以上内容。（必填）" },
  "개인정보 수집 및 이용에 동의해 주세요.": { en: "Please agree to the collection and use of personal information.", ja: "個人情報の収集・利用に同意してください。", zh: "请同意收集和使用个人信息。" },
  "자세히 보기": { en: "Learn more", ja: "詳しく見る", zh: "了解详情" },
  "문의 보내기": { en: "Send inquiry", ja: "問い合わせを送信", zh: "发送咨询" },
  "보내는 중…": { en: "Sending…", ja: "送信中…", zh: "发送中…" },
  "문의가 전달됐어요. 적어 주신 이메일로 답변드릴게요.": { en: "Your inquiry was sent. We'll reply to the email you provided.", ja: "お問い合わせを送信しました。ご記入のメールアドレスに返信します。", zh: "咨询已发送。我们会回复到您填写的邮箱。" },
  "보내지 못했어요. 잠시 뒤 다시 시도해 주세요.": { en: "Couldn't send your message. Please try again shortly.", ja: "送信できませんでした。しばらくしてからもう一度お試しください。", zh: "发送失败，请稍后重试。" },
  "문의 내용은 3,000자까지 적을 수 있어요.": { en: "Your message can be up to 3,000 characters.", ja: "お問い合わせ内容は3,000文字までです。", zh: "咨询内容最多可输入 3,000 个字符。" },
  "파일 선택": { en: "Choose files", ja: "ファイルを選択", zh: "选择文件" },
  "{n}개 첨부됨": { en: "{n} attached", ja: "{n}件添付済み", zh: "已附加 {n} 个" },
  "첨부한 파일 없음": { en: "No files attached", ja: "添付ファイルなし", zh: "没有附件" },
  "문의로 돌아가기": { en: "Back to contact options", ja: "お問い合わせ方法に戻る", zh: "返回联系选项" },
  "카카오톡": { en: "KakaoTalk", ja: "カカオトーク", zh: "KakaoTalk" },
  "인스타그램": { en: "Instagram", ja: "インスタグラム", zh: "Instagram" },
  "이용약관": { en: "Terms of use", ja: "利用規約", zh: "使用条款" },
  "목록을 가져오지 못했어요": { en: "Couldn't load the list", ja: "一覧を読み込めませんでした", zh: "无法加载列表" },
  "서버가 떠 있는지 확인해 주세요": { en: "Please check that the server is running", ja: "サーバーが起動しているか確認してください", zh: "请确认服务器是否已启动" },
  "내 캐릭터로 웹툰 만들기": { en: "Make a webtoon with my character", ja: "マイキャラクターでウェブトゥーンを作る", zh: "用我的角色制作漫画" },
  "설정": { en: "Settings", ja: "設定", zh: "设置" },
  "웹툰이 다 만들어지면 이메일로 알림": {
    en: "Email me when a webtoon is done", ja: "ウェブトゥーンが完成したらメールで通知", zh: "漫画完成时用邮件通知我",
  },
  "부탁하신 웹툰이 다 만들어지면 계정 이메일로 알려 드려요. 게스트로 만들 때 직접 적은 주소는 이 설정과 상관없이 그대로 가요.": {
    en: "We'll email your account address when the webtoon you asked for is ready. An address you type in as a guest still goes through, regardless of this setting.",
    ja: "お願いいただいたウェブトゥーンが完成したら、アカウントのメールアドレスにお知らせします。ゲストとして入力したアドレスは、この設定に関係なくそのまま送られます。",
    zh: "你要的漫画做好后，我们会发邮件到你的账号邮箱。以访客身份填写的地址不受此设置影响，仍会照常发送。",
  },
  "바꾸지 못했어요": { en: "Couldn't change it", ja: "変更できませんでした", zh: "无法更改" },
});

export default function MyPage({ go, initialTab }: { go: Go; initialTab?: "settings" }) {
  const t = useT();
  const { user, isAuthenticated, signOut } = useAuth();

  /* 지금은 "내 웹툰"과 "설정" 딱 둘뿐이라 화면을 아예 나누지는 않고
     같은 레일 안에서 본문만 바꾼다 — 나중에 칸이 늘면 그때 공용 탭
     구조(@common/mypage/MyPage 의 Section)로 옮겨도 된다. */
  const [tab, setTab] = useState<"works" | "chars" | "settings">(initialTab === "settings" ? "settings" : "works");
  /* 내 웹툰은 첫 화면에 한 줄만 — 내 캐릭터처럼 「모두 보기」를 눌러야 전부 펼친다 */
  const [allWorks, setAllWorks] = useState(false);
  const WORKS_PREVIEW = 3;
  const [contactOpen, setContactOpen] = useState(false);
  const [contactCompose, setContactCompose] = useState(false);
  const [contactEmail, setContactEmail] = useState("");
  const [contactCategory, setContactCategory] = useState<WebtoonContactCategory>("WEBTOON");
  const [contactMessage, setContactMessage] = useState("");
  const [contactWebsite, setContactWebsite] = useState("");
  const [contactFiles, setContactFiles] = useState<File[]>([]);
  const [contactPrivacyConsent, setContactPrivacyConsent] = useState(false);
  const [contactBusy, setContactBusy] = useState(false);
  const [contactError, setContactError] = useState("");
  const [contactSent, setContactSent] = useState(false);
  const [surveyStatus, setSurveyStatus] = useState<SurveyStatus | null>(null);
  useEffect(() => {
    if (!isAuthenticated) { setSurveyStatus(null); return; }
    mySurveyStatus().then(setSurveyStatus).catch(() => setSurveyStatus(null));
  }, [isAuthenticated]);

  const [runs, setRuns] = useState<RunCard[]>([]);
  const [runsFailed, setRunsFailed] = useState(false);
  const [chars, setChars] = useState<Character[]>([]);
  /* 크레딧 창 — 충전·내역은 공용 것을 그대로 쓴다(계정 크레딧이라 도메인마다
     따로 만들 것이 아니다). 잔액도 헤더와 같은 `/credits/me` 를 읽어야 두
     자리가 같은 값을 말한다. */
  const [credits, setCredits] = useState<number | null>(null);
  /* 남은 유료 크레딧. 탈퇴하면 소멸하고 환불도 못 받으므로(이용약관 13조 7항) 확인 단계에서 먼저 알린다. */
  const [paidCredits, setPaidCredits] = useState(0);
  const [creditModal, setCreditModal] = useState<"charge" | "history" | null>(null);

  const [allowance, setAllowance] = useState<Allowance | null>(null);

  /* 최근 본 것(브라우저)과 찜한 것(계정) — #247. 최근 본 것은 둘러보기 목록에서 카드를 찾아 쓴다. */
  const [recent, setRecent] = useState<RunCard[]>([]);
  const [liked, setLiked] = useState<RunCard[]>([]);
  useEffect(() => {
    const ids = recentRuns();
    if (ids.length === 0) { setRecent([]); return; }
    browseRuns().then((all) => {
      // 연재는 1화 카드 하나로 묶여 온다 — 2화 이후를 봤어도 그 카드로 찾는다.
      const byId = new Map(all.flatMap((r) => [r.run_id, ...Object.values(r.episode_run_ids || {})].map((id) => [id, r] as const)));
      setRecent(ids.map((id) => byId.get(id)).filter((r): r is RunCard => !!r).slice(0, 10));
    }).catch(() => setRecent([]));
  }, []);
  useEffect(() => {
    if (!isAuthenticated) { setLiked([]); return; }
    myLikes().then(setLiked).catch(() => setLiked([]));
  }, [isAuthenticated]);

  /* 내 작품 — 로그인했으면 계정 것과 이 브라우저 것을 합친다(기기를 바꾸면
     둘이 다르다). run_id 로 겹치는 것을 걸러낸다. */
  const loadRuns = useCallback(async () => {
    /* 실패와 "그냥 없음"을 가른다 — 전엔 못 받아도 조용히 빈 목록으로
       떨어져서 "웹툰이 다 사라졌다"처럼 보였다. 둘 다 실패했을 때만
       실패로 본다(한쪽만 죽어도 다른 쪽 결과는 보여줄 수 있다). */
    const results = await Promise.allSettled([
      myBrowserRuns(),
      isAuthenticated ? myAccountRuns() : Promise.resolve([] as RunCard[]),
    ]);
    const bothFailed = results.every((r) => r.status === "rejected");
    setRunsFailed(bothFailed);
    const seen = new Set<string>();
    const merged: RunCard[] = [];
    for (const r of results) {
      if (r.status !== "fulfilled") continue;
      for (const one of r.value) {
        if (seen.has(one.run_id)) continue;
        seen.add(one.run_id);
        merged.push(one);
      }
    }
    setRuns(merged);
  }, [isAuthenticated]);

  useEffect(() => { void loadRuns(); }, [loadRuns]);

  /* 만드는 중(#548) — 아직 안 끝난 작업. 장면 확인처럼 사람이 누를 때까지 멈춰 있는 작업을
     며칠 뒤에도 여기서 찾아 「이어서 만들기」로 돌아간다. */
  const [active, setActive] = useState<NhJob[]>([]);
  const [activeCards, setActiveCards] = useState<Record<string, NhActiveCard>>({});
  useEffect(() => {
    /* 화면만 보는 자리 — 주소에 #mock-drafts 를 붙이면 서버 대신 가짜 셋을 보여 준다(#548). */
    if (typeof window !== "undefined" && window.location.hash === "#mock-drafts") {
      setActive(MOCK_DRAFTS.jobs);
      setActiveCards(Object.fromEntries(MOCK_DRAFTS.cards.map((c) => [c.id, c])));
      return;
    }
    myActiveJobs().then((r) => {
      setActive(r.jobs ?? []);
      setActiveCards(Object.fromEntries((r.cards ?? []).map((c) => [c.id, c])));
    }).catch(() => setActive([]));
  }, []);

  /* 다음 화 초안 — 「다음 편 만들기」에서 써 두고 아직 만들지 않은 것. 만드는 중에 같이 둔다. */
  const [seriesDrafts, setSeriesDrafts] = useState<SeriesDraftCard[]>([]);
  useEffect(() => {
    if (!isAuthenticated) { setSeriesDrafts([]); return; }
    let alive = true;
    mySeriesDrafts().then((r) => { if (alive) setSeriesDrafts(r ?? []); }).catch(() => { if (alive) setSeriesDrafts([]); });
    return () => { alive = false; };
  }, [isAuthenticated]);

  /* 휴지통(#157) — 지운 작품은 영구 삭제 전까지 여기서 되살린다. 지우기가
     로그인한 사람만 되므로 휴지통도 로그인했을 때만 읽는다. */
  const [trash, setTrash] = useState<TrashCard[]>([]);
  const [keepDays, setKeepDays] = useState(30);
  const [trashOpen, setTrashOpen] = useState(false);
  const loadTrash = useCallback(async () => {
    if (!isAuthenticated) { setTrash([]); return; }
    try {
      const got = await myTrash();
      setTrash(got.runs);
      setKeepDays(got.keepDays);
    } catch {
      setTrash([]);
    }
  }, [isAuthenticated]);
  useEffect(() => { void loadTrash(); }, [loadTrash]);
  useEffect(() => {
    listCharacters().then((l) => setChars(l.characters.filter((c) => c.mine))).catch(() => {});
    readAllowance().then(setAllowance).catch(() => {});
    if (isAuthenticated) {
      creditBalance().then((b) => { setCredits(b.balance); setPaidCredits(b.paid ?? 0); }).catch(() => {});
    }
  }, [isAuthenticated]);

  /* 설정 탭 — 웹툰 완성 메일. 안 건드렸으면 서버가 켜진 채로 준다. */
  const [notify, setNotify] = useState<boolean | null>(null);
  const [notifyBusy, setNotifyBusy] = useState(false);
  const [notifyErr, setNotifyErr] = useState("");

  /* 계정 탈퇴(#405) — 두 번 눌러야 된다. 첫 번째는 자리를 확인 문구로 바꾸기만 한다.
     서버가 받으면 토큰이 즉시 폐기되므로 화면도 바로 로그아웃으로 넘기고 첫 화면으로 보낸다.
     signOut 은 서버 호출이 실패해도 화면을 로그아웃으로 넘기는 약속이라 여기서도 안전하다. */
  const [withdrawMode, setWithdrawMode] = useState<"idle" | "confirm">("idle");
  const [withdrawBusy, setWithdrawBusy] = useState(false);
  const [withdrawErr, setWithdrawErr] = useState("");
  const withdraw = async () => {
    setWithdrawBusy(true);
    setWithdrawErr("");
    try {
      await withdrawAccount();
      await signOut();
      go("landing", undefined, { replace: true });
    } catch {
      setWithdrawErr(t("탈퇴하지 못했어요. 잠시 뒤 다시 시도해 주세요."));
      setWithdrawBusy(false);
    }
  };
  useEffect(() => {
    if (!isAuthenticated) return;
    readNotifySetting().then((r) => setNotify(r.on)).catch(() => {});
  }, [isAuthenticated]);

  const toggleNotify = async () => {
    if (notify === null) return;
    const want = !notify;
    setNotify(want);
    setNotifyBusy(true);
    setNotifyErr("");
    try {
      const r = await setNotifySetting(want);
      setNotify(r.on);
    } catch {
      setNotify(!want);
      setNotifyErr(t("바꾸지 못했어요"));
    } finally {
      setNotifyBusy(false);
    }
  };

  
  return (
    <div className="wt-wrap wt-page wt-my">
      <aside className="wt-my-rail">
        <div className="card wt-my-me">
          <span className="wt-my-avatar"><IconUser size={18} /></span>
          <b>{user?.email?.split("@")[0] || t("마이페이지")}</b>
          <span className="dim">{user?.email ?? t("로그인 안 함")}</span>
        </div>

        {isAuthenticated && (
          <div className="card wt-my-credit">
            <span className="dim">{t("크레딧")}</span>
            <b>◈ {(credits ?? allowance?.balance ?? 0).toLocaleString()} <span>C</span></b>
            <div className="wt-my-creditacts">
              <button type="button" className="btn btn-p btn-sm" onClick={() => { track("charge_open", { where: "mypage" }); setCreditModal("charge"); }}>{t("충전")}</button>
              <button type="button" className="btn btn-w btn-sm" onClick={() => setCreditModal("history")}>{t("내역")}</button>
            </div>
          </div>
        )}

        <div className="card rail wt-my-menu">
          <small>{t("만든 것")}</small>
          <button type="button" className={tab === "works" ? "on" : ""} onClick={() => { setTab("works"); setAllWorks(false); }}>
            {t("내 웹툰")} <span className="dim">{runs.length}</span>
          </button>
          <small>{t("재료")}</small>
          <button type="button" className={tab === "chars" ? "on" : ""} onClick={() => setTab("chars")}>
            {t("내 캐릭터")} <span className="dim">{chars.length}</span>
          </button>
          {isAuthenticated && (
            <>
              <small>{t("설정")}</small>
              <button type="button" className={tab === "settings" ? "on" : ""} onClick={() => setTab("settings")}>
                {t("설정")}
              </button>
              {/* 휴지통(#157) — 지운 웹툰은 창으로 따로 연다. */}
              <button type="button" className="wt-my-trashmenu"
                      onClick={() => { void loadTrash(); setTrashOpen(true); track("trash_open", { where: "mypage" }); }}>
                {t("휴지통")} <span className="dim">{trash.length}</span>
              </button>
            </>
          )}
          <small>{t("계정")}</small>
          <button type="button" onClick={() => {
            track("contact_open", { where: "mypage" });
            setContactEmail(user?.email ?? ""); setContactMessage(""); setContactError("");
            setContactCategory("WEBTOON"); setContactCompose(false); setContactSent(false); setContactOpen(true);
          }}>
            {t("1:1 문의하기")}
          </button>
          <button type="button" onClick={() => { track("feedback_open", { where: "mypage" }); go("feedback"); }}>
            {t("피드백 보내기")}
            {surveyStatus && !surveyStatus.done && <span className="dim">+{surveyStatus.reward}C</span>}
          </button>
          {isAuthenticated && (
            <button type="button" onClick={() => void signOut()}>{t("로그아웃")}</button>
          )}
          <a href={LEGAL_LINKS.terms} target="_blank" rel="noopener noreferrer">{t("이용약관")}</a>
          <a href={LEGAL_LINKS.privacy} target="_blank" rel="noopener noreferrer">{t("개인정보처리방침")}</a>
        </div>

        <div className="card wt-my-lang">
          <label>{t("언어")}</label>
          <LangSwitch />
        </div>
      </aside>

      <div className="wt-my-main">
            {contactOpen && (
              <Dialog className="wt-contact-dialog"
                      title={t(contactCompose ? "이메일 문의" : "무엇을 도와드릴까요?")}
                      sub={contactCompose ? undefined : t("LORE 이용 중 궁금한 점이 있으신가요?")}
                      busy={contactBusy} onClose={() => setContactOpen(false)}
                      /* 뒤로 가기는 제목 앞에 — 제목 아래 한 줄을 따로 차지해 어색했다(2026-10-26) */
                      onBack={contactCompose && !contactSent ? () => setContactCompose(false) : undefined}
                      backLabel={t("문의로 돌아가기")}>
                {!contactCompose ? (
                  <div className="wt-my-contact">
                    <a className="wt-contact-row" href={CONTACT_CHANNEL} target="_blank" rel="noopener noreferrer"
                       onClick={() => { track("contact_pick", { where: "mypage", channel: "kakao" }); setContactOpen(false); }}>
                      <span className="wt-contact-rowmark kakao"><KakaoTalkMark size={22} /></span>
                      <span className="wt-contact-rowcopy"><b>{t("카카오톡 문의")}</b><small>{t("채팅으로 편하게 문의하기")}</small></span>
                      <IconChevronRight size={19} />
                    </a>
                    <button type="button" className="wt-contact-row" onClick={() => {
                      track("contact_pick", { where: "mypage", channel: "email" });
                      setContactError(""); setContactCompose(true);
                    }}>
                      <span className="wt-contact-rowmark mail"><IconMail size={21} /></span>
                      <span className="wt-contact-rowcopy"><b>{t("이메일 문의")}</b><small>{WEBTOON_SUPPORT_EMAIL}</small></span>
                      <IconChevronRight size={19} />
                    </button>
                    <p className="wt-contact-social-label">{t("LORE의 소식도 만나보세요")}</p>
                    <div className="wt-contact-socials">
                      {CONTACT_LINKS.map((c) => (
                        <a key={c.key} href={c.href} target="_blank" rel="noopener noreferrer"
                           onClick={() => { track("contact_pick", { where: "mypage", channel: c.key }); setContactOpen(false); }}>
                          {c.key === "instagram" ? <InstagramMark size={23} /> : <XMark size={22} />}
                          <span>{c.label}</span>
                        </a>
                      ))}
                    </div>
                  </div>
                ) : contactSent ? (
                  <div className="wt-contact-success" role="status">
                    <span className="wt-contact-successmark"><IconCheck size={22} /></span>
                    <b>{t("문의가 전달됐어요. 적어 주신 이메일로 답변드릴게요.")}</b>
                    <button type="button" className="btn btn-w" onClick={() => setContactOpen(false)}>{t("닫기")}</button>
                  </div>
                ) : (
                  <form className="wt-contact-form" onSubmit={(event) => {
                    event.preventDefault();
                    if (contactBusy || !contactEmail.trim() || contactMessage.trim().length < 5 || contactMessage.length > 3000 || !contactPrivacyConsent) return;
                    setContactBusy(true); setContactError("");
                    void sendWebtoonContact({ email: contactEmail.trim(), category: contactCategory, message: contactMessage.trim(), privacyConsent: contactPrivacyConsent, files: contactFiles, website: contactWebsite })
                      .then(() => { setContactSent(true); track("contact_sent", { where: "mypage", channel: "email" }); })
                      .catch(() => setContactError(t("보내지 못했어요. 잠시 뒤 다시 시도해 주세요.")))
                      .finally(() => setContactBusy(false));
                  }}>
                    <label htmlFor="wt-contact-email">{t("답변 받을 이메일")}</label>
                    <input id="wt-contact-email" type="email" autoComplete="email" maxLength={254} required
                           value={contactEmail} onChange={(event) => setContactEmail(event.target.value)} />
                    <label htmlFor="wt-contact-category">{t("문의 유형")}</label>
                    <select id="wt-contact-category" value={contactCategory}
                            onChange={(event) => setContactCategory(event.target.value as WebtoonContactCategory)}>
                      <option value="WEBTOON">{t("웹툰 제작")}</option>
                      <option value="ACCOUNT_CREDITS">{t("계정·크레딧")}</option>
                      <option value="ERROR">{t("오류 제보")}</option>
                      <option value="OTHER">{t("기타 문의")}</option>
                    </select>
                    <label htmlFor="wt-contact-message">{t("문의 내용을 적어 주세요")}</label>
                    <textarea id="wt-contact-message" rows={5} maxLength={3000} required
                              value={contactMessage} placeholder={t("예: 크레딧을 충전했는데 반영되지 않아요. 결제한 시간과 화면에 나온 내용을 적어 주세요.")}
                              onChange={(event) => setContactMessage(event.target.value)} />
                    <div className="wt-contact-formfoot"><span>{contactMessage.length.toLocaleString()} / 3,000</span></div>
                    <div className="wt-contact-attachments">
                      <label htmlFor="wt-contact-files">{t("첨부 파일")}</label>
                      <small>{t("이미지 또는 PDF · 최대 3개, 파일당 1MB")}</small>
                      {/* 기본 파일칸은 감추고 단추와 개수만 보인다 — 고른 뒤에도 「선택된 파일 없음」이 남아 헷갈렸다(2026-10-26) */}
                      <span className="wt-contact-pick">
                        <label htmlFor="wt-contact-files" className="wt-contact-pickbtn">{t("파일 선택")}</label>
                        <span>{contactFiles.length ? t("{n}개 첨부됨", { n: contactFiles.length }) : t("첨부한 파일 없음")}</span>
                      </span>
                      <input id="wt-contact-files" className="wt-contact-fileinput" type="file" accept="image/png,image/jpeg,image/webp,application/pdf" multiple
                        onChange={(event) => {
                          const selected = Array.from(event.target.files || []);
                          const next = [...contactFiles, ...selected];
                          let issue = "";
                          if (next.length > 3) issue = t("첨부 파일은 최대 3개까지 올릴 수 있어요.");
                          else if (next.some((file) => file.size > 1024 * 1024)) issue = t("파일 하나는 1MB 이하로 첨부해 주세요.");
                          else if (next.reduce((sum, file) => sum + file.size, 0) > 3 * 1024 * 1024) issue = t("첨부 파일 전체 용량은 3MB 이하로 올려 주세요.");
                          else if (next.some((file) => !["image/png", "image/jpeg", "image/webp", "application/pdf"].includes(file.type))) issue = t("PNG, JPG, WEBP 이미지와 PDF 파일만 첨부할 수 있어요.");
                          setContactError(issue);
                          if (!issue) setContactFiles(next);
                          event.target.value = "";
                        }} />
                      {contactFiles.length > 0 && <ul className="wt-contact-filelist">{contactFiles.map((file, index) => (
                        <li key={`${file.name}-${file.lastModified}-${index}`}><span>{file.name}</span><button type="button" onClick={() => setContactFiles((files) => files.filter((_, i) => i !== index))}>{t("삭제")}</button></li>
                      ))}</ul>}
                    </div>
                    <fieldset className="wt-contact-consent">
                      <legend>{t("개인정보 수집 및 이용 동의 (필수)")}</legend>
                      <p>{t("수집 항목: 이메일 주소, 문의 내용, 첨부 파일 · 이용 목적: 문의 확인 및 답변 · 보유 기간: 문의 해결 후 1년")}</p>
                      <div className="wt-contact-consent-row">
                        <label><input type="checkbox" required checked={contactPrivacyConsent}
                          onChange={(event) => setContactPrivacyConsent(event.target.checked)} />
                          <span>{t("위 내용에 동의합니다. (필수)")}</span></label>
                        <a href={LEGAL_LINKS.privacy} target="_blank" rel="noopener noreferrer">{t("자세히 보기")}</a>
                      </div>
                    </fieldset>
                    <input className="wt-contact-honeypot" type="text" name="website" tabIndex={-1} autoComplete="off"
                           aria-hidden="true" value={contactWebsite} onChange={(event) => setContactWebsite(event.target.value)} />
                    {contactError && <p className="wt-contact-error" role="alert">{contactError}</p>}
                    {contactMessage.trim().length > 0 && contactMessage.trim().length < 5 &&
                      <p className="wt-contact-error">{t("문의 내용은 5자 이상 적어 주세요.")}</p>}
                    <button type="submit" className="wt-contact-submit" disabled={contactBusy || !contactEmail.trim() || contactMessage.trim().length < 5 || contactMessage.length > 3000 || !contactPrivacyConsent}>
                      {contactBusy ? t("보내는 중…") : t("문의 보내기")}
                    </button>
                  </form>
                )}
                <div className="wt-contact-address"><IconMail size={15} /><span>{t("문의 이메일")}</span><b>{WEBTOON_SUPPORT_EMAIL}</b></div>
              </Dialog>
            )}
            {trashOpen && (
              <Dialog title={t("휴지통")} wide onClose={() => setTrashOpen(false)}
                      sub={t("지운 웹툰은 {n}일 동안 여기 있다가 영구 삭제돼요.", { n: keepDays })}>
                {trash.length === 0 ? (
                  <p className="wt-my-trashempty muted">{t("휴지통이 비어 있어요.")}</p>
                ) : (
                  <div className="wt-my-trash">
                    {trash.map((r) => (
                      <TrashRow key={r.run_id} run={r}
                                onRestored={() => { void loadRuns(); void loadTrash(); }} />
                    ))}
                  </div>
                )}
                <div className="wt-dialog-actions">
                  <button type="button" className="btn btn-w" onClick={() => setTrashOpen(false)}>{t("닫기")}</button>
                </div>
              </Dialog>
            )}
        {tab === "works" && (
          <>
            <div className="wt-my-head">
              <div>
                {allWorks && (
                  <button type="button" className="wt-my-back" onClick={() => setAllWorks(false)}>
                    ← {t("내 작업실")}
                  </button>
                )}
                <h2>{t("내 웹툰")} {allWorks && <span className="dim wt-my-count">{runs.length}</span>}</h2>
              </div>
              <div className="wt-my-headacts">
                {/* 소식(알림 · 구독 중 · 신고)은 맨 위 칸 대신 종 단추 하나로 */}
                {isAuthenticated && <CommunityBell admin={user?.role === "ADMIN"} />}
                <button type="button" className="btn btn-w" onClick={() => go("works")}>{t("둘러보기")}</button>
                <button type="button" className="btn btn-p" onClick={() => go("entry")}>{t("새 웹툰 만들기")}</button>
              </div>
            </div>


            {runsFailed && (
              <div className="wt-my-empty">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={louArt("error")} alt="" aria-hidden="true" />
                <b>{t("목록을 가져오지 못했어요")}</b>
                <span className="dim">{t("서버가 떠 있는지 확인해 주세요")}</span>
              </div>
            )}
            {!runsFailed && runs.length === 0 && (
              <div className="wt-my-empty">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={louArt("empty")} alt="" aria-hidden="true" />
                <b>{t("아직 만든 웹툰이 없어요")}</b>
                <button type="button" className="inline-link" onClick={() => go("entry")}>
                  {t("내 캐릭터로 웹툰 만들기")}
                </button>
              </div>
            )}
            {!runsFailed && runs.length > 0 && (
              <div className={`wt-my-grid${allWorks ? "" : " preview"}`}>
                {(allWorks ? runs : runs.slice(0, WORKS_PREVIEW)).map((r) => (
                  <WorkCard key={r.run_id} run={r} go={go} keepDays={keepDays}
                            onDeleted={() => {
                              setRuns((list) => list.filter((x) => x.run_id !== r.run_id));
                              void loadTrash();
                            }} />
                ))}
              </div>
            )}
            {!allWorks && !runsFailed && runs.length > WORKS_PREVIEW && (
              <button type="button" className="btn btn-w wt-my-more" onClick={() => { setAllWorks(true); window.scrollTo({ top: 0 }); }}>
                {t("내 웹툰 모두 보기")} <span className="dim">{runs.length}</span>
              </button>
            )}

            {!allWorks && <>
            {(active.length > 0 || seriesDrafts.length > 0) && (
              /* 만드는 중(#548) — 「최근 본 웹툰」 자리. 표지 한 줄과 같은 크기로, 눌러서 이어 만든다. */
              <div className="wt-my-strip">
                <div className="wt-strip">
                  <b className="wt-strip-title">{t("만드는 중")} <span className="dim wt-my-draftcount">{active.length + seriesDrafts.length}</span></b>
                  <div className="wt-strip-row">
                    {active.map((j) => (
                      <DraftCard key={j.id} job={j} card={activeCards[j.id]}
                                 onOpen={() => { track("resume_job", { job: j.id, status: j.status }); go("running", { job: j.id }); }} />
                    ))}
                    {seriesDrafts.map((d) => (
                      <SeriesDraftItem key={d.run} draft={d} cover={runs.find((r) => r.run_id === d.run)}
                                       onOpen={() => { track("resume_series_draft", { run: d.run, ep: d.episode }); go("next", { run: d.run }); }} />
                    ))}
                  </div>
                </div>
              </div>
            )}

            {isAuthenticated && (
              <div className="wt-my-strip">
                {liked.length > 0 ? (
                  <RunStrip title={t("찜한 웹툰")} runs={liked}
                            onOpen={(r) => { track("works_open", { run: r.run_id, where: "mypage_likes" }); go("result", { run: r.run_id }); }} />
                ) : (
                  <div className="wt-strip">
                    <b className="wt-strip-title">{t("찜한 웹툰")}</b>
                    <span className="muted" style={{ fontSize: 12.5 }}>{t("아직 찜한 웹툰이 없어요. 둘러보기에서 하트를 눌러 보세요.")}</span>
                  </div>
                )}
              </div>
            )}

            <div className="wt-my-head wt-my-head-sub">
              <div>
                <h2>{t("내 캐릭터")}</h2>
              </div>
              <button type="button" className="btn btn-w" onClick={() => setTab("chars")}>{t("모두 보기")}</button>
            </div>
            <div className="wt-my-chars">
              {chars.map((c) => (
                <button type="button" key={c.id} className="wt-my-char" onClick={() => setTab("chars")}>
                  {/* eslint-disable-next-line @next/next/no-img-element */}
                  <img src={c.art_url || ""} alt="" />
                  <b>{c.name}</b>
                </button>
              ))}
              <button type="button" className="wt-my-char-new" onClick={() => go("try")}>
                <b>+</b>{t("만들기")}
              </button>
            </div>
            </>}
          </>
        )}

        {tab === "chars" && (
          /* 내 캐릭터(#548) — 내 웹툰처럼 마이페이지 안에서 카드로 본다. 캐릭터 탭으로 나가지 않는다. */
          <>
            <div className="wt-my-head">
              <div>
                <h2>{t("내 캐릭터")}</h2>
              </div>
              <div className="wt-my-headacts">
                <button type="button" className="btn btn-p" onClick={() => go("try")}>{t("새 캐릭터 만들기")}</button>
              </div>
            </div>
            {chars.length === 0 ? (
              <div className="wt-my-empty">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={louArt("empty")} alt="" aria-hidden="true" />
                <b>{t("아직 만든 캐릭터가 없어요")}</b>
              </div>
            ) : (
              <div className="wt-my-grid">
                {chars.map((c) => (
                  <div key={c.id} className="card wt-my-work wt-my-charcard">
                    <span className="wt-my-cover">
                      {c.art_url ? (
                        /* eslint-disable-next-line @next/next/no-img-element */
                        <img src={c.art_url} alt="" />
                      ) : (
                        <span className="wt-my-charnoimg" aria-hidden="true" />
                      )}
                      {c.status !== "ready" && (
                        <span className="wt-my-draftchip">{c.status === "drawing" ? t("그리는 중") : t("못 그렸어요")}</span>
                      )}
                    </span>
                    <b>{c.name}</b>
                    <span className="muted">
                      {[c.card?.world_label, c.card?.species, ago(c.created_at, t)].filter(Boolean).join(" · ")}
                    </span>
                    <div className="wt-my-workfoot">
                      <button type="button" className="btn btn-p btn-sm grow" disabled={c.status !== "ready"}
                              onClick={() => go("create", { step: 1, character: c.id })}>{t("이 캐릭터로 웹툰")}</button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        )}

        {tab === "settings" && (
          <>
            <div className="wt-my-head">
              <div>
                <h2>{t("설정")}</h2>
              </div>
            </div>
            <div className="card wt-my-setting">
              <div className="wt-my-setting-row">
                <div>
                  <b>{t("웹툰이 다 만들어지면 이메일로 알림")}</b>
                  <span className="muted">{t("부탁하신 웹툰이 다 만들어지면 계정 이메일로 알려 드려요. 게스트로 만들 때 직접 적은 주소는 이 설정과 상관없이 그대로 가요.")}</span>
                </div>
                <button type="button" className={`sw${notify ? "" : " off"}`} role="switch"
                        aria-checked={notify ?? false} aria-label={t("웹툰이 다 만들어지면 이메일로 알림")}
                        disabled={notify === null || notifyBusy} onClick={() => void toggleNotify()}><i /></button>
              </div>
              {notifyErr && <span className="wt-my-err">{notifyErr}</span>}
            </div>
            <PushOptIn variant="settings" />
            {isAuthenticated && (
              <div className="card wt-my-setting">
                <div className="wt-my-setting-row">
                  <div>
                    <b>{t("계정 탈퇴")}</b>
                    <span className="muted">{t("탈퇴하면 바로 로그인할 수 없게 되고, 30일이 지나면 계정·작품·캐릭터·올린 사진이 지워져요. 남은 크레딧도 함께 사라져요. 필요한 작품은 먼저 내려받아 두세요.")}</span>
                  </div>
                  {withdrawMode === "idle" && (
                    <button type="button" className="btn btn-w wt-my-withdraw" onClick={() => setWithdrawMode("confirm")}>{t("탈퇴하기")}</button>
                  )}
                </div>
                {withdrawMode === "confirm" && (
                  <div className="wt-my-withdraw-confirm">
                    {paidCredits > 0 && (
                      <span className="wt-my-err" role="alert">
                        {t("충전한 크레딧이 남아 있어요")} ({paidCredits}C). {t("탈퇴하면 환불받을 수 없어요. 먼저 1:1 문의로 환불을 신청해 주세요.")}{" "}
                        <a href={CONTACT_CHANNEL} target="_blank" rel="noopener noreferrer">{t("환불 신청하기")}</a>
                      </span>
                    )}
                    <span className="muted">{t("정말 탈퇴할까요? 되돌릴 수 없어요.")}</span>
                    <button type="button" className="btn btn-p" disabled={withdrawBusy} onClick={() => void withdraw()}>{t("탈퇴")}</button>
                    <button type="button" className="btn btn-w" disabled={withdrawBusy} onClick={() => setWithdrawMode("idle")}>{t("취소")}</button>
                  </div>
                )}
                {withdrawErr && <span className="wt-my-err">{withdrawErr}</span>}
              </div>
            )}
            {isAuthenticated && user?.role === "ADMIN" && (
              /* 관리자만 — 관리자 대시보드(#779)로 들어가는 입구. 설문은 그 아래 그대로. */
              <div className="card wt-my-setting">
                <div className="wt-my-setting-row">
                  <div>
                    <b>{t("관리자 대시보드")}</b>
                    <span className="muted">{t("우리 쪽 접속을 뺀 지표 · 비공개까지 모든 작품과 캐릭터 · 작품 처리 · 예시 작품 · 결제로 가는 자리. 관리자만 보여요.")}</span>
                  </div>
                  <button type="button" className="btn btn-w" onClick={() => go("admin")}>{t("열기")}</button>
                </div>
              </div>
            )}
            {isAuthenticated && <AdminSurvey />}
          </>
        )}
      </div>

      {creditModal === "charge" && <CreditCharge onClose={() => setCreditModal(null)} />}
      {creditModal === "history" && <CreditHistory onClose={() => setCreditModal(null)} />}
    </div>
  );
}

/** 작품 한 칸 — 표지 · 제목 · 회차 · 공개 스위치 · 편집실. (둘러보기와 같은 규칙) */
/** 휴지통 한 줄 — 표지 · 제목 · 남은 날 · 되살리기(#157). */
function TrashRow({ run, onRestored }: { run: TrashCard; onRestored: () => void }) {
  const t = useT();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const left = Math.max(0, Math.ceil((new Date(run.purge_at).getTime() - Date.now()) / 86_400_000));
  const removedByAdmin = run.moderation?.state === "REMOVED";

  const restore = async () => {
    setBusy(true);
    setErr("");
    try {
      await restoreRun(run.run_id);
      track("run_restore", { run: run.run_id, where: "mypage" });
      onRestored();
    } catch (e) {
      setErr((e as Error).message || t("되살리지 못했습니다"));
      setBusy(false);
    }
  };

  return (
    <div className="wt-my-trashrow">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      {run.cover_url ? <img src={run.cover_url} alt="" /> : <span className="wt-my-trashimg" aria-hidden="true" />}
      <div className="wt-my-trashtext">
        <b>{run.title || t("제목 없음")}</b>
        <span className="muted">{left > 0 ? t("{n}일 뒤 영구 삭제", { n: left }) : t("오늘 영구 삭제")}</span>
        {removedByAdmin && (
          /* 관리자가 삭제 처리한 작품(#638) — 작가는 못 되살린다(서버 RunTrash.restore 도 막는다). */
          <span className="wt-my-modnote">
            {t("관리자가 삭제 처리했어요.")}{run.moderation?.reason ? ` ${t("사유: {reason}", { reason: run.moderation.reason })}` : ""}
          </span>
        )}
        {err && <span className="wt-my-err">{err}</span>}
      </div>
      {!removedByAdmin && (
        <button type="button" className="btn btn-w" disabled={busy} onClick={() => void restore()}>{t("되살리기")}</button>
      )}
    </div>
  );
}


/* 「만드는 중」 화면 확인용 가짜 작업 셋 — 장면 확인 · 그리는 중 · 이야기 고르기. */
const MOCK_DRAFTS: { jobs: NhJob[]; cards: NhActiveCard[] } = (() => {
  const ago = (min: number) => new Date(Date.now() - min * 60000).toISOString();
  const base = { run_id: "mock", error: null, directions: [], pick: null, style: "", style_label: "", stage: "pages",
    stage_index: 2, stages: [], stage_label: "", say: "", checkpoints: true, queue: null, notice: null,
    minutes_left: null, pct: 0, art: null, redraw: null, log: [], elapsed: 0, sheet_ready: false } as unknown as NhJob;
  return {
    jobs: [
      { ...base, id: "mock-a", status: "awaiting_scenes", mode: "own", story: { title: "아이들을 지키는 자", body: "" } } as NhJob,
      { ...base, id: "mock-b", status: "running", mode: "own", story: { title: "비 오는 날의 우산", body: "" }, art: { done: 3, total: 6, retry_page: 0, pages: [1, 2, 3] } } as NhJob,
      { ...base, id: "mock-c", status: "awaiting_pick", mode: "quick", stage: "story" } as NhJob,
    ],
    cards: [
      { id: "mock-a", name: "Isolde Verlaine", created_at: ago(180), updated_at: ago(12) },
      { id: "mock-b", name: "서연화", created_at: ago(90), updated_at: ago(2) },
      { id: "mock-c", name: "몽이", created_at: ago(3000), updated_at: ago(2900) },
    ],
  };
})();

/* 만드는 중 카드(#548) — 시트가 있으면 시트, 없으면 루. 상태는 그림 위 작은 딱지로,
   사람이 답할 차례면 진하게. 아래에 캐릭터 · 길 · 마지막으로 손댄 때. */
/** 다음 화 초안 카드 — 앞 화 표지에 「N화 초안」 표시, 누르면 다음 화 이어 만들기 페이지로. */
function SeriesDraftItem({ draft, cover, onOpen }: { draft: SeriesDraftCard; cover?: RunCard; onOpen: () => void }) {
  const t = useT();
  const label = t("{n}화 초안", { n: draft.episode });
  return (
    <button type="button" className="wt-strip-item wt-my-draft" onClick={onOpen} aria-label={`${draft.title || t("제목 짓기 전")} · ${label} · ${t("이어서 만들기")}`}>
      <span className="wt-my-draftcover">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={cover ? coverUrl(draft.run, cover.cover_page ?? 1, cover.cover_episode ?? 1) : louArt("generating")} alt="" className={cover ? "" : "lou"} />
        <span className="wt-my-draftchip wait">{label}</span>
      </span>
      <span className={`wt-strip-name${draft.title ? "" : " dim"}`}>{draft.title || t("제목 짓기 전")}</span>
      {draft.story && <span className="wt-my-draftmeta">{draft.story}</span>}
      <span className="wt-my-draftgo">{t("이어서 만들기")} ›</span>
    </button>
  );
}

function DraftCard({ job, card, onOpen }: { job: NhJob; card?: NhActiveCard; onOpen: () => void }) {
  const t = useT();
  const title = activeJobTitle(job);
  const waiting = job.status.startsWith("awaiting_");
  const meta = [card?.name, ago(card?.updated_at, t)].filter(Boolean).join(" · ");
  return (
    <button type="button" className="wt-strip-item wt-my-draft" onClick={onOpen} aria-label={`${title || t("제목 짓기 전")} · ${t("이어서 만들기")}`}>
      <span className="wt-my-draftcover">
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={job.sheet_ready ? sheetImageUrl(job.id) : louArt("generating")} alt="" className={job.sheet_ready ? "" : "lou"} />
        <span className={`wt-my-draftchip${waiting ? " wait" : ""}`}>
          {job.status === "running" && job.art?.total ? t("그리는 중 · {label}", { label: activeJobLabel(job, t) }) : activeJobLabel(job, t)}
        </span>
      </span>
      <span className={`wt-strip-name${title ? "" : " dim"}`}>{title || t("제목 짓기 전")}</span>
      {meta && <span className="wt-my-draftmeta">{meta}</span>}
      <span className="wt-my-draftgo">{t("이어서 만들기")} ›</span>
    </button>
  );
}

/** 「방금 · n분 전 · n시간 전 · n일 전」 */
function ago(iso: string | null | undefined, t: (s: string, p?: Record<string, string | number>) => string): string {
  if (!iso) return "";
  const min = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 60000));
  if (min < 1) return t("방금");
  if (min < 60) return t("{n}분 전", { n: min });
  if (min < 60 * 24) return t("{n}시간 전", { n: Math.floor(min / 60) });
  return t("{n}일 전", { n: Math.floor(min / 1440) });
}

function WorkCard({ run, go, keepDays, onDeleted }: { run: RunCard; go: Go; keepDays: number; onDeleted: () => void }) {
  const t = useT();
  /* 관리자가 비공개 처리한 작품(#638) — 공개 스위치를 잠근다. 서버도 다시 공개를 막는다. */
  const hiddenByAdmin = run.moderation?.state === "HIDDEN";
  const [pub, setPub] = useState(run.public !== false && !hiddenByAdmin);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");

  /* 지우기(#55) — 두 번 눌러야 된다. 첫 번째는 자리를 확인 문구로 바꾸기만 한다. */
  const [confirming, setConfirming] = useState(false);
  const remove = async () => {
    setBusy(true);
    setErr("");
    try {
      await deleteRun(run.run_id);
      forgetMyRun(run.run_id);
      track("run_delete", { run: run.run_id, where: "mypage" });
      onDeleted();
    } catch (e) {
      setErr((e as Error).message || t("지우지 못했습니다"));
      setBusy(false);
    }
  };

  /* 서버에 먼저 보내고, 실패하면 되돌린다 — 화면만 바뀌어 있으면 다음에
     들어왔을 때 값이 달라 보인다. */
  const flip = async () => {
    const want = !pub;
    setBusy(true);
    setErr("");
    setPub(want);
    try {
      const out = await setVisibility(run.run_id, want);
      setPub(out.public ?? want);
    } catch {
      setPub(!want);
      setErr(t("바꾸지 못했습니다"));
    } finally {
      setBusy(false);
    }
  };

  const open = (episode = 1) => {
    const runId = run.episode_run_ids?.[String(episode)] ?? run.run_id;
    go("result", { run: runId });
  };

  return (
    <div className="card wt-my-work">
        <button type="button" className="wt-my-cover" onClick={() => open()} aria-label={run.title || t("제목 없음")}>
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img src={coverUrl(run.run_id, run.cover_page ?? 1, run.cover_episode ?? 1)} alt="" />
      </button>
      <b>{run.title || t("제목 없음")}</b>
      <span className="muted">{[run.character, run.genre && t(run.genre)].filter(Boolean).join(" · ")}</span>
      <div className="wt-my-eps">
        {run.episodes.map((n) => (
          <button key={n} type="button" className="ep" onClick={() => open(n)}>{t("{n}화", { n })}</button>
        ))}
      </div>
      <div className="wt-my-workfoot">
        <span className="wt-my-pub">
          <button type="button" className={`sw${pub ? "" : " off"}`} role="switch" aria-checked={pub}
                  aria-label={t("둘러보기에 공개")} disabled={busy || hiddenByAdmin} onClick={() => void flip()}><i /></button>
          {hiddenByAdmin ? t("관리자 비공개") : pub ? t("공개") : t("비공개")}
        </span>
        <span className="wt-my-workacts">
          <button type="button" className="btn btn-w wt-my-edit" onClick={() => go("editor", { run: run.run_id })}>
            {t("편집실")}
          </button>
          <button type="button" className="btn btn-w wt-my-edit" disabled={busy} onClick={() => setConfirming(true)}>
            {t("지우기")}
          </button>
        </span>
      </div>
      {(hiddenByAdmin || run.moderation?.warning) && (
        <span className="wt-my-modnote" role="status">
          {hiddenByAdmin && <>{t("관리자가 비공개 처리했어요.")}{run.moderation?.reason ? ` ${t("사유: {reason}", { reason: run.moderation.reason })}` : ""}</>}
          {hiddenByAdmin && run.moderation?.warning && <br />}
          {run.moderation?.warning && <>{t("운영 정책 경고가 있어요.")} {t("사유: {reason}", { reason: run.moderation.warning.reason })}</>}
        </span>
      )}
      {confirming && (
        <ConfirmDialog title={t("휴지통으로 옮길까요?")}
                       sub={<><b>{run.title || t("제목 없음")}</b><br />{t("{n}일 안에는 휴지통에서 되살릴 수 있어요.", { n: keepDays })}</>}
                       confirmLabel={t("지우기")} cancelLabel={t("취소")} busy={busy}
                       error={err} onConfirm={() => void remove()} onClose={() => { setConfirming(false); setErr(""); }} />
      )}
      {err && <span className="wt-my-err">{err}</span>}
    </div>
  );
}
