"""Custom-only canvas, reusing the canonical keying/alignment and default-v1 untouched path.
All sixteen frames receive the same normalization affine and final canvas translation.
No content is clipped to make a sample pass. Metadata attests loss at both crop stages.
"""
from pathlib import Path
import argparse, hashlib, json, math, shutil, sys
import numpy as np
from scipy import ndimage
from PIL import Image
import anchors, state8_v5, service_post, gif_out
import standing_scale

MARGIN=16

def build(grid, output, keys, postures, base_anchors, motion_plan=None):
    out=Path(output);out.mkdir(parents=True,exist_ok=True)
    base_path=Path(base_anchors);base_bytes=base_path.read_bytes();base=json.loads(base_bytes)
    assert len(keys)==8 and len(set(keys))==8 and 'base'not in keys
    assert base['K']>0 and base['Hw']>0 and base['canvas']['w']>0 and base['canvas']['h']>0
    work=out/'_custom_work';work.mkdir(exist_ok=False)
    crop={};membership=[]
    def own_cell(cell, own, frame):
        pixels=np.array(cell);mask=pixels[:,:,3]>0
        labels,count=ndimage.label(mask,structure=np.ones((3,3),dtype=int))
        inside=np.zeros(mask.shape,dtype=bool);inside[own[1]:own[3],own[0]:own[2]]=True
        removed=[];before=int(mask.sum())
        for label in range(1,count+1):
            component=labels==label
            if (component & inside).any():continue
            yy,xx=np.where(component)
            removed.append({"bbox":[int(xx.min()),int(yy.min()),int(xx.max()+1),int(yy.max()+1)],"pixels":int(component.sum()),"reason":"DISCONNECTED_OUTSIDE_OWN_CELL"})
            pixels[component]=0
        after=int((pixels[:,:,3]>0).sum())
        assert before-after==sum(c["pixels"]for c in removed)
        membership.append({"frame":frame,"ownCell":list(own),"componentsRemoved":removed,"removedAlphaPixels":before-after,"retainedOwnCellAlphaPixels":int((mask & inside).sum())})
        return Image.fromarray(pixels)
    def full_box(cells, original):
        boxes=[im.getchannel('A').getbbox()for im in cells]
        assert len(boxes)==16 and all(boxes)
        left=min(original[0],min(b[0]for b in boxes)-MARGIN)
        top=min(original[1],min(b[1]for b in boxes)-MARGIN)
        right=max(original[2],max(b[2]for b in boxes)+MARGIN)
        bottom=max(original[3],max(b[3]for b in boxes)+MARGIN)
        box=(left,top,right,bottom)
        assert all(0<=left<right<=im.width and 0<=top<bottom<=im.height for im in cells),'Alignment buffer exhausted'
        before=[int((np.array(im)[:,:,3]>0).sum())for im in cells]
        after=[int((np.array(im.crop(box))[:,:,3]>0).sum())for im in cells]
        assert before==after,'CUSTOM_CROP_ALPHA_LOSS'
        crop.update(originalBox=list(original),expandedBox=list(box),alphaPixelsBefore=before,alphaPixelsAfter=after,lostAlphaPixels=[a-b for a,b in zip(before,after)])
        return box
    pmap=service_post.resolve_postures(keys,postures)
    state8_v5.main(str(grid),str(work),postures=pmap,crop_box=full_box,cell_filter=own_cell)
    frames=[Image.open(work/'frames'/f'f{i:02d}.png').convert('RGBA')for i in range(1,17)]
    assert all(im.size==frames[0].size for im in frames)
    standing=[i for i in range(8)if pmap[state8_v5.KEYS[i]]=='standing']
    assert standing,'At least one standing pose required to preserve base head scale'
    normalization=None
    if motion_plan is not None:
        plan=json.loads(Path(motion_plan).read_text());plan=plan.get('motionPlan',plan);motions=plan['motions']
        try:normalization=standing_scale.select(frames,motions,keys,base,plan)
        except standing_scale.MeasurementAmbiguous as error:
            (out/'standing-measurement.json').write_text(json.dumps(error.report,ensure_ascii=False,indent=2)+'\n')
            print('CUSTOM_MEASUREMENT_AMBIGUOUS '+json.dumps(error.report,ensure_ascii=False));raise
        source_hw=normalization['sourceHeadWidthProxy'];scale=normalization['scale']
        measured=[{'foot_y':normalization['standingFootSource']}]
        (out/'standing-measurement.json').write_text(json.dumps(normalization,ensure_ascii=False,indent=2)+'\n')
    else:
        measured=[service_post.measure_body(frames[2*i])for i in standing]
        assert all(measured),'Standing body measurement required'
        source_hw=float(np.median([m['hw']for m in measured]));scale=base['Hw']/source_hw
    assert math.isfinite(scale)and .25<=scale<=4
    ref_hw,foot=service_post.read_norm_ref(base_path)
    assert ref_hw==base['Hw']and foot is not None
    base_w,base_h=base['canvas']['w'],base['canvas']['h']
    target_foot=base_h-foot[1]if foot[0]=='bottom'else foot[1]
    # Shared pivot: original slot centre and median standing foot. Never fit each motion separately.
    origin=crop['originalBox'];expanded=crop['expandedBox']
    pivot_x=(origin[0]+origin[2])/2-expanded[0]
    pivot_y=float(np.median([m['foot_y']for m in measured]))
    dx=round(base_w/2-pivot_x*scale);dy=round(target_foot-pivot_y*scale)
    resized=[im.resize((max(1,round(im.width*scale)),max(1,round(im.height*scale))),Image.Resampling.LANCZOS)for im in frames]
    boxes=[im.getchannel('A').getbbox()for im in resized];assert all(boxes)
    left=min(0,min(b[0]+dx for b in boxes)-MARGIN);top=min(0,min(b[1]+dy for b in boxes)-MARGIN)
    right=max(base_w,max(b[2]+dx for b in boxes)+MARGIN);bottom=max(base_h,max(b[3]+dy for b in boxes)+MARGIN)
    width,height=right-left,bottom-top;assert 16<=width<=4096 and 16<=height<=4096
    tx,ty=-left,-top;final=[];checks=[]
    for i,im in enumerate(resized,1):
        placed=Image.new('RGBA',(width,height),(0,0,0,0));placed.paste(im,(dx+tx,dy+ty))
        # Paste without mask preserves original straight RGBA, including partial alpha exactly.
        src=np.array(im);dst=np.array(placed);before=int((src[:,:,3]>0).sum());after=int((dst[:,:,3]>0).sum())
        assert before==after,'CUSTOM_NORMALIZE_ALPHA_LOSS'
        box=im.getchannel('A').getbbox();crop_after=placed.crop((dx+tx,dy+ty,dx+tx+im.width,dy+ty+im.height))
        assert (np.array(crop_after)[src[:,:,3]>0]==src[src[:,:,3]>0]).all(),'CUSTOM_AFFINE_CONTENT_CHANGED'
        edge=service_post.edge_contact(dst[:,:,3]);assert not any(edge.get(k,0)>=service_post.CLIP_MIN[k]for k in service_post.CLIP_MIN),'CUSTOM_EDGE_CLIP'
        checks.append({'frame':i,'alphaPixelsBeforePlacement':before,'alphaPixelsAfterPlacement':after,'lostAlphaPixels':before-after,'edgeContact':edge,'contentPreserved':True,'bbox':list(placed.getchannel('A').getbbox())})
        final.append(placed)
    first={}
    for i,key in enumerate(keys):
        a,b=final[2*i:2*i+2];dst=out/(key+'.webp')
        a.save(dst,save_all=True,append_images=[b],duration=state8_v5.FRAME_MS,loop=0,quality=state8_v5.WEBP_Q)
        actual=Image.open(dst);assert actual.size==(width,height)and actual.n_frames==2
        first[key]=a;gif_out.webp_to_gif(dst)
    data=anchors.build(first,keys,K=base['K'],Hw=base['Hw'],base_key='base')
    data['canvas']={'w':width,'h':height};assert not data.get('missing')
    transform={'version':'CUSTOM_CANVAS_V1','gridSHA256':hashlib.sha256(Path(grid).read_bytes()).hexdigest(),'baseAnchorsSHA256':hashlib.sha256(base_bytes).hexdigest(),'baseCanvas':base['canvas'],'outputCanvas':data['canvas'],'K':base['K'],'Hw':base['Hw'],'sourceHeadWidth':source_hw,'normalizationScale':scale,'baseOffset':{'x':tx,'y':ty},'sharedAffine':{'scale':scale,'dx':dx+tx,'dy':dy+ty},'inputCanvas':{'w':frames[0].width,'h':frames[0].height},'resizedCanvas':{'w':resized[0].width,'h':resized[0].height},'standingFootTarget':target_foot+ty,'standingFootSource':pivot_y,'cellMembership':membership,'crop':crop,'frames':checks,'lostAlphaPixels':sum(crop['lostAlphaPixels'])+sum(c['lostAlphaPixels']for c in checks)}
    if normalization is not None:
        transform['version']='CUSTOM_CANVAS_BODY_V2';transform['bodyNormalization']=normalization
    assert transform['lostAlphaPixels']==0
    data['customTransform']=transform;anchors.write(data,out/'anchors.json');(out/'custom-transform.json').write_text(json.dumps(transform,indent=2)+'\n')
    print(json.dumps({'status':'PASS','canvas':data['canvas'],'K':base['K'],'Hw':base['Hw'],'scale':scale,'sharedAffine':transform['sharedAffine'],'lostAlphaPixels':0,'motions':8}))
    return transform

def main():
    ap=argparse.ArgumentParser();ap.add_argument('grid');ap.add_argument('out');ap.add_argument('--keys',required=True);ap.add_argument('--postures',required=True);ap.add_argument('--base-anchors',required=True);ap.add_argument('--motion-plan');a=ap.parse_args()
    result=service_post.run_gate(Path(a.grid))
    if result:return result
    build(Path(a.grid),Path(a.out),a.keys.split(','),a.postures,Path(a.base_anchors),a.motion_plan);return 0
if __name__=='__main__':sys.exit(main())
