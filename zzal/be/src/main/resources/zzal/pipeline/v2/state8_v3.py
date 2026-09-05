#!/usr/bin/env python3
"""실험4c(초록배경·4마크) 전용 후처리 — 균등절단 → 초록 키잉 → 침범제거 → 층1·층2 정렬 → GIF.
사용: state8_v3.py <grid.png>
"""
import sys
from pathlib import Path
from collections import deque
import numpy as np
from scipy import ndimage
from PIL import Image
NAMES=["기본","식사","배고픔","청소","행복","불행","쓰다듬","훈련","잠"]  # 2026-08-25 상훈님 재구성(졸림→청소, 9번째 잠은 6x3 시험용)

def key_green(cell, cell_h=None, lo=10, hi=90, despill=True):
    """초록 배경을 지운다 — **알파는 연속값**(2026-08-25 전면 개편).

    ★왜 바꿨나 (2026-08-25 상훈님 지적 "벌레가 파먹은 것처럼 되어 있고 좀 울퉁불퉁한데
      이거는 소닉만의 문제는 아니야")
      옛 판은 `bg = g > 40` 하나로 갈라 알파를 **0 아니면 255**로만 줬다.
      그런데 캐릭터와 배경이 만나는 가장자리는 두 색이 섞인 중간색이고,
      **머리카락 한 가닥처럼 가는 부분은 픽셀 전체가 중간색**이라 통째로 배경 판정돼 사라졌다.
      실측(우리 시드 5판, 40<g<120 구간): 여울 15,813px · 소닉 11,030px · 블룸 10,858px ·
      흑연 8,821px · 김애용 6,916px 이 그렇게 지워지고 있었다.
      → 여울 옆머리가 끊기고 블룸 흰머리 외곽이 점선처럼 됐다. **모든 세대에 있던 결함이다.**

    처방 = 크로마키의 정석. 초록 우세도 g로 알파를 부드럽게 깎고(lo~hi 사이 선형),
    남은 초록 기운은 despill 로 색에서 뺀다.
      g <= lo : 완전 불투명(캐릭터)  ·  g >= hi : 완전 투명(배경)  ·  사이: 선형
    """
    a = np.array(cell.convert("RGB")).astype(float)
    g = a[:,:,1] - np.maximum(a[:,:,0], a[:,:,2])      # 초록 우세도
    alpha = np.clip((hi - g) / float(hi - lo), 0.0, 1.0) * 255.0

    # ★2026-08-26 신설 — 배경은 **칸 가장자리에서 이어진 것**만이다.
    #   상훈님 지적으로 드러난 결함: 김애용·소닉의 **초록 눈**(RGB 57,137,48 → 우세도 80)이
    #   배경으로 판정돼 알파가 32까지 떨어지고 despill로 색까지 빠져 **회색 눈**이 됐다.
    #   캐릭터 안에 갇힌 초록은 배경일 수 없다. 다만 거의 순수한 초록(우세도 150+)은
    #   머리카락이 에워싼 진짜 배경 구멍이므로 갇혀 있어도 지운다.
    cand = alpha < 128
    lab_bg, n_bg = ndimage.label(cand)
    if n_bg:
        edge = set(lab_bg[0, :]) | set(lab_bg[-1, :]) | set(lab_bg[:, 0]) | set(lab_bg[:, -1])
        edge.discard(0)
        outside = np.isin(lab_bg, list(edge)) if edge else np.zeros_like(cand)
        # ★2026-08-26 v3 — 여기서 조건 없이 복원했더니 **여울 머리카락 틈의 배경까지 살아나**
        #   초록 얼룩이 남았다(상훈님: "저거는 사고야"). 셋을 모두 만족할 때만 캐릭터로 인정한다.
        #   ⓐ 충분히 크다(50px+)  ⓑ 둥글다(가로세로비 2.5 미만)
        #   ⓒ ★B가 R보다 확실히 높다 — 배경 #00FF00 이 어두워진 것은 R과 B가 비슷하게 낮지만
        #      캐릭터의 초록 눈은 청록 쪽으로 치우친다.
        #      실측: 김애용 눈 RGB(37,124,60) B-R=+23 / 여울 머리틈 (49,125,38) -11 / 소닉 배경 (32,116,23) -9
        trapped = cand & ~outside & (g < 150)
        tl, tn = ndimage.label(trapped)
        keep = np.zeros_like(trapped)
        for _i in range(1, tn + 1):
            _m = tl == _i
            if _m.sum() < 50:
                continue
            _ys, _xs = np.where(_m)
            _h, _w = _ys.max()-_ys.min()+1, _xs.max()-_xs.min()+1
            if max(_h, _w) / max(min(_h, _w), 1) >= 2.5:
                continue
            if (a[_m][:, 2].mean() - a[_m][:, 0].mean()) <= 10:
                continue
            keep |= _m
        alpha = np.where(keep, 255.0, alpha)

    out = np.dstack([a, alpha])
    if despill:
        # ★남아 있는 픽셀 **전부**에서 초록 기운을 뺀다(옛 판은 경계 한 줄만 손봤다).
        m = np.maximum(out[:,:,0], out[:,:,2])
        # ★2026-08-26 — **경계 픽셀에만**. 옛 판은 alpha>0 전부를 건드려
        #   배경과 닿지도 않은 초록 눈·초록 소품의 색을 빼버렸다.
        spill = (alpha > 0) & (alpha < 255) & (out[:,:,1] > m)
        out[:,:,1] = np.where(spill, m, out[:,:,1])
    out = np.clip(out, 0, 255).astype(np.uint8)

    # 마크(마젠타·시안)와 그 안티에일리어싱 테두리까지 지운다.
    #  ★ 색 판정만으로는 경계의 회색 픽셀이 남아 십자 유령이 생긴다(2026-08-24 실측).
    # ★색 조건을 **순수한 마젠타·시안으로 좁힌다** (2026-08-25).
    #   옛 조건(R<130·G>150·B>150)은 너무 헐거워 **소닉 가시의 밝은 파랑 RGB(129,177,255)**가
    #   시안으로 오인됐다. 실측: 마크로 판정된 341px 중 132px이 몸통 높이의 가시였고,
    #   4px 번지기가 얹혀 한 칸 최대 557px·16칸 합계 3,401px 이 뜯겨 나갔다(소닉 대조군).
    #   진짜 마크는 RGB(4,247,255)·(239,8,248)처럼 **순수**하다. 임계를 80/200으로 조이면
    #   가시(R=129)는 빠지고 마크는 남는다. 경계의 흐린 픽셀은 아래 4px 번지기가 덮는다.
    #   ⚠️세로 위치로 거르려 했다가 실패했다 — 칸을 자를 때 아래에 PAD를 붙이므로
    #     아래쪽 마크가 이미지 하단에서 PAD 만큼 위에 있어, 하단 띠 기준으로는 **마크를 놓친다**
    #     (실측: 여울·블룸 칸에 분홍 십자가 그대로 남았다).
    #   ★방법 = **씨앗 + 연결 확장**. 순수한 마크 중심(seed)을 찾고, 거기에 **닿아 있는**
    #     느슨한 색(loose) 덩어리만 마크로 인정한다. 소닉 가시는 loose 에는 걸려도
    #     순수한 씨앗이 없으므로 통째로 제외되고, 마크는 흐린 가장자리까지 따라간다.
    #     (색만 좁히면 흐린 가장자리를 놓쳐 십자가 남고, 넓히면 가시를 먹는다. 둘 다 겪었다.)
    r,gg,b = out[:,:,0].astype(int), out[:,:,1].astype(int), out[:,:,2].astype(int)
    # ★2026-08-26 — 씨앗을 **순수색**으로 더 조이고 **칸 모서리**로 제한한다.
    #   상훈님 지적: 김애용 훈련 칸의 땀방울 RGB(76,221,235)가 옛 조건
    #   (R<80·G>200·B>200)에 그대로 걸려 **f15는 80%, f16은 66%가 뜯겨나갔다.**
    #   주석에 "훈련의 파란 땀방울은 G가 낮아서 씨앗이 안 된다"고 적어뒀던 것은
    #   **여울 기준**이었다 — 캐릭터마다 땀 색이 다르다.
    #   격자점은 정의상 칸 네 모서리에만 있으므로, 위치로 못 박으면 캐릭터 옆
    #   소품은 애초에 후보가 되지 않는다(가로 띠만 걸던 옛 방식과 달리 격자 형태와 무관).
    seed  = ((r>235)&(gg< 50)&(b>235)) | ((r< 50)&(gg>235)&(b>235))
    if cell_h is not None:
        _h, _w = seed.shape; _R = int(_w * 0.16)
        _corner = np.zeros((_h, _w), bool)
        for _cy in (0, cell_h):
            for _cx in (0, _w - 1):
                _corner[max(0,_cy-_R):min(_h,_cy+_R+1), max(0,_cx-_R):min(_w,_cx+_R+1)] = True
        seed &= _corner
    loose = ((r>150)&(gg<130)&(b>150)) | ((r<130)&(gg>150)&(b>150))
    # ★가로 제한은 유지 — 마크는 칸 좌우 가장자리 근처에만 있다. 이 조건이 없으면
    #   캐릭터 옆의 파란 땀방울이 시안으로 오인돼 지워지고 머리에 구멍이 난다(2026-08-24 실측).
    # ⚠️가로 22% 위치 제한은 **뺐다** (2026-08-25) — 6열x3행 격자에서 칸 비율이 달라지자
    #   마크가 그 띠 밖에 놓여 십자가 그대로 남았다. 씨앗을 **순수색**(R<80·G>200·B>200 등)으로
    #   좁혀 뒀으므로 위치 조건 없이도 캐릭터를 오인하지 않는다 —
    #   소닉 가시 RGB(129,177,255)는 R이 커서, 훈련의 파란 땀방울은 G가 낮아서 씨앗이 안 된다.
    core = np.zeros_like(seed)
    if seed.any():
        lab, n = ndimage.label(loose)
        hit = np.unique(lab[seed & (lab > 0)])
        if len(hit):
            core = np.isin(lab, hit)
    if core.any():
        grow = core.copy()
        for _ in range(4):                      # 코어에서 4px 번지기
            g2 = grow.copy()
            g2[1:,:] |= grow[:-1,:]; g2[:-1,:] |= grow[1:,:]
            g2[:,1:] |= grow[:,:-1]; g2[:,:-1] |= grow[:,1:]
            grow = g2
        out[:,:,3] = np.where(grow, 0, out[:,:,3])
    return Image.fromarray(out)

