"""Conservative upright silhouette proxy. No anatomical segmentation or colour assumptions.
Text evidence excludes non-neutral poses; pixel evidence rejects unstable/oversized silhouettes.
Props are never removed from the rendered images. This module only chooses a measuring reference.
"""
import re, math, hashlib, json
import numpy as np
import service_post

class MeasurementAmbiguous(ValueError):
    pass

def select(frames, motions, keys, base, plan):
    motions=sorted(motions,key=lambda m:m["order"])
    decisions=[];eligible=[]
    def fail(reason):
        error=MeasurementAmbiguous("CUSTOM_MEASUREMENT_AMBIGUOUS: "+reason);error.report={"status":"REVIEW_REQUIRED","version":"UPRIGHT_SILHOUETTE_PROXY_V1","characterOnlySegmentation":False,"decisions":decisions};raise error
    for motion in motions:
        order=motion['order'];pose=motion['pose'];text=pose.lower();reason=None
        if not text.startswith('standing:'):reason='NOT_STANDING'
        elif re.search(r'lean|bend|jump|airborne|lifted|tiptoe|off.{0,8}ground|기울|점프|뛰|발.{0,12}들',text):reason='NON_UPRIGHT_OR_UNGROUNDED'
        elif re.search(r'(rais|reach).{0,35}(sky|overhead|above.{0,8}head)|머리.{0,12}위|하늘',text):reason='OVERHEAD_POSE'
        elif re.search(r'desk|table|telescope|tripod|sofa|blanket|책상|탁자|망원경|삼각대|소파|담요|창가',text):reason='SUPPORT_OR_ATTACHED_PROP_AMBIGUITY'
        props=motion.get('props',[])
        if not reason and any(re.search(r'desk|table|telescope|tripod|sofa|blanket|책상|탁자|망원경|삼각대|소파|담요',str(p).lower())for p in props):reason='LARGE_ATTACHED_PROP'
        d={'order':order,'slot':keys[order-1],'pose':pose,'propsCount':len(props),'excludedReason':reason}
        decisions.append(d)
        if not reason:eligible.append((len(props),order,d))
    if not eligible:fail('no neutral upright plan reference')
    # Prefer explicit prop-free references. Fallback references remain labeled silhouette proxies.
    least=min(p for p,_,_ in eligible);preferred=[(o,d)for p,o,d in eligible if p==least]
    measured=[]
    for order,d in preferred:
        pair=[]
        for i in (2*(order-1),2*(order-1)+1):
            m=service_post.measure_body(frames[i])
            if not m or not m['hw'] or m['h']/m['hw']<1.15:break
            pair.append({'frame':i+1,'height':m['h'],'headWidthProxy':m['hw'],'headTop':m['y0'],'footY':m['foot_y'],'bbox':[m['x0'],m['y0'],m['x1']+1,m['foot_y']]})
        if len(pair)!=2 or abs(pair[0]['height']-pair[1]['height'])/max(p['height']for p in pair)>.08:
            d['excludedReason']='UNSTABLE_OR_WIDE_SILHOUETTE';continue
        d['selected']=True;d['measurements']=pair;measured.extend(pair)
    if not measured:fail('upright silhouette measurement unstable')
    heights=[m['height']for m in measured]
    if (max(heights)-min(heights))/max(heights)>.10:fail('upright references disagree')
    h=float(np.median([m['height']for m in measured]));hw=float(np.median([m['headWidthProxy']for m in measured]));scale=float(base['K'])/h
    if not math.isfinite(scale)or not .25<=scale<=4:fail('unreasonable shared scale')
    return {'version':'UPRIGHT_SILHOUETTE_PROXY_V1','motionPlanSHA256':hashlib.sha256(json.dumps(plan,ensure_ascii=False,separators=(',',':')).encode()).hexdigest(),'targetHeight':base['K'],'sourceHeight':h,'sourceHeadWidthProxy':hw,'scale':scale,'selectedOrders':[d['order']for d in decisions if d.get('selected')],'selectedFrames':[m['frame']for m in measured],'standingFootSource':float(np.median([m['footY']for m in measured])),'measuredFrames':measured,'decisions':decisions,'characterOnlySegmentation':False,'normalizedHeadWidthProxy':hw*scale,'seedHwDiagnosticRatio':hw*scale/base['Hw'],'diagnosticLimit':'Hw/head-band are silhouette proxies; uniform resizing cannot correct intrinsic head/body proportion drift.'}
