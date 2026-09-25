// 4. 작품 벽 — 페이지 맨 아래, 마지막 CTA 다음 · 푸터 바로 위.
// (시안 C · 2026-09-20 확정. 프로토타입: haeun/landing-concepts/c-split.html)
//
// 제목도 단추도 없이 그림만 두 줄로 흐른다. 윗줄은 웹툰(예시 작품 표지와 예시
// 캐릭터), 아랫줄은 짤(여러 배경에서 여러 동작을 하는 키우기 캐릭터)이고 반대
// 방향으로 돈다. 두 줄이 다른 그림이라 같은 그림이 겹쳐 보이지 않는다. 마우스를
// 올려도 멈추지 않는다. 자바스크립트가 없다 — 배열을 두 번 이어 붙여 서버에서
// 렌더링한다.
//
// 그림 추가·교체하는 법은 common/fe/landing/README.md 참고 — 아래 두 배열에
// 파일명만 넣으면 된다. 원본 파일은 common/fe/assets/landing/wall/ 에
// 둔다(거기가 git 이 보는 자리다 — apps/web/public/static 전체가 gitignore
// 돼 있어서, sync-landing-assets.sh 가 빌드 전에 그 폴더를 여기로 복사해 온다).
import styles from "../landing.module.css";

const WEBTOON_IMAGES: readonly string[] = [
  "webtoon-01.jpg",
  "webtoon-02.jpg",
  "webtoon-03.jpg",
  "webtoon-04.jpg",
  "webtoon-05.jpg",
  "webtoon-06.jpg",
  "webtoon-07.jpg",
  "webtoon-08.jpg",
  "webtoon-09.jpg",
  "webtoon-10.jpg",
  "webtoon-11.jpg",
  "webtoon-12.jpg",
  "webtoon-13.jpg",
  "webtoon-14.jpg",
  "webtoon-15.jpg",
  "webtoon-16.jpg",
  "webtoon-17.jpg",
  "webtoon-18.jpg",
  "webtoon-19.jpg",
  "webtoon-20.jpg",
  "webtoon-21.jpg",
];

const ZZAL_IMAGES: readonly string[] = [
  "zzal-01.jpg",
  "zzal-02.jpg",
  "zzal-03.jpg",
  "zzal-04.jpg",
  "zzal-05.jpg",
  "zzal-06.jpg",
  "zzal-07.jpg",
  "zzal-08.jpg",
  "zzal-09.jpg",
  "zzal-10.jpg",
  "zzal-11.jpg",
  "zzal-12.jpg",
  "zzal-13.jpg",
  "zzal-14.jpg",
  "zzal-15.jpg",
];

function Row({ images, back }: { images: readonly string[]; back?: boolean }) {
  // 두 벌을 이어 붙여야 -50% 지점에서 다음 바퀴 시작과 이가 맞아 끊김이 안 보인다.
  const doubled = [...images, ...images];
  return (
    <div className={`${styles.wallRow} ${back ? styles.wallRowBack : ""}`}>
      {doubled.map((file, i) => (
        <div
          className={styles.wallTile}
          key={`${file}-${i}`}
          aria-hidden={i >= images.length}
        >
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={`/static/landing/wall/${file}`} alt="" loading="lazy" />
        </div>
      ))}
    </div>
  );
}

export default function Wall() {
  return (
    <div className={styles.wall} aria-hidden="true">
      <Row images={WEBTOON_IMAGES} />
      <Row images={ZZAL_IMAGES} back />
    </div>
  );
}
