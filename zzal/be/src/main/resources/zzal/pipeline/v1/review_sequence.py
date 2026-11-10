"""Optional operator PNG sequence. Never synthesizes a missing B frame or duration."""
from pathlib import Path
from PIL import Image
import hashlib,json,sys
MAX_SIDE=2048
MAX_PNG_BYTES=8*1024*1024
MAX_TOTAL_BYTES=64*1024*1024

def export(config,output):
    cfg=json.loads(Path(config).read_text());out=Path(output);out.mkdir(parents=True,exist_ok=False)
    assert len(cfg['sources'])==16,'SOURCE_COUNT'
    frames=[];total=0
    for order,source in enumerate(cfg['sources']):
        path=Path(source['path']);source_sha=hashlib.sha256(path.read_bytes()).hexdigest()
        image=Image.open(path);assert image.format=='WEBP'and image.n_frames==2,'FRAME_COUNT'
        assert 16<=image.width<=MAX_SIDE and 16<=image.height<=MAX_SIDE,'LIMIT_EXCEEDED'
        for ab in range(2):
            image.seek(ab);rgba=image.convert('RGBA');duration=image.info.get('duration')
            assert isinstance(duration,int)and 1<=duration<=60000,'DURATION_INVALID'
            bounds=rgba.getchannel('A').getbbox();assert bounds,'EMPTY_FRAME'
            index=2*order+ab;filename='frame-%02d.png'%(index+1);png=out/filename;rgba.save(png,format='PNG',compress_level=9,optimize=False)
            assert Image.open(png).convert('RGBA').tobytes()==rgba.tobytes(),'RGBA_CHANGED'
            data=png.read_bytes();total+=len(data);assert len(data)<=MAX_PNG_BYTES and total<=MAX_TOTAL_BYTES,'LIMIT_EXCEEDED'
            frames.append({'index':index,'group':source['group'],'motionOrder':source['motionOrder'],'slot':source['slot'],'frameIndex':ab,'imageKey':cfg['reviewPrefix']+'/'+filename,'sha256':hashlib.sha256(data).hexdigest(),'sourceImageKey':source['imageKey'],'sourceSha256':source_sha,'durationMs':duration,'width':rgba.width,'height':rgba.height,'alphaBounds':list(bounds),'rgbaSHA256':hashlib.sha256(rgba.tobytes()).hexdigest()})
    manifest={'version':'CUSTOM_SEQUENCE_V1','setId':cfg['setId'],'baseRound':cfg['baseRound'],'K':cfg['K'],'Hw':cfg['Hw'],'baseAnchorsKey':cfg['baseAnchorsKey'],'baseAnchorsSHA256':cfg['baseAnchorsSHA256'],'customAnchorsKey':cfg['customAnchorsKey'],'customAnchorsSHA256':cfg['customAnchorsSHA256'],'alphaBoundsThreshold':0,'frames':frames}
    (out/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,separators=(',',':'))+'\n')
    print(json.dumps({'status':'READY','frames':len(frames),'pngBytes':total}))
if __name__=='__main__':export(sys.argv[1],sys.argv[2])
