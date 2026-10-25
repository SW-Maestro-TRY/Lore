import {test,expect, type Page} from '@playwright/test';
import {readFileSync, mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
const base=resolve(__dirname,'../../..');
const ok=(data:unknown)=>({success:true,data,error:null});
const chapter=(ep:number)=>({episode:ep,run_id:`ep${ep}`,job_id:`job${ep}`,status:'DONE',title:ep===1?'다리 너머의 약속':'조금 일찍 출발한 날'});
async function stub(page:Page,count=1,authenticated=true) {
 let submitted:any=null;
 let draft:any={episode:count+1,fixed_settings:'민수는 높은 곳을 무서워한다. 지수와는 오랜 친구다.\n지수가 감춘 약속은 아직 공개하지 않는다.',progress_state:`현재 시점: ${count}화 종료\n민수는 다리를 피해 돌아가느라 약속에 늦었다. 지수는 이유를 아직 모른다.`,story:'',revision:0,previous_state:'',source_scenes:['민수는 다리 앞에서 멈춘다.','먼 길로 돌아온 민수가 지수에게 사과한다.'],source_run:`ep${count}`,title:'다리 너머의 약속',credit_cost:12,balance:48,blocked:null,entries:[],progress:[],digest:{status:'NONE'}};
 // 앞 화 정리(모델 호출)는 여기서 흉내만 낸다 — 정리를 시키면 다음 조회에서 정리본으로 채워진다.
 const digested={fixed_settings:'',progress_state:'',
  entries:[{id:'L0001',title:'다리',kind:'place',content:'민수가 피해 가는 강 위의 다리',keys:['다리'],always:false,enabled:true,new:true},
           {id:'R001',title:'지수의 약속',kind:'rule',content:'지수가 감춘 약속은 아직 공개하지 않는다',keys:[],always:true,enabled:true,new:true}],
  progress:[{key:'P001',title:'시점',text:`${count}화가 끝난 저녁, 민수는 지수에게 사과했다.`},{key:'L0001',title:'다리',text:'민수는 이제 다리 앞에서 멈추지 않는다'}]};
 const calls:string[]=[];
 await page.addInitScript(()=>{localStorage.setItem('lore_uid','series-test-browser');localStorage.setItem('lore_my_runs','["ep1","ep2"]');});
 await page.route('**/api/**',async route=>{
  const req=route.request(),path=new URL(req.url()).pathname;calls.push(req.method()+' '+path);
  const send=(body:unknown,status=200)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
  if(path==='/api/v1/users/me') return send(authenticated?ok({userId:7,email:'writer@example.test',role:'USER',createdAt:'2026-01-01T00:00:00Z'}):{error:'로그인 필요'},authenticated?200:401);
  if(path.includes('/auth/refresh')) return send({error:'로그인 필요'},401);
  if(path==='/api/v1/credits/me') return send(ok({balance:48}));
  if(path.endsWith('/my/link')) return send(ok({linked:true}));
  if(path.endsWith('/my/runs')) return send(ok([]));
  if(path.endsWith('/my/trash')) return send(ok({keepDays:30,runs:[]}));
  if(path.endsWith('/my/notify-setting')) return send(ok({on:true}));
  if(path.endsWith('/my/feedback')) return send(ok({done:true,reward:0}));
  if(path.endsWith('/jobs/mine')) return send({jobs:[],cards:[]});
  if(path.endsWith('/allowance')) return send({logged_in:true,balance:48,credit_cost:12,qualities:[{key:'wave',label:'파도',credits:12}]});
  if(path.endsWith('/runs')) return send({runs:[]});
  if(path.endsWith('/liked')) return send(ok([]));
  if(path.endsWith('/subscription')) return send(ok({subscribed:false,subscribers:0,episode:1}));
  if(path.endsWith('/attribution')) return send(ok([]));
  if(/\/runs\/ep\d\/result$/.test(path)) {
   const ep=Number(path.match(/ep(\d)/)![1]);
   return send({run_id:`ep${ep}`,character:'민수',title:chapter(ep).title,genre:'일상',style_label:'로맨스',episode:ep,pages:[{no:1,gap:0,width:1,caption:'다리 앞에서 망설이는 민수'}],page_count:1,planned_pages:1,preview:false,example:false});
  }
  if(path.includes('/page/')) return route.fulfill({status:200,contentType:'image/jpeg',body:readFileSync(resolve(base,'webtoon/fe/static/entry/webtoon-page.jpg'))});
  if(/\/series\/ep\d$/.test(path)) return send({episode:Number(path.match(/ep(\d)/)![1]),chapters:Array.from({length:count},(_,i)=>chapter(i+1))});
  if(path.endsWith('/digest')) {draft={...draft,digest:{status:'RUNNING'}};return send(draft.digest);}
  if(path.endsWith('/draft')) {
   if(req.method()==='POST') {draft={...draft,...req.postDataJSON(),revision:draft.revision+1,digest:{status:'SAVED'}};return send({revision:draft.revision});}
   if(draft.digest.status==='RUNNING') draft={...draft,...digested,digest:{status:'DONE'}};
   return send(draft);
  }
  if(path.endsWith('/next')) {submitted=req.postDataJSON();return send({id:'job-next'});}
  if(path.endsWith('/jobs/job-next')) return send({id:'job-next',status:'queued',stage:'story',stage_label:'이야기 짓기',say:'준비 중',pct:0,log:[],queue:{ahead:0,minutes:1,line:'곧 시작해요'},elapsed:0,art:null});
  return send(ok([]));
 });
 return {calls,get submitted(){return submitted;}};
}
// 다음 화 이어 만들기는 창이 아니라 페이지다(?view=next&run=앞 화).
async function openForm(page:Page) {
 await page.goto('/webtoon?run=ep1');
 // next dev 에만 뜨는 「DEV 화면」 단추가 폰 너비에서 아래 단추를 가린다(배포 빌드에는 없다).
 await page.addStyleTag({content:'.wt-devnav{display:none}'});
 await page.getByRole('button',{name:'다음 편 만들기',exact:true}).filter({visible:true}).first().click();
 await expect(page).toHaveURL(/view=next&run=ep1/);
 await expect(page.getByLabel('이번 화에서 일어날 일',{exact:true})).toBeVisible();
 await expect(page.getByLabel('이번 화에서 일어날 일',{exact:true})).toBeEnabled({timeout:15000});
}
test('다음 화 버튼 → 앞 화 정리로 칸이 채워짐 → 항목 빼기 → 이번 화만 적고 생성',async({page})=>{
 const server=await stub(page);
 await openForm(page);
 // 개발 모드는 effect 를 두 번 돌린다. 서버의 정리 시작은 같은 앞 화에 한 번만 돈다(SeriesDigestService.start).
 expect(server.calls.filter(x=>x.endsWith('/series/ep1/digest')).length).toBeGreaterThan(0);
 // 진행 상태와 설정집 항목은 접혀 있다. 펼쳐서 본다. 만들기 단추 안에 크레딧이 들어 있다.
 await expect(page.getByText('1화가 끝난 저녁, 민수는 지수에게 사과했다.')).toBeHidden();
 await page.getByText('현재 진행 상태',{exact:true}).click();
 await expect(page.getByText('1화가 끝난 저녁, 민수는 지수에게 사과했다.')).toBeVisible();
 await expect(page.getByRole('button',{name:/^2화 만들기/})).toContainText('-12 크레딧');
 await expect(page.getByText('사용하는 캐릭터·설정·이야기의 권리를 확인했어요.')).toHaveCount(0);
 // 항목은 한 목록에 접혀 있다(수십 개가 될 수 있어서). 펼쳐서 본다. 항상 참고 항목은 표시가 붙는다.
 await page.getByText('설정집 항목',{exact:true}).click();
 await expect(page.getByText('지수가 감춘 약속은 아직 공개하지 않는다')).toBeVisible();
 await expect(page.getByText('항상 참고',{exact:true}).first()).toBeVisible();
 await expect(page.getByText('민수가 피해 가는 강 위의 다리')).toBeVisible();
 // 이름 고치기 — Escape 는 고치기만 취소하고 창은 그대로, 확인하면 새 이름이 키워드에도 들어간다.
 await page.getByRole('button',{name:'지수의 약속 이름 고치기',exact:true}).click();
 await page.getByLabel('지수의 약속 새 이름',{exact:true}).press('Escape');
 await expect(page.getByLabel('이번 화에서 일어날 일',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:'지수의 약속 이름 고치기',exact:true}).click();
 await page.getByLabel('지수의 약속 새 이름',{exact:true}).fill('지수의 비밀 약속');
 await page.getByRole('button',{name:'확인',exact:true}).click();
 await expect(page.getByText('지수의 비밀 약속',{exact:true})).toBeVisible();
 // 항목을 빼면 그 항목을 덮어쓰던 진행 상태 줄도 같이 빠진다.
 await page.getByRole('button',{name:'다리 빼기',exact:true}).last().click();
 await expect(page.getByText('민수가 피해 가는 강 위의 다리')).toHaveCount(0);
 await expect(page.getByText('민수는 이제 다리 앞에서 멈추지 않는다')).toHaveCount(0);
 await page.getByLabel('이번 화에서 일어날 일',{exact:true}).fill('민수는 우회로를 미리 확인하고 일찍 출발한다.');
 await page.getByRole('button',{name:/^2화 만들기/}).click();
 await expect(page).toHaveURL(/view=running&job=job-next/);
 expect(server.submitted.entries.map((e:any)=>e.title)).toEqual(['지수의 비밀 약속']);
 expect(server.submitted.entries[0].keys).toEqual(['지수의 비밀 약속']);
 expect(server.submitted.progress.map((p:any)=>p.key)).toEqual(['P001']);
});
test('정리본을 고쳐 쓰기 → 무료 초안 저장·복원 → 생성',async({page},info)=>{
 const server=await stub(page);
 await openForm(page);
 await page.getByText('더 적어 둘 설정 · 선택',{exact:true}).click();
 await page.getByLabel('고정 설정',{exact:true}).fill('민수는 고소공포증이 있다. 비밀 약속은 공개하지 않는다.');
 await page.getByText('현재 진행 상태',{exact:true}).click();
 // 진행 상태 줄을 다 빼면 글 칸이 나온다(정리가 실패했을 때와 같은 칸).
 await page.getByRole('button',{name:'시점 빼기',exact:true}).click();
 await page.getByRole('button',{name:'다리 빼기',exact:true}).last().click();
 await page.getByLabel('현재 진행 상태',{exact:true}).fill('다음 날 아침. 민수는 지수에게 사과했고, 지수도 늦은 이유를 알게 됐다.');
 await page.getByLabel('이번 화에서 일어날 일',{exact:true}).fill('민수는 지난번 일을 기억하고 우회로로 일찍 출발한다.');
 // 권리 확인 칸이 없다 — 1화에서 받은 확인을 잇는다. 이번 화와 진행 상태가 있으면 바로 만들 수 있다.
 await expect(page.getByRole('button',{name:/^2화 만들기/})).toBeEnabled();
 await page.getByRole('button',{name:'초안 저장',exact:true}).click();
 await expect(page.getByRole('status').filter({hasText:'초안을 저장했어요'})).toBeVisible();
 expect(server.submitted).toBeNull();
 // 돌아갔다가 다시 들어오면 저장한 초안이 그대로다.
 await page.getByRole('button',{name:/돌아가기/}).click();
 await expect(page).toHaveURL(/run=ep1/);await expect(page).not.toHaveURL(/view=next/);
 await page.getByRole('button',{name:'다음 편 만들기',exact:true}).filter({visible:true}).first().click();
 await page.getByText('현재 진행 상태',{exact:true}).click();
 await expect(page.getByLabel('현재 진행 상태',{exact:true})).toHaveValue('다음 날 아침. 민수는 지수에게 사과했고, 지수도 늦은 이유를 알게 됐다.');
 await page.getByLabel('이번 화에서 일어날 일',{exact:true}).fill('민수는 우회로를 미리 확인하고 일찍 출발한다.');
 await page.evaluate(()=>window.scrollTo(0,0));
 mkdirSync(resolve(base,'.impeccable/review'),{recursive:true});
 await page.screenshot({path:resolve(base,`.impeccable/review/${info.project.name}.png`),fullPage:true});
 await page.getByRole('button',{name:/^2화 만들기/}).scrollIntoViewIfNeeded();
 await page.screenshot({path:resolve(base,`.impeccable/review/${info.project.name}-actions.png`),fullPage:true});
 await page.getByRole('button',{name:/^2화 만들기/}).click();
 await expect(page).toHaveURL(/view=running&job=job-next/);
 expect(server.submitted.fixed_settings).toContain('고소공포증');
 expect(server.submitted.progress_state).toContain('지수도 늦은 이유');
 expect(server.submitted.story).toContain('우회로를 미리');
 expect(server.submitted.revision).toBe(1);
 expect(server.submitted.confirmed).toBe(true);
 expect(server.calls.filter(x=>x.endsWith('/next'))).toHaveLength(1);
});
test('완성 화면에서 회차 목록과 다음 화 이동',async({page})=>{
 await stub(page,2);
 await page.goto('/webtoon?run=ep1');
 // 새 디자인(#780): 회차 목록은 「회차 목록」 단추로 여는 창이다.
 await page.getByRole('button',{name:'회차 목록'}).filter({visible:true}).first().click();
 await page.getByRole('dialog',{name:'회차 목록'}).getByRole('button',{name:/^2화/}).click();
 await expect(page).toHaveURL(/run=ep2/);
 await page.getByRole('button',{name:'회차 목록'}).filter({visible:true}).first().click();
 await expect(page.getByRole('dialog',{name:'회차 목록'}).getByRole('button',{name:/^2화/})).toHaveAttribute('aria-current','page');
});
test('로그인 전에는 개인 설정을 요청하지 않음',async({page})=>{
 const server=await stub(page,1,false);
 await page.goto('/webtoon?view=next&run=ep1');
 await expect(page.getByText('연재 기록을 계정에 보관하려면 로그인해 주세요.')).toBeVisible();
 await expect(page.getByRole('button',{name:'로그인',exact:true}).last()).toBeVisible();
 expect(server.calls.some(x=>x.endsWith('/draft'))).toBe(false);
});

test('불러오는 중과 오류 화면 — 다시 시도와 내 작품 이동',async({page})=>{
 await stub(page);
 let release!:()=>void;
 const pending=new Promise<void>(resolve=>{release=resolve;});
 await page.route('**/series/ep1/draft',async route=>{
  await pending;
  await route.fulfill({status:500,contentType:'application/json',body:JSON.stringify({error:'테스트 불러오기 실패'})});
 });
 await page.goto('/webtoon?view=next&run=ep1');
 await expect(page.getByRole('status').filter({hasText:'앞 화의 기록을 불러오고 있어요'})).toBeVisible();
 release();
 await expect(page.getByRole('alert').filter({hasText:'테스트 불러오기 실패'})).toBeVisible();
 await expect(page.getByRole('button',{name:'다시 시도',exact:true})).toBeVisible();
 await expect(page.getByRole('button',{name:'내 작품으로 이동',exact:true})).toBeVisible();
});
test('저장 중에는 칸과 단추가 잠기고 돌아가기도 막힘',async({page})=>{
 await stub(page);
 let release!:()=>void;
 const pending=new Promise<void>(resolve=>{release=resolve;});
 await page.route('**/series/ep1/draft',async route=>{
  if(route.request().method()!=='POST') return route.fallback();
  await pending;
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({revision:1})});
 });
 await openForm(page);
 await page.getByLabel('이번 화에서 일어날 일',{exact:true}).fill('조금 일찍 출발한다.');
 await page.getByRole('button',{name:'초안 저장',exact:true}).click();
 await expect(page.getByLabel('이번 화에서 일어날 일',{exact:true})).toBeDisabled();
 await expect(page.locator('.wt-series-actions button[type=submit]')).toBeDisabled();
 await page.getByRole('button',{name:/돌아가기/}).click();
 await expect(page).toHaveURL(/view=next/);
 release();
 await expect(page.getByRole('status').filter({hasText:'초안을 저장했어요'})).toBeVisible();
 await expect(page.getByLabel('이번 화에서 일어날 일',{exact:true})).toBeEnabled();
});

test('같은 캐릭터의 다른 작품은 다음 회차로 이동하지 않음',async({page})=>{
 const server=await stub(page,1);
 await page.route('**/api/**/runs',route=>route.fulfill({contentType:'application/json',body:JSON.stringify({runs:[{run_id:'unrelated',title:'민수의 별개 이야기',character:'민수',episodes:[1],page_count:1}]})}));
 await openForm(page);
 await expect(page.getByRole('heading',{name:'2화 이어 만들기'})).toBeVisible();
 await expect(page).toHaveURL(/run=ep1/);
 expect(server.calls.some(v=>v.endsWith('/series/ep1/draft'))).toBe(true);
 expect(server.calls.some(v=>/\/unrelated\/(result|draft|next)$/.test(v))).toBe(false);
});