def drop_intruders(im):
    a=np.array(im); m=a[:,:,3]>8; h,w=m.shape
    seen=np.zeros_like(m); comps=[]
    for y0 in range(h):
        for x0 in range(w):
            if m[y0,x0] and not seen[y0,x0]:
                q=deque([(y0,x0)]); seen[y0,x0]=True; px=[]; touch=False
                while q:
                    y,x=q.popleft(); px.append((y,x))
                    if y in (0,h-1) or x in (0,w-1): touch=True
                    for dy,dx in ((1,0),(-1,0),(0,1),(0,-1)):
                        ny,nx=y+dy,x+dx
                        if 0<=ny<h and 0<=nx<w and m[ny,nx] and not seen[ny,nx]:
                            seen[ny,nx]=True; q.append((ny,nx))
                comps.append((len(px),touch,px))
    comps.sort(key=lambda c:-c[0]); rm=0
    for n,t,px in comps[1:]:
        if t:
            for y,x in px: a[y,x,3]=0
            rm+=n
    return Image.fromarray(a), rm

def foot_ref(im, frac=0.04):
    """발 기준점 = 실루엣 **최하단 12%** 영역의 (가로중심, 최하단y).

    ⚠️frac 은 0.04(4%)다. **0.12로 잡았다가 틀렸다**(2026-08-24 상훈님 지적 "흑연 8번째는 확실하게 발이 움직여").
      12%는 발뿐 아니라 **옷자락과 팔까지** 포함하는데, 흑연 기모노는 옷자락이 발보다 넓다.
      실측: 흑연 훈련 쌍의 발은 실제로 14px 어긋나 있었는데, 12% 영역은 좌우가 우연히 상쇄돼
      **차이 0.0px**로 나왔다(좌우끝 111~218 vs 125~204 — 폭이 다른데 중심만 같았다).
      4%면 같은 쌍이 -14.0px로 제대로 잡힌다.
    ★왜 (2026-08-24 상훈님 판정 "앵커가 너무 많이 흔들려")
      겹침(IoU) 탐색은 실루엣 전체를 맞추므로, **커지는 기호와 퍼지는 머리카락**에 끌려다닌다.
      상훈님이 짚으신 흔들리는 칸(배고픔·쓰다듬·졸림)이 전부 '공중에 뜬 기호가 커지는 상태'였고,
      기호가 캐릭터에 닿으면 한 덩어리가 돼 캐릭터만 고르는 것도 소용없었다.
      여울은 머리카락이 잘려 덩어리가 11~16개라 '가장 큰 덩어리'조차 흔들렸다.
    → **발은 안 움직이도록 설계된 부분**이라 기준점으로 이상적이다. 기호는 공중, 머리카락은 위에 있어
      최하단 12%에는 들어오지 않는다.
    실측: 겹침 탐색 발끝 11~17px → 발 기준 **0px**(3캐릭터 전부), 가로도 1px.

    ★꼬리는 발이 아니다 (2026-08-25 상훈님 지적 "1프레임은 꼬리가 있고 2프레임은 꼬리가 없어.
      그래서 뭔가 발로 제대로 인지가 안됐고 그래서 맞췄는데도 발이 흔들렸던 것 같아")
      고양이·다람쥐처럼 꼬리가 바닥까지 닿는 캐릭터는 **최하단 띠에 꼬리 끝이 함께 들어온다.**
      좌우 끝의 중점을 쓰므로 꼬리가 한쪽에 있으면 기준점이 통째로 그쪽으로 밀린다.
      실측(김애용 우리시드 r1 기본 쌍): 아래 4% 띠의 덩어리가 **3개**였다 —
        왼발 186px · 오른발 188px · **꼬리 끝 7px**(f01) / 19px(f02).
      발은 거의 안 움직였는데(0.3px·1.1px) 꼬리만 달라져 기준점이 흔들렸다.
      → 최하단 띠의 연결 덩어리 중 **가장 큰 것의 25% 이상**만 발로 본다. 발 186~210px 대
        꼬리 7~19px 이라 확실히 갈린다.
    """
    b = mk_char(im)
    ys, xs = np.nonzero(b)
    if len(ys) == 0:
        return 0.0, 0.0
    h = ys.max() - ys.min()
    fm = b.copy(); fm[:int(ys.max() - h * frac), :] = False
    if fm.any():
        lab, n = ndimage.label(fm)
        if n > 1:
            sizes = ndimage.sum(fm, lab, range(1, n + 1))
            keep = np.nonzero(sizes >= sizes.max() * 0.25)[0] + 1
            fm = np.isin(lab, keep)
    fy, fx = np.nonzero(fm)
    if len(fx) == 0:
        return (xs.min() + xs.max()) / 2, float(ys.max())
    return (fx.min() + fx.max()) / 2, float(ys.max())


