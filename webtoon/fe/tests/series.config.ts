import { defineConfig } from '@playwright/test';
export default defineConfig({
 testDir:'.',testMatch:["series.spec.ts","lorebook.spec.ts","payment.spec.ts"],outputDir:'../../../.impeccable/test-results',workers:1,
 timeout:45000,expect:{timeout:10000},reporter:'list',
 use:{locale:'ko-KR',baseURL:'http://127.0.0.1:3107',browserName:'chromium',channel:'chrome',trace:'retain-on-failure'},
 projects:[{name:'desktop',use:{viewport:{width:1280,height:900}}},{name:'mobile',use:{viewport:{width:390,height:844},hasTouch:true}}]
});
