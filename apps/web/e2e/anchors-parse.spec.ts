// 앵커 파일 해석 — 브라우저 없이 순수 함수만 본다.
//
// ★ 지키는 것 둘
//   1) 캔버스는 `[w, h]` 배열과 `{ w, h }` 객체를 둘 다 받고, 이상한 값은 고정값으로 버틴다.
//      후처리가 실제로 내는 모양은 객체인데 배열만 받던 때는 서버 값이 늘 버려졌다.
//   2) 서버 파일에 없는 자세는 "서버에서 온 자세" 로 치지 않는다. 1층이 빠진 파일(펫 5·8·9)에서
//      여울 고정표로 메운 자세를 서버 값으로 믿어 아이가 발끝선 위로 뜨던 문제가 있었다.
import { expect, test } from '@playwright/test';
import { mergeAnchors, parseCanvas } from '../../../zzal/fe/tamagotchi/props/anchors';
import { FIXED_ANCHORS } from '../../../zzal/fe/tamagotchi/props/anchors-fixed';
import { poseFromServer } from '../../../zzal/fe/tamagotchi/props/layout';

const FALLBACK: [number, number] = [312, 349];
const POSE = {
  bbox: { x: 99, y: 27, w: 146, h: 255 },
  head_top: { x: 160, y: 27 },
  head_side: { y: 60, left_x: 110, right_x: 220 },
  hand_front: { y: 160, left_x: 130, right_x: 200 },
  feet: { y: 282, left_x: 130, right_x: 200, center_x: 165 },
};

test.describe('앵커 캔버스 해석', () => {
  test('{ w, h } 객체를 받는다', () => {
    expect(parseCanvas({ w: 312, h: 386 }, FALLBACK)).toEqual([312, 386]);
  });
  test('[w, h] 배열을 받는다', () => {
    expect(parseCanvas([300, 400], FALLBACK)).toEqual([300, 400]);
  });
  test('잘못된 값은 고정값으로 버틴다', () => {
    expect(parseCanvas(undefined, FALLBACK)).toEqual(FALLBACK);
    expect(parseCanvas('312x349', FALLBACK)).toEqual(FALLBACK);
    expect(parseCanvas([312], FALLBACK)).toEqual(FALLBACK);
    expect(parseCanvas({ w: 0, h: -1 }, FALLBACK)).toEqual(FALLBACK);
    expect(parseCanvas({ w: 'a', h: 400 }, FALLBACK)).toEqual([312, 400]);
  });
  test('mergeAnchors 가 객체 캔버스를 실제로 쓴다', () => {
    const got = mergeAnchors({ canvas: { w: 312, h: 386 }, K: 255, Hw: 118, poses: { eat_rice: POSE } });
    expect(got?.canvas).toEqual([312, 386]);
  });
});

test.describe('서버에서 온 자세', () => {
  test('1층이 빠진 파일 — base 는 고정값으로 메워지고 서버 자세로 안 친다', () => {
    const got = mergeAnchors({ canvas: { w: 312, h: 386 }, K: 255, Hw: 118, poses: { eat_rice: POSE, wash: POSE } });
    expect(got).not.toBeNull();
    expect(got!.poses.base).toEqual(FIXED_ANCHORS.poses.base);
    expect(got!.serverPoses).toEqual(['eat_rice', 'wash']);
    expect(poseFromServer(got!, 'base')).toBe(false);
    expect(poseFromServer(got!, 'hello')).toBe(false);
    expect(poseFromServer(got!, 'eat_rice')).toBe(true);
  });
  test('다 갖춘 파일 — 모든 자세가 서버 자세다', () => {
    const poses = Object.fromEntries(Object.keys(FIXED_ANCHORS.poses).map((k) => [k, POSE]));
    const got = mergeAnchors({ canvas: { w: 312, h: 349 }, K: 255, Hw: 118, poses });
    for (const k of Object.keys(FIXED_ANCHORS.poses)) expect(poseFromServer(got!, k)).toBe(true);
    // 표에 없는 동작은 poseAnchors 와 같은 길(별칭 → base)로 고른 key 를 본다.
    expect(poseFromServer(got!, 'roll')).toBe(true);
  });
  test('고정값 그대로면 서버 자세가 없다', () => {
    expect(poseFromServer(FIXED_ANCHORS, 'base')).toBe(false);
  });
});
