import {test,expect,type Page} from '@playwright/test';
import {readFileSync,mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
const base=resolve(__dirname,'../../..');
const worlds=JSON.parse(readFileSync(resolve(base,'webtoon/ai/new_harness/worlds.json'),'utf8')).presets;
const seeds=JSON.parse(readFileSync(resolve(base,'webtoon/ai/new_harness/lorebook.json'),'utf8')).worlds;
const books=Object.entries(worlds).map(([id,w]:[string,any])=>({id,name:w.label,source:'preset',confirmed:false,fixed_settings:w.text,progress_state:'',entries:(seeds[id]||[]).map((e:any)=>({title:e.name,content:e.text,keys:e.keys,kind:e.kind,always:false,enabled:true,evidence:''}))}));
const ok=(data:any)=>({success:true,data});
async function setup(page:Page,empty=false){
 let sent:any=null;let personal:any[]=[];
 await page.addInitScript(({empty})=>{localStorage.setItem('lore_uid','lore-test');localStorage.setItem('lore_wt_notify_asked','1');if(!sessionStorage.getItem('lore_wizard_draft'))sessionStorage.setItem('lore_wizard_draft',JSON.stringify(empty?{}:{name:'민수',character:'다정한 청년',photos:[],quality:'wave',style:'romance',mode:'simple',create:'quick'}));},{empty});
 await page.route('**/api/**',async route=>{const p=new URL(route.request().url()).pathname;const send=(data:any)=>route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
 if(p==='/api/v1/users/me')return send(ok({userId:7,email:'writer@example.test',nickname:'작가',role:'USER'}));
 if(p==='/api/v1/credits/me')return send(ok({balance:48}));
 if(p.endsWith('/allowance'))return send({logged_in:true,balance:48,credit_cost:12,qualities:[{key:'wave',credits:12}]});
 if(p.endsWith('/lorebooks'))return send({books,personal_books:personal,extract_limit:3,extract_left:3});
 if(p.endsWith('/lorebooks/mine')){const book={...route.request().postDataJSON(),library_id:'my-book',library_owner:7};personal=[book];return send(book);}
 if(p.endsWith('/lorebooks/extract'))return send({id:'extract-test',status:'RUNNING'});
 if(p.endsWith('/lorebooks/extract/extract-test'))return send({status:'DONE',book:{name:'다리 너머의 약속',source:'extracted',fixed_settings:'민수는 높은 곳을 무서워한다.',progress_state:'',entries:[],confirmed:false}});
 if(p.endsWith('/characters/random'))return send({name:'민수',description:'다정한 청년'});
 if(p.endsWith('/characters'))return send({characters:[]});
 if(p.endsWith('/create')){sent=route.request().postDataJSON();return send({id:'created-book'});}
 if(p.endsWith('/jobs/created-book'))return send({id:'created-book',status:'queued',stage:'story',pct:0,log:[],queue:{ahead:0,minutes:1,line:'곧 시작해요'}});
 if(p.endsWith('/jobs/mine'))return send({jobs:[],cards:[]});
 if(p.endsWith('/runs'))return send({runs:[]});
 return send(ok([]));});
 return {get sent(){return sent;}};
}
const next=async(page:Page)=>page.getByRole('button',{name:'다음',exact:true}).filter({visible:true}).first().click();
async function finish(page:Page){await next(page);await expect(page).toHaveURL(/step=4/);await next(page);await expect(page).toHaveURL(/step=5/);await page.getByLabel('저작권 확인',{exact:true}).check();await page.getByRole('button',{name:'웹툰 만들기',exact:true}).filter({visible:true}).first().click();await expect(page).toHaveURL(/job=created-book/);}
test('간단한 시작 화면과 설정집 도움말, 붙이기·빼기·복원',async({page},info)=>{
 await setup(page);await page.goto('/webtoon?view=create&step=3');
 await expect(page.getByRole('heading',{name:'어떤 이야기를 만들까요?'})).toBeVisible();
 await expect(page.getByText('맡겨서 시작하기',{exact:true})).toHaveCount(0);
 const help=page.getByRole('button',{name:'설정집이란?',exact:true});
 await help.click();await expect(help).toHaveAttribute('aria-expanded','true');
 await expect(page.getByRole('region',{name:'설정집이란?'})).toContainText('진행 상태');
 mkdirSync(resolve(base,'.impeccable/review'),{recursive:true});
 await page.addStyleTag({content:"nextjs-portal{display:none}"});
 await page.screenshot({path:resolve(base,`.impeccable/review/lore-help-${info.project.name}.png`),fullPage:true});
 await page.getByRole('button',{name:'설정집 설명 닫기'}).click();await expect(help).toBeFocused();
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();
 await expect(page.locator('.wt-lore-catalog button')).toHaveCount(14);
 await page.screenshot({path:resolve(base,`.impeccable/review/lore-picker-${info.project.name}.png`),fullPage:true});
 await page.locator('.wt-lore-catalog button').first().click();
 await page.getByRole('textbox',{name:/고정 설정/}).fill('주인공은 높은 곳을 무서워한다.');
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await expect(page.locator('.wt-lore-attached-list li')).toHaveCount(1);
 await page.screenshot({path:resolve(base,`.impeccable/review/lore-attached-${info.project.name}.png`),fullPage:true});
 await page.getByRole('button',{name:/설정집 빼기/}).click();
 await expect(page.locator('.wt-lore-attached-list li')).toHaveCount(0);
 await page.getByRole('button',{name:'방금 뺀 설정집 다시 붙이기'}).click();
 await expect(page.getByRole('textbox',{name:/고정 설정/})).toHaveValue('주인공은 높은 곳을 무서워한다.');
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 expect(await page.evaluate(()=>JSON.parse(sessionStorage.getItem('lore_wizard_draft')!).lorebook.confirmed)).toBe(true);
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});
test('확인한 설정집은 생성 요청에 포함되고 빼면 남지 않음',async({page})=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=3');
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.locator('.wt-lore-catalog button').first().click();await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await finish(page);expect(server.sent.lorebook.confirmed).toBe(true);expect(server.sent.lorebook.fixed_settings).toBe(books[0].fixed_settings);
 await page.goto('/webtoon?view=create&step=3');await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.locator('.wt-lore-catalog button').first().click();await page.getByRole('button',{name:'확인하고 붙이기'}).click();await page.getByRole('button',{name:/설정집 빼기/}).click();await finish(page);expect(server.sent.lorebook).toBeNull();
});
test('글 추출은 검토 후 연결되며 원문 변경 시 다시 확인',async({page})=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=3');const text=page.locator('#wt-wiz-story');await text.fill('민수는 높은 곳을 무서워한다. 친구와 약속한 날, 다리 앞에 멈춰 서서 우회로를 찾기 시작한다.');
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.getByLabel('입력한 글을 사용할 권리가 있어요.').check();await page.getByRole('button',{name:'이 글에서 설정 정리'}).click();
 await expect(page.getByRole('textbox',{name:'설정집 이름',exact:true})).toHaveValue('다리 너머의 약속');expect(server.sent).toBeNull();
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.locator('.wt-lore-catalog button').first().click();await page.getByRole('button',{name:'확인하고 붙이기'}).click();await text.fill('민수는 높은 곳을 무서워한다. 친구에게 무서운 이유를 털어놓고 함께 우회로를 걷는다.');
 await expect(page.getByRole('button',{name:'다음',exact:true}).filter({visible:true}).first()).toBeDisabled();
 await page.locator('.wt-lore-attached-list li').filter({hasText:'다리 너머의 약속'}).getByRole('button').first().click();await page.getByRole('button',{name:'확인하고 붙이기'}).click();await finish(page);expect(server.sent.mode).toBe('own');expect(server.sent.checkpoints).toBe(true);
});

