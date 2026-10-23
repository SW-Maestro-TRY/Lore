import json,base64,hmac,hashlib,time,urllib.request,urllib.error,subprocess,concurrent.futures
import os
BASE=os.environ['COMMUNITY_TEST_API'].rstrip('/')+'/api/webtoon/v1'
DB=os.environ['COMMUNITY_TEST_DB']
assert BASE.startswith(('http://localhost:','http://127.0.0.1:')), 'localhost only'
assert DB.startswith('lore_community_'), 'dedicated community test DB only'

def token(user):
 b=lambda x:base64.urlsafe_b64encode(json.dumps(x,separators=(',',':')).encode()).decode().rstrip('=')
 s=b({'alg':'HS256'})+'.'+b({'sub':str(user),'iat':int(time.time()),'exp':int(time.time())+7200})
 return s+'.'+base64.urlsafe_b64encode(hmac.new(b'local-dev-only-secret-change-me-in-production-32bytes',s.encode(),hashlib.sha256).digest()).decode().rstrip('=')
def call(path,user=None,method='GET',data=None,status=200):
 headers={'Content-Type':'application/json'}
 if user: headers['Authorization']='Bearer '+token(user)
 req=urllib.request.Request(BASE+path,data=None if data is None else json.dumps(data).encode(),headers=headers,method=method)
 try:
  with urllib.request.urlopen(req) as r: code=r.status;out=json.load(r)
 except urllib.error.HTTPError as e:code=e.code;out=json.loads(e.read())
 assert code==status,(path,code,status,out)
 return out.get('data',out)
def sql(q):
 return subprocess.check_output(['psql','-h','localhost','-U','mint','-d',DB,'-Atc',q],text=True).strip()
sql("delete from webtoon_community_report where user_id in (91001,91002,91003); delete from webtoon_comment where run_id like 'community-run-%'; delete from webtoon_community_notice where user_id in (91001,91002,91003); delete from webtoon_community_publication where job_id='community-job-2'; update webtoon_work set is_public=false where run_id='community-run-2'; update webtoon_character set community_blocked=false,community_public=false where public_id='community-harin'")
P='/community'
call(P+'/runs/community-run-1/subscription',method='PUT',status=401)
call(P+'/runs/community-run-2/subscription',91002,status=404)
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
 list(pool.map(lambda _:call(P+'/runs/community-run-1/subscription',91002,'PUT'),range(5)))
assert call(P+'/runs/community-run-1/subscription',91002)['subscribers']==1
print('PASS guest/private protection and concurrent subscription deduplication')
call(P+'/runs/community-run-1/comments',91002,'POST',{'body':'  '},400)
call(P+'/runs/community-run-1/comments',91002,'POST',{'body':'x'*1001},400)
c=call(P+'/runs/community-run-1/comments',91002,'POST',{'body':'편지를 기다리는 마음이 느껴져요. 다음 화도 기다릴게요!'})['id']
call(P+'/runs/community-run-1/comments',91002,'POST',{'body':'duplicate'},400)
call(P+f'/comments/{c}',91001,'PUT',{'body':'남의 댓글 수정'},404)
call(P+f'/comments/{c}',91002,'PUT',{'body':'수정한 감상: 다음 화가 기대돼요.'})
assert call(P+'/runs/community-run-1/comments',91002)[0]['body'].startswith('수정한')
call(P+'/runs/community-run-2/comments',91001,status=404)
print('PASS comment length, rate limit, edit ownership and private episode isolation')
notices=call(P+'/notices',91001);assert any(n['kind']=='COMMENT' for n in notices)
call(P+f'/notices/{notices[0]["id"]}/read',91002,'POST')
assert call(P+'/notices',91001)[0]['read_at'] is None
call(P+f'/notices/{notices[0]["id"]}/read',91001,'POST')
assert call(P+'/notices',91001)[0]['read_at'] is not None
print('PASS author comment notice and read ownership')
call(P+'/characters/community-harin/publication',91002,'PUT',{'published':True,'agreed':True},404)
call(P+'/characters/community-harin/publication',91001,'PUT',{'published':True,'agreed':False},400)
call(P+'/characters/community-harin/publication',91001,'PUT',{'published':True,'agreed':True})
assert call(P+'/characters')[0]['id']=='community-harin'
call('/characters/community-harin',91002)
call(P+'/characters/community-harin/publication',91001,'PUT',{'published':False,'agreed':False})
call(P+'/characters/community-harin',status=404)
call('/characters/community-harin',91002,status=404)
call(P+'/characters/community-harin/publication',91001,'PUT',{'published':True,'agreed':True})
print('PASS character ownership, explicit consent, public read and revoke')
# Reuse attribution is captured at generation request, this fixture represents that completed generation.
sql("insert into webtoon_character_use(job_id,character_id,creator_id,character_name) values ('community-job-2','community-harin',91002,'하린') on conflict do nothing")
sql("update webtoon_work set is_public=true where run_id='community-run-2'")
for _ in range(22):
 if any(n['kind']=='EPISODE' for n in call(P+'/notices',91002)):break
 time.sleep(1)
else:raise AssertionError('episode notification not delivered')
notes=call(P+'/notices',91002);assert len([n for n in notes if n['kind']=='EPISODE'])==1
assert len([n for n in notes if n['kind']=='CHARACTER_USE'])==1
assert call(P+'/runs/community-run-2/subscription',91002)['subscribed']
assert call(P+'/runs/community-run-2/subscription',91002)['episode']==2
assert call(P+'/runs/community-run-2/comments',91002)==[]
assert call(P+'/runs/community-run-2/attribution')[0]['character_name']=='하린'
print('PASS series root subscription, separate episode comments, publish notice and provenance')
sql("update webtoon_work set is_public=false where run_id='community-run-2'")
assert not any(n['kind']=='EPISODE' for n in call(P+'/notices',91002))
sql("update webtoon_work set is_public=true where run_id='community-run-2'")
assert len([n for n in call(P+'/notices',91002) if n['kind']=='EPISODE'])==1
call(P+'/reports',91001,'POST',{'kind':'comment','target':str(c),'reason':'댓글 검토 요청'})
call(P+'/reports',91002,status=403)
r=call(P+'/reports',91003)[0]
call(P+f'/reports/{r["id"]}/resolve',91003,'POST',{'hide':True})
assert call(P+'/runs/community-run-1/comments',91002)==[]
call(P+'/reports',91002,'POST',{'kind':'character','target':'community-harin','reason':'캐릭터 검토 요청'})
r=call(P+'/reports',91003)[0];call(P+f'/reports/{r["id"]}/resolve',91003,'POST',{'hide':True})
call(P+'/characters/community-harin/publication',91001,'PUT',{'published':True,'agreed':True},400)
assert call(P+'/characters')==[]
print('PASS withdrawn notice filtering, report admin gate, hide and publication block')
call(P+'/subscriptions/community-job-1',91002,'DELETE')
assert not call(P+'/runs/community-run-1/subscription',91002)['subscribed']
# Restore fixture for browser checks only.
sql("update webtoon_character set community_blocked=false,community_public=true where public_id='community-harin'; update webtoon_comment set hidden=false where id="+str(c))
print('ALL API CHECKS PASS')