def mk(im): return np.array(im)[:,:,3]>8

def mk_char(im):
    """정렬 기준 마스크 — **캐릭터만** 본다(가장 큰 연결 덩어리).

    ★왜 (2026-08-25 상훈님 지적 "좌우 앵커가 엄청 흔들려")
      알파 전체로 겹침을 맞추면 **기호까지 맞추려 든다.** 기호는 쌍의 두 번째 칸에서 커지도록
      설계돼 있어서, 커진 기호를 맞추려고 정렬이 캐릭터를 반대쪽으로 밀어버린다.
      실측(여울 v06): 가로 흔들림이 정렬 전 33.5px → 정렬 후 33.0px 로 **거의 안 줄었고**,
      튀는 칸은 6·10·14번(배고픔2·쓰다듬2·졸림2)으로 전부 기호가 커지는 프레임이었다.
      2026-08-24에 bbox 중심 정렬이 같은 병(팔이 움직이면 폭이 변해 중심 오판)으로
      8개 중 6개를 악화시켜 겹침 방식으로 바꿨는데, 기호 때문에 그 병이 되살아난 것이다.
    ⚠️기호가 캐릭터에 닿아 한 덩어리가 되면 못 가른다 — 그때는 예전과 같아진다."""
    m = np.array(im)[:,:,3]>8
    lab, n = ndimage.label(m)
    if n <= 1:
        return m
    sizes = ndimage.sum(m, lab, range(1, n+1))
    return lab == int(np.argmax(sizes)) + 1