test('아무 글·설정집 없이 시작하면 캐릭터를 준비하고 다음으로 이동',async({page})=>{
 const server=await setup(page,true);await page.goto('/webtoon?view=create&step=1');await next(page);await expect(page).toHaveURL(/step=3/);
 await expect(page.locator('#wt-wiz-story')).toHaveValue('');await expect(page.getByRole('button',{name:'+ 설정집',exact:true})).toBeVisible();
 expect(await page.evaluate(()=>JSON.parse(sessionStorage.getItem('lore_wizard_draft')!).name)).toBe('민수');
 await finish(page);expect(server.sent.lorebook).toBeNull();expect(server.sent.story).toBe('');expect(server.sent.mode).toBe('quick');
});
test('추출 중 선택 덮어쓰기 방지와 실패 안내 유지',async({page},info)=>{
 await setup(page);let complete=false;
 await page.route('**/lorebooks/extract/extract-test',async route=>route.fulfill({contentType:'application/json',body:JSON.stringify(complete?{status:'ERROR',error:'설정을 정리하지 못했어요'}:{status:'RUNNING'})}));
 await page.goto('/webtoon?view=create&step=3');await page.locator('#wt-wiz-story').fill('민수는 높은 곳을 무서워한다. 친구와 약속한 날, 다리 앞에 멈춰 서서 우회로를 찾기 시작한다.');await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.getByLabel('입력한 글을 사용할 권리가 있어요.').check();await page.getByRole('button',{name:'이 글에서 설정 정리'}).click();
 await expect(page.locator('.wt-lore-catalog button').first()).toBeDisabled();await expect(page.getByRole('button',{name:'빈 설정집에 직접 적기'})).toBeDisabled();await page.screenshot({path:resolve(base,`.impeccable/review/lore-loading-${info.project.name}.png`),fullPage:true});complete=true;
 await expect(page.getByRole('alert').filter({hasText:'설정을 정리하지 못했어요'})).toBeVisible();await expect(page.locator('.wt-lore-catalog button').first()).toBeEnabled();await page.screenshot({path:resolve(base,`.impeccable/review/lore-error-${info.project.name}.png`),fullPage:true});await expect(page.getByRole('alert').filter({hasText:'설정을 정리하지 못했어요'})).toBeVisible();
});

