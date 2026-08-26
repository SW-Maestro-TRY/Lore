import type { CSSProperties } from "react";

/**
 * 완성된 이미지가 들어갈 자리(플레이스홀더).
 * src 가 있으면 이미지를, 없으면 빗금 배경 + 안내 문구를 보여준다.
 * (jakae 에서 이식 — 이미지는 assets/*.webp 를 import 해서 .src 를 넘긴다.)
 */
export default function ImageSlot({
  placeholder,
  src,
  fit = "cover",
  style,
}: {
  placeholder: string;
  src?: string;
  fit?: "cover" | "contain";
  style?: CSSProperties;
}) {
  return (
    <div
      style={{
        position: "relative",
        overflow: "hidden",
        background:
          "repeating-linear-gradient(-45deg,#eee7d8 0 9px,#f6f0e4 9px 18px)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        ...style,
      }}
    >
      {src ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={src}
          alt=""
          style={{ width: "100%", height: "100%", objectFit: fit }}
        />
      ) : (
        <span
          style={{
            fontFamily: "'Gothic A1'",
            fontWeight: 600,
            fontSize: 12,
            color: "#9a8f7a",
            textAlign: "center",
            padding: "0 12px",
            lineHeight: 1.45,
          }}
        >
          {placeholder}
        </span>
      )}
    </div>
  );
}
