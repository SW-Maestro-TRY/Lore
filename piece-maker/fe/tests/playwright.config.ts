import { defineConfig } from '@playwright/test';

// 외부 전송 없는 PM 픽셀 전송기 검사. 모든 페이지와 Meta SDK 응답을 가로챈다.
export default defineConfig({
  testDir: '.',
  testMatch: 'meta-pixel.spec.ts',
  outputDir: '/private/tmp/piece-maker-meta-results',
  workers: 1,
  timeout: 15_000,
  use: { browserName: 'chromium' },
});