test('설정집 여러 개 추가·개별 수정·제거·복원 후 모두 생성에 전달',async({page},info)=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=3');
 await expect(page.getByText('없어도 만들 수 있어요.',{exact:true})).toHaveCount(0);
 const add=page.getByRole('button',{name:'+ 설정집',exact:true});
 await add.click();await page.locator('.wt-lore-catalog button').nth(0).click();await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await expect(add).toBeVisible();await add.click();await page.locator('.wt-lore-catalog button').nth(1).click();await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await expect(page.locator('.wt-lore-attached-list li')).toHaveCount(2);
 await expect(page.getByRole('button',{name:'다음',exact:true}).filter({visible:true}).first()).toBeEnabled();await page.reload();await expect(page.locator('.wt-lore-attached-list li')).toHaveCount(2);
 await page.locator('.wt-lore-attached-list li').first().getByRole('button').first().click();await page.getByRole('textbox',{name:/고정 설정/}).fill('도시에는 밤마다 문이 열린다.');await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await expect(page.locator('.wt-lore-attached-list li')).toHaveCount(2);
 await page.locator('.wt-lore-attached-list li').first().getByRole('button',{name:/설정집 빼기/}).click();
 await expect(page.locator('.wt-lore-attached-list li')).toHaveCount(1);await expect(page.locator('.wt-lore-attached-list')).toContainText(books[1].name);
 await page.getByRole('button',{name:'방금 뺀 설정집 다시 붙이기'}).click();await page.getByRole('button',{name:'확인하고 붙이기'}).click();await expect(page.locator('.wt-lore-attached-list li')).toHaveCount(2);
 await page.addStyleTag({content:'nextjs-portal{display:none}'});await page.screenshot({path:resolve(base,`.impeccable/review/lore-multiple-${info.project.name}.png`),fullPage:true});
 await finish(page);expect(server.sent.lorebook.books).toHaveLength(2);expect(server.sent.lorebook.fixed_settings).toContain('도시에는 밤마다 문이 열린다.');expect(server.sent.lorebook.fixed_settings).toContain(books[1].fixed_settings);
});


test('기존 설정 편집에서 목록으로 돌아가 추출하면 새 설정집으로 추가',async({page})=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=3');
 await page.locator('#wt-wiz-story').fill('민수는 높은 곳을 무서워한다. 친구와 약속한 날, 다리 앞에 멈춰 서서 우회로를 찾기 시작한다.');
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.locator('.wt-lore-catalog button').first().click();await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await page.locator('.wt-lore-attached-list li').first().getByRole('button').first().click();await page.getByRole('button',{name:'목록으로',exact:true}).click();
 await page.getByLabel('입력한 글을 사용할 권리가 있어요.').check();await page.getByRole('button',{name:'이 글에서 설정 정리'}).click();
 await expect(page.getByRole('textbox',{name:'설정집 이름',exact:true})).toHaveValue('다리 너머의 약속');await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await expect(page.locator('.wt-lore-attached-list li')).toHaveCount(2);await expect(page.locator('.wt-lore-attached-list li').first()).toContainText(books[0].name);
 await finish(page);expect(server.sent.lorebook.books).toHaveLength(2);expect(server.sent.lorebook.books[0].fixed_settings).toBe(books[0].fixed_settings);expect(server.sent.lorebook.books[1].source).toBe('extracted');
});


