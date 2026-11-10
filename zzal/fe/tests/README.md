# Current focused room telemetry regression tests

Run from the repository root:

```
npm run test:zzal:telemetry
FOCUSED_TEST_URL=http://localhost:3203 npm run test:zzal:focused
```

The browser suite uses the current default `/zzal` route, with real React components at 320×568, 390×844 and 430×932 with touch enabled. It requires an already running frontend serving this checkout. It defaults to localhost:3203 (override FOCUSED_TEST_URL). It does not start/restart a service. Action/locator waits are bounded to 15 seconds and navigation to 45 seconds. All `/api/` requests are intercepted; unrecognized endpoints fail the test. External requests are blocked. API success/failure and assets are synthetic; these tests do not establish backend persistence or production delivery.

The node tests execute the actual telemetry module and focused interaction hook with a minimal React lifecycle stub. They cover stable IDs across reload/new tabs, network and HTTP failures, explicit/partial ACK, beacon ambiguity, bounded overflow, server rejection, HTTP origins without randomUUID, privacy filtering, sample/tutorial context, care no-op/uncertain results and sleep/wake without chat.

Existing `apps/web/e2e` scrapbook/mock suites remain supported legacy regression tests. Their helper explicitly selects `skin=scrapbook`; passing them is not evidence for the current default focused route. They must not be mechanically switched to focused selectors because their mock engine and lifecycle assumptions differ. The current browser suite covers first-meeting failure/readback/retry and entering, care, sleep/wake, game, album, review retry, attendance view, journal read, background apply and inquiry submission. Onboarding, hatching, full tutorial ending, attendance/custom reward grants and real backend browser round trips remain separate coverage responsibilities. Actor capture is checked against the mocked verified auth response; backend mismatch rejection is covered separately by backend tests.
