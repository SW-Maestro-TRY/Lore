// 4. 작품 벽 — 페이지 맨 아래, 마지막 CTA 다음 · 푸터 바로 위.
// (시안 C · 2026-09-20 확정. 프로토타입: haeun/landing-concepts/c-split.html)
//
// 제목도 단추도 없이 그림만 두 줄로 흐른다. 위·아래 줄이 반대 방향으로 돈다.
// 자바스크립트가 없다 — 프로토타입은 브라우저에서 DOM 을 채웠지만, 여기서는
// 그냥 배열을 두 번 이어 붙여 서버에서 렌더링한다.
//
// 그림 추가·교체하는 법은 common/fe/landing/README.md 참고 — 아래 WALL_IMAGES
// 배열에 파일명만 넣으면 된다. 원본 파일은 common/fe/assets/landing/wall/ 에
// 둔다(거기가 git 이 보는 자리다 — apps/web/public/static 전체가 gitignore
// 돼 있어서, sync-landing-assets.sh 가 빌드 전에 그 폴더를 여기로 복사해 온다).
import styles from "../landing.module.css";

const WALL_IMAGES: readonly string[] = [
  "wall-01.jpg",
  "wall-02.jpg",
  "wall-03.jpg",
  "wall-04.jpg",
  "wall-05.png",
  "wall-06.jpg",
  "wall-07.jpg",
  "wall-08.jpg",
  "wall-09.jpg",
  "wall-10.jpg",
  "wall-11.jpg",
  "wall-12.jpg",
];

function Row({ back }: { back?: boolean }) {
  // 위·아래 줄이 반대 방향으로 돌게 순서를 뒤집는다.
  const order = back ? [...WALL_IMAGES].reverse() : WALL_IMAGES;
  // 두 벌을 이어 붙여야 -50% 지점에서 다음 바퀴 시작과 이가 맞아 끊김이 안 보인다.
  const doubled = [...order, ...order];
  return (
    <div className={`${styles.wallRow} ${back ? styles.wallRowBack : ""}`}>
      {doubled.map((file, i) => (
        <div
          className={styles.wallTile}
          key={`${file}-${i}`}
          aria-hidden={i >= order.length}
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
      <Row />
      <Row back />
    </div>
  );
}