test('추출 중과 초안 검토 중에는 단계 이동·원문 변경을 막고 브라우저 뒤로가기도 보존',async({page})=>{
 await setup(page);let complete=false;
 await page.route('**/lorebooks/extract/extract-test',async route=>route.fulfill({contentType:'application/json',body:JSON.stringify(complete?{status:'DONE',book:{name:'다리 너머의 약속',source:'extracted',fixed_settings:'민수는 높은 곳을 무서워한다.',progress_state:'',entries:[],confirmed:false}}:{status:'RUNNING'})}));
 await page.goto('/webtoon?view=create&step=1');await next(page);await expect(page).toHaveURL(/step=3/);
 const source=page.locator('#wt-wiz-story');await source.fill('민수는 높은 곳을 무서워한다. 친구와 약속한 날, 다리 앞에 멈춰 서서 우회로를 찾기 시작한다.');
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.getByLabel('입력한 글을 사용할 권리가 있어요.').check();await page.getByRole('button',{name:'이 글에서 설정 정리'}).click();
 const advance=page.getByRole('button',{name:'다음',exact:true}).filter({visible:true}).first();await expect(advance).toBeDisabled();await expect(source).toBeDisabled();
 await expect(page.getByRole('button',{name:'설정집 닫기'})).toBeDisabled();
 const previous=page.getByRole('button',{name:/이전.*캐릭터/}).filter({visible:true});if(await previous.count())await expect(previous).toBeDisabled();
 await page.goBack();await expect(page).toHaveURL(/step=3/);await expect(page.getByRole('status').filter({hasText:'설정 추출 중'})).toBeVisible();
 await page.getByRole('link',{name:'Webtoon',exact:true}).click();await expect(page).toHaveURL(/step=3/);
 complete=true;await expect(page.getByRole('textbox',{name:'설정집 이름',exact:true})).toHaveValue('다리 너머의 약속');await expect(advance).toBeDisabled();await expect(source).toBeDisabled();
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();await expect(advance).toBeEnabled();await expect(source).toBeEnabled();await expect(page.locator('.wt-lore-attached-list li')).toHaveCount(1);
 await page.goBack();await expect(page).toHaveURL(/step=1/);
});

test('설정집 편집은 확인하거나 닫기 전까지 진행할 수 없고 취소하면 기존 부착본 유지',async({page})=>{
 const server=await setup(page);await page.goto('/webtoon?view=create&step=3');
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();await page.locator('.wt-lore-catalog button').first().click();
 const advance=page.getByRole('button',{name:'다음',exact:true}).filter({visible:true}).first();await expect(advance).toBeDisabled();
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();await expect(advance).toBeEnabled();
 await page.locator('.wt-lore-attached-list li').first().getByRole('button').first().click();await page.getByRole('textbox',{name:/고정 설정/}).fill('취소할 변경');await expect(advance).toBeDisabled();
 await page.getByRole('button',{name:'설정집 닫기',exact:true}).click();await expect(advance).toBeEnabled();await finish(page);expect(server.sent.lorebook.fixed_settings).toBe(books[0].fixed_settings);
});

test('내 설정집 저장 후 기본 설정집과 별도 표시하고 다시 불러오기',async({page})=>{
 await setup(page);await page.goto('/webtoon?view=create&step=3');
 await page.getByRole('button',{name:'+ 설정집',exact:true}).click();
 await page.getByRole('button',{name:'빈 설정집에 직접 적기'}).click();
 await page.getByRole('textbox',{name:'설정집 이름',exact:true}).fill('내 도시 설정');
 await page.getByRole('textbox',{name:'고정 설정',exact:true}).fill('도시의 시계는 밤에 멈춘다.');
 await page.getByRole('button',{name:'내 설정집에 저장',exact:true}).click();
 await expect(page.getByRole('status').filter({hasText:'저장했어요'})).toBeVisible();
 await page.getByRole('button',{name:'목록으로',exact:true}).click();
 await expect(page.locator('.wt-lore-personal button')).toHaveCount(1);
 await page.locator('.wt-lore-personal button').click();
 await expect(page.getByRole('textbox',{name:'고정 설정',exact:true})).toHaveValue('도시의 시계는 밤에 멈춘다.');
 await expect(page.locator('.wt-lore-panel').getByText(/공개|비공개/)).toHaveCount(0);
 await page.getByRole('button',{name:'확인하고 붙이기'}).click();
 await expect(page.locator('.wt-lore-attached-list')).toContainText('내 도시 설정');
});