def shift(m,dx,dy):
    h,w=m.shape; o=np.zeros_like(m)
    ys=slice(max(0,dy),min(h,h+dy)); xs=slice(max(0,dx),min(w,w+dx))
    yt=slice(max(0,-dy),min(h,h-dy)); xt=slice(max(0,-dx),min(w,w-dx))
    o[ys,xs]=m[yt,xt]; return o
def best(a,b,rng=25):
    r=(-1,0,0)
    for dy in range(-rng,rng+1):
        for dx in range(-rng,rng+1):
            S=shift(b,dx,dy); u=(a|S).sum()
            v=(a&S).sum()/u if u else 0
            if v>r[0]: r=(v,dx,dy)
    return r
def move(im,dx,dy):
    o=Image.new("RGBA",im.size,(0,0,0,0)); o.paste(im,(dx,dy)); return o

def save_transparent_gif(frames, path, duration):
    """RGBA 프레임을 배경 없는(투명) GIF로 저장한다.

    ★왜 필요한가 — 2026-08-24 v01에서 순차 GIF를 convert("RGB")로 저장했더니
      알파가 버려지면서 **키잉 전 초록 배경이 통째로 되살아났다**(좌상단 픽셀 8,248,13 실측).
      초록을 지우는 게 이 파이프라인의 핵심인데 마지막 한 줄에서 되돌리고 있었다.
    투명 인덱스는 255로 고정하고 disposal=2(배경 복원)를 준다 — 안 주면 프레임이 겹쳐 잔상이 남는다.
    """
    out = []
    for f in frames:
        a = np.array(f.convert("RGBA"))
        opaque = a[:, :, 3] > 8
        a[~opaque, :3] = 255           # 투명 자리를 흰색으로 눕혀 팔레트를 초록에 낭비하지 않게
        pal = Image.fromarray(a[:, :, :3]).convert("P", palette=Image.ADAPTIVE, colors=255)
        pal.paste(255, mask=Image.fromarray((~opaque).astype(np.uint8) * 255))
        out.append(pal)
    out[0].save(path, save_all=True, append_images=out[1:], duration=duration,
                loop=0, transparency=255, disposal=2)


