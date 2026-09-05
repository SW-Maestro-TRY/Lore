// 홈 아래쪽 세 섹션 — 어떻게 만드나 · 결과 예시 · 자주 묻는 것.
// haeun/landing/web/index.html 의 #how · #gallery · #faq 를 그대로 옮겼다.


const STEPS: [src: string, title: string, sub: string][] = [
  ["/static/lou/stage/sheet.webp", "루가 사진 속 얼굴을 보고 캐릭터 시트를 그려요",
   "사진 한 장, 이름 하나면 충분해요. 그 얼굴 그대로 끝까지 이어집니다."],
  ["/static/lou/stage/story.webp", "루가 캐릭터를 보고 이야기를 지어요",
   "한 줄만 적어도, 아예 비워 둬도 괜찮아요."],
  ["/static/lou/stage/art.webp", "루가 표지부터 순서대로 그려요",
   "한 편 그리는 데 보통 10분 정도 걸려요."],
  ["/static/lou/stage/done.webp", "다 그린 뒤에도 마음에 안 들면 다시",
   "편집실에서 컷을 골라 손볼 수 있어요."],
];

const GALLERY: [src: string, alt: string, cap: string][] = [
  ["/static/samples/ex-romance-1.png", "로맨스 그림체 예시", "로맨스"],
  ["/static/samples/ex-webtoon-1.jpg", "웹툰체 예시", "웹툰체"],
  ["/static/samples/ex-frost-1.jpg", "프로스트 그림체 예시", "프로스트"],
  ["/static/samples/ex-shoujo-1.jpg", "순정 그림체 예시", "순정"],
];

const FAQ: [q: string, a: React.ReactNode][] = [
  ["이건 누가 만든 서비스인가요?",
   <><b>AI SW 마에스트로</b> 과정에서 만들고 있는 프로젝트예요. 아직 다듬는 중이라
     낯선 부분이 남아 있을 수 있는데, 발견하시면 언제든 알려주세요 — 확인하고
     고치겠습니다.</>],
  ["AI SW 마에스트로란 무엇인가요?",
   <>과학기술정보통신부가 주관하고 정보통신기획평가원(IITP)이 운영하는 AI 소프트웨어
     인재 양성 과정이에요. LORE는 이 과정에서 실제로 기획하고 만들고 있는
     프로젝트입니다.</>],
  ["대사가 어색하거나 틀렸어요 — 고칠 수 있나요?",
   <>그림에 함께 그려진 글자라 바로 고쳐지지는 않아요. 편집실에서 그 컷을 다시
     그리게 하거나, 원래 글자를 가리고 새 말풍선을 얹어 원하는 대사로 바꿀 수
     있어요.</>],
  ["마음에 안 들면 다시 만들 수 있나요?",
   <>네, 가능해요. <b>「3번만 확인하며」</b>를 고르면 이야기와 그림 단계마다
     확인하면서 다시 만들 수 있고, 완성한 뒤에도 편집실에서 컷 단위로 다시 그릴 수
     있어요.</>],
  ["만들다가 창을 닫거나 다른 걸 하면 어떻게 되나요?",
   <>괜찮아요. 진행 상황은 서버가 갖고 있어서, 나중에 같은 작업으로 다시 들어오면
     하던 데서 그대로 이어집니다.</>],
  ["얼마나 걸리나요?",
   <>한 편에 보통 10분 안팎 걸려요. 그림체나 이야기 길이에 따라 조금씩 달라질 수
     있어요.</>],
  ["그림을 하나도 못 그려도 쓸 수 있나요?",
   <>네, 그림을 못 그리셔도 괜찮아요. 사진 한 장과 이름만 있으면 버튼 하나로 끝까지
     완성됩니다.</>],
  ["그림체는 몇 가지인가요?",
   <>로맨스·웹툰체·프로스트·순정 등 여러 그림체가 있어요. 위 「루가 그린 그림체」에서
     실제 결과를 미리 볼 수 있어요.</>],
  ["로그인 안 해도 만들 수 있나요?",
   <>네, 로그인 없이도 게스트로 끝까지 만들 수 있어요. 나중에 로그인해서 저장하면
     마이페이지에서 다시 볼 수 있어요.</>],
  ["여러 캐릭터로 여러 편 만들 수 있나요?",
   <>네, 얼마든지요. 로그인하면 그동안 만든 작품이 마이페이지에 모두 모여요.</>],
  ["완성한 웹툰은 다른 사람도 볼 수 있나요?",
   <>기본적으로 <b>둘러보기</b>에 공개돼요. 마이페이지에서 언제든 비공개로 바꿀 수
     있어요.</>],
  ["올린 사진은 어떻게 되나요?",
   <>캐릭터를 만드는 데만 사용하고, 시트가 완성되면 서버에서 바로 삭제해요.</>],
];

export default function HowGalleryFaq({ onSeeFull }: { onSeeFull: () => void }) {
  return (
    <>
      <section className="how" id="how">
        <div className="how-head">
          <p className="eyebrow">How it works</p>
          <h2>사진 한 장이 웹툰 한 화가 되기까지</h2>
          <p className="section-lede">
            루는 그림 도구가 아니라 <b>제작 과정 전체를 대신하는 스튜디오</b>예요.
            캐릭터 사진 한 장, 이야기 한 줄이면 웹툰 한 화가 통째로 나옵니다.
          </p>
          <ul className="how-audience">
            <li>웹소설·팬픽 세계관을 그림으로 먼저 확인해보고 싶은 작가</li>
            <li>캐릭터는 있지만 웹툰 제작은 처음인 창작 입문자</li>
            <li>여러 그림체·전개를 빠르게 뽑아보고 고르고 싶은 기획자</li>
          </ul>
        </div>

        <ol className="how-steps">
          {STEPS.map(([src, title, sub], i) => (
            <li key={title}>
              <div className="how-thumb">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img className="how-stage-art" src={src} alt="" aria-hidden="true" />
                <span className="how-no">{i + 1}</span>
              </div>
              <div>
                <b>{title}</b>
                <small>{sub}</small>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <section className="gallery" id="gallery">
        <div className="gallery-head">
          <p className="eyebrow">Examples</p>
          <h2>루가 그린 그림체</h2>
          <p className="section-lede">그림체마다 손이 다릅니다 — 마음에 드는 결로 시작하세요.</p>
        </div>
        <div className="gallery-grid">
          {GALLERY.map(([src, alt, cap]) => (
            <figure key={cap}>
              {/* eslint-disable-next-line @next/next/no-img-element */}
              <img src={src} alt={alt} loading="lazy" />
              <figcaption>{cap}</figcaption>
            </figure>
          ))}
        </div>
        <button type="button" className="btn btn-ghost gallery-full" onClick={onSeeFull}>
          완성된 웹툰 한 편 전체 보기 →
        </button>
      </section>

      <section className="faq" id="faq">
        <div className="faq-head">
          <p className="eyebrow">FAQ</p>
          <h2>자주 묻는 것</h2>
        </div>
        {FAQ.map(([q, a]) => (
          <details className="faq-item" key={q}>
            <summary>{q}</summary>
            <p>{a}</p>
          </details>
        ))}
      </section>
    </>
  );
}
