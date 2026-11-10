import {test,expect,type Page} from '@playwright/test';
import {mkdirSync} from 'node:fs';
import {resolve} from 'node:path';

/* 결제가 아직 안 열렸을 때의 안내(2026-10-26) — 충전 창에서 결제하기를 누르면 「결제 준비 중」 팝업이 떠서
   1:1 문의(카카오톡 · 이메일)로 보낸다. 서버는 전부 가짜다 — 실제 결제창은 열리지 않는다. */
const base=resolve(__dirname,'../../..');
const shots=resolve(base,'.impeccable/review');
const ok=(data:any)=>({success:true,data});

async function setup(page:Page){
 const calls:string[]=[];
 await page.addInitScript(()=>{localStorage.setItem('lore_uid','lore-test');localStorage.setItem('lore_wt_notify_asked','1');});
 await page.route('**/api/**',async route=>{
  const p=new URL(route.request().url()).pathname;calls.push(p);
  const send=(data:any)=>route.fulfill({contentType:'application/json',body:JSON.stringify(data)});
  if(p==='/api/v1/users/me')return send(ok({userId:7,email:'writer@example.test',nickname:'작가',role:'USER'}));
  if(p==='/api/v1/credits/me')return send(ok({balance:48,paid:0}));
  if(p==='/api/v1/payments/packages')return send(ok({ready:false,packages:[
   {code:'MINI',credits:15,won:4900,note:null},{code:'MID',credits:70,won:18900,note:'가장 많이 고르는 것'}]}));
  if(p.endsWith('/allowance'))return send({logged_in:true,balance:48,credit_cost:12,qualities:[{key:'wave',credits:12}]});
  if(p.endsWith('/characters'))return send({characters:[],logged_in:true,free_left:0,free_per_day:0});
  if(p.endsWith('/characters/trash'))return send({keep_days:15,characters:[]});
  if(p.endsWith('/my/trash'))return send(ok({keepDays:30,runs:[]}));
  if(p.endsWith('/my/notify-setting'))return send(ok({on:true}));
  if(p.endsWith('/my/feedback'))return send({done:true,reward:0});
  if(p.endsWith('/series/drafts'))return send([]);
  if(p.endsWith('/jobs/mine'))return send({jobs:[],cards:[]});
  if(p.endsWith('/runs'))return send({runs:[]});
  return send(ok([]));
 });
 return calls;
}

test('결제가 안 열렸으면 결제하기에서 「결제 준비 중」 팝업 → 이메일 문의는 계정·크레딧으로',async({page},info)=>{
 const calls=await setup(page);
 mkdirSync(shots,{recursive:true});
 await page.goto('/webtoon?view=mypage');
 await page.addStyleTag({content:'nextjs-portal{display:none}'});
 await page.getByRole('button',{name:'충전',exact:true}).click();
 const charge=page.getByRole('dialog',{name:'크레딧 충전'});
 await expect(charge).toBeVisible();
 await charge.locator('#credit-phone').fill('01000000000');
 await charge.getByRole('checkbox').check();
 await charge.getByRole('button',{name:/결제하기/}).click();

 const notice=page.getByRole('dialog',{name:'결제 준비 중'});
 await expect(notice).toBeVisible();
 await expect(notice).toContainText('현재 결제 서비스가 아직 준비 중에 있어요.');
 await expect(notice).toContainText('1:1 문의하기를 통해 결제 문의를 진행해 주세요!');
 await expect(notice.getByRole('link',{name:/카카오톡 문의/})).toBeVisible();
 await expect(notice.getByRole('button',{name:/이메일 문의/})).toBeVisible();
 // 예전처럼 충전 창 안에 오류 한 줄이 따로 뜨지 않는다
 await expect(charge.getByText('결제 연결을 마무리하고 있어요')).toHaveCount(0);
 // 결제 주문은 열지 않았다
 expect(calls.some(p=>p.endsWith('/payments/orders'))).toBe(false);
 await page.screenshot({path:resolve(shots,`1-결제준비중-${info.project.name}.png`)});

 await notice.getByRole('button',{name:/이메일 문의/}).click();
 const contact=page.getByRole('dialog',{name:'이메일 문의'});
 await expect(contact).toBeVisible();
 await expect(contact.getByRole('combobox')).toHaveValue('ACCOUNT_CREDITS');
 await expect(page.getByRole('dialog',{name:'크레딧 충전'})).toHaveCount(0);
 await page.screenshot({path:resolve(shots,`2-이메일문의-${info.project.name}.png`)});
});