def main(grid, cols=4, rows=4):
    """격자를 잘라 정렬하고 GIF까지 만든다.

    ★cols/rows 를 인자로 뺐다 (2026-08-25) — 상훈님이 "4x4 말고 다른 형식이 가능한지" 물으셔서
      9종 18칸(6열x3행)을 시험하게 됐다. 기본값은 4x4 그대로라 기존 호출은 영향이 없다.
    """
    grid=Path(grid); R=grid.parent
    npairs = cols*rows//2
    im=Image.open(grid).convert("RGB"); W,H=im.size; cw,ch=W/cols,H/rows
    # ★칸 아래로 여유를 두고 자른다 (2026-08-24 상훈님 제안 "마커 아주 조금 아래까지 조금만 더 잘라보는 건")
    #   발이 하단 마커에 거의 닿게 그려지면 균등분할선이 신발 밑창을 스쳐 **발이 조금 잘린다**(여울 실측).
    #   격자 아래에 배경색을 덧대고 칸마다 같은 만큼 더 잘라, 마지막 행도 같은 크기를 유지한다.
    #   위 칸을 침범한 조각은 뒤의 drop_intruders 가 지운다.
    #   ★여유는 넉넉히(12%≈38px) 준다. 실측(여울 실험4h): 1행 발이 칸 경계를 **19px** 넘어가는데
    #     5%(15px)로는 4px가 모자라 신발 밑창이 잘렸다. 넘어가는 양은 판마다 달라 고정값을 크게 잡고,
    #     남는 초록은 어차피 키잉으로 지워지므로 손해가 없다.
    PAD = int(ch * 0.12)
    ext = Image.new("RGB", (W, H + PAD), (0, 255, 0))
    ext.paste(im, (0, 0))
    im = ext
    cut=R/"cut"; cut.mkdir(exist_ok=True)
    cells=[]
    for r in range(rows):
        for c in range(cols):
            x0,y0=int(round(c*cw)),int(round(r*ch))
            box=(x0,y0,x0+int(cw),y0+int(ch)+PAD)
            cells.append(key_green(im.crop(box), int(ch)))
    tot=0
    for i,cl in enumerate(cells):
        cl,rm=drop_intruders(cl); cells[i]=cl; tot+=rm
    print(f"침범 제거 {tot}px")
    # 층1: 쌍 안 — ★가로는 발 기준, 세로는 겹침(2026-08-24)
    #   겹침만 쓰면 **커지는 기호가 실루엣을 한쪽으로 늘려 캐릭터를 반대로 민다.**
    #   실측(흑연 v06): 쓰다듬 두 번째 칸의 발이 22px 왼쪽으로 밀렸다(하트가 커지는 칸).
    #   가로는 애초에 움직이면 안 되는 축이므로 발로 못 박고,
    #   세로만 겹침으로 미세 조정해 '발끝으로 서기' 같은 의도된 움직임을 남긴다.
    for k in range(npairs):
        ax,_ = foot_ref(cells[k*2]); bx,_ = foot_ref(cells[k*2+1])
        dx = int(round(ax-bx))
        tmp = move(cells[k*2+1], dx, 0)
        v,_,dy = best(mk_char(cells[k*2]), mk_char(tmp), 12)
        cells[k*2+1]=move(cells[k*2+1],dx,dy)
        print(f"  {NAMES[k]:6s} 층1 dx={dx:+3d}(발) dy={dy:+3d} 겹침={v:.3f}")
    # 층2: 쌍 사이 — ★겹침 탐색이 아니라 **발 좌표를 직접 맞춘다**(2026-08-24).
    #   탐색 방식은 커지는 기호·퍼지는 머리카락에 끌려다녀 상태마다 앵커가 흔들렸다.
    #   쌍 안(층1)은 겹침을 그대로 둔다 — 거기서는 미세한 움직임이 살아야 하기 때문.
    # ★기준을 첫 칸이 아니라 **16칸 발 좌표의 중앙값**으로 잡는다 (2026-08-25).
    #   그 전에는 cells[0](기본)의 발에 나머지를 맞췄다. 기준 칸 하나가 오염되면 **전부** 끌려간다.
    #   실측(실험6b): 빗자루가 발 띠에 들어온 청소 칸이 +9.4px(김애용)·+5.6px(블룸) 밀렸는데,
    #   중앙값을 쓰면 그런 칸이 하나 튀어도 나머지 14칸이 기준을 지킨다.
    refs = [foot_ref(c) for c in cells]
    rx = float(np.median([r[0] for r in refs]))
    ry = float(np.median([r[1] for r in refs]))
    for k in range(npairs):
        cx, by = refs[k*2]
        dx, dy = int(round(rx-cx)), int(round(ry-by))
        if dx or dy:
            for j in (0,1): cells[k*2+j]=move(cells[k*2+j],dx,dy)
        print(f"  {NAMES[k]:6s} 층2 dx={dx:+3d} dy={dy:+3d} (발 기준·중앙값)")
    for i,cl in enumerate(cells,1): cl.save(cut/f"f{i:02d}.png")
    w,h=cells[0].size
    def chk(w,h,s=16):
        bg=Image.new("RGB",(w,h),(210,214,220)); px=bg.load()
        for y in range(h):
            for x in range(w):
                if ((x//s)+(y//s))%2==0: px[x,y]=(170,176,186)
        return bg
    # 시트 배치 — 쌍 개수에 맞춰 열 수를 고른다(8쌍=4x2, 9쌍=3x3).
    sc = 4 if npairs <= 8 else 3
    sr = (npairs + sc - 1)//sc
    fr=[]
    for ph in (0,1):
        cv=chk(w*sc,h*sr).convert("RGBA")
        for k in range(npairs): cv.alpha_composite(cells[k*2+ph],((k%sc)*w,(k//sc)*h))
        fr.append(cv.convert("RGB"))
    fr[0].save(R/"상태8.gif",save_all=True,append_images=[fr[1]],duration=450,loop=0)
    seq=[]
    for k in range(npairs):
        for _ in range(2): seq += [cells[k*2], cells[k*2+1]]
    save_transparent_gif(seq, R/"순차.gif", 420)
    print("저장:", R/"상태8.gif", "·", R/"순차.gif")

def regen(grid):
    """이미 잘라둔 cut/f01~f16.png에서 GIF만 다시 만든다(정렬 재계산 없이).
    저장 방식만 바뀌었을 때 격자를 다시 돌리지 않으려고 둔다."""
    R = Path(grid).parent; cut = R/"cut"
    cells = [Image.open(cut/f"f{i:02d}.png").convert("RGBA") for i in range(1, 17)]
    w, h = cells[0].size
    def chk(w,h,s=16):
        bg=Image.new("RGB",(w,h),(210,214,220)); px=bg.load()
        for y in range(h):
            for x in range(w):
                if ((x//s)+(y//s))%2==0: px[x,y]=(170,176,186)
        return bg
    # 시트 배치 — 쌍 개수에 맞춰 열 수를 고른다(8쌍=4x2, 9쌍=3x3).
    sc = 4 if npairs <= 8 else 3
    sr = (npairs + sc - 1)//sc
    fr=[]
    for ph in (0,1):
        cv=chk(w*sc,h*sr).convert("RGBA")
        for k in range(npairs): cv.alpha_composite(cells[k*2+ph],((k%sc)*w,(k//sc)*h))
        fr.append(cv.convert("RGB"))
    fr[0].save(R/"상태8.gif",save_all=True,append_images=[fr[1]],duration=450,loop=0)
    seq=[]
    for k in range(npairs):
        for _ in range(2): seq += [cells[k*2], cells[k*2+1]]
    save_transparent_gif(seq, R/"순차.gif", 420)
    print("재생성:", R/"상태8.gif", "·", R/"순차.gif")


if __name__=="__main__":
    if len(sys.argv) > 2 and sys.argv[2] == "--from-cut":
        regen(sys.argv[1])
    elif len(sys.argv) > 2 and sys.argv[2].startswith("--grid"):
        c, r = (sys.argv[3] if sys.argv[2]=="--grid" else sys.argv[2].split("=",1)[1]).split("x")
        main(sys.argv[1], int(c), int(r))
    else:
        main(sys.argv[1])
