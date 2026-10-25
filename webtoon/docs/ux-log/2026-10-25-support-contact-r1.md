# UX review: My Page support contact

## Scope

Updated the webtoon My Page contact dialog with KakaoTalk, email, Instagram, and X channels. Added an in-service email form that sends the selected category, reply address, and message to the fixed LORE support inbox.

## Review method

- Applied the project-selected `ux-heuristics` review and Impeccable direction for the existing ocean-themed product UI.
- Opened the production build in actual Chrome and inspected the contact options and email composer at desktop (1440 × 900) and mobile (390 × 600) viewports.
- This is a code and interface review; no user research or real email delivery was performed.

## Findings

- The four support/social destinations are distinct and recognizable; each external channel opens from its own labeled control.
- `lore@lorecomic.com` remains visible in both the contact options and email composer, including while the form scrolls.
- The email form uses an example placeholder, validates message length, caps input at 3,000 characters, and exposes sending, error, and success states.
- Users can attach up to three PNG/JPG/WEBP/PDF files, at 1 MB each; the server validates consent and file type, size, and file signature before mail delivery.
- A required consent checkbox summarizes the collected items, inquiry-response purpose, retention through one year after resolution, and the consequence of declining; redundant policy wording was shortened.
- Increased contact dialog text sizes and removed the redundant composer subtitle at the user's request.
- Follow-up polish aligns the contact title with the close control, increases the social prompt size and spacing, removes the refusal helper sentence, and places “자세히 보기” beside the consent checkbox.
- With temporary local-only values, the form enabled the send button; it was not pressed and no message was transmitted.
- Chrome's file chooser blocked local test-file selection because the ChatGPT extension does not have access to file URLs. The attachment control was visually reviewed; a real file was not selected or submitted.
- At the short mobile height, the message form scrolls inside the dialog while the persistent contact address stays visible.
- No material usability issue was found during this bounded review. Actual SMTP delivery still depends on deployment mail settings and was not exercised.

## Evidence and limitations

- Desktop and mobile visual inspection completed on the local My Page route.
- Frontend TypeScript check completed without errors after attachment and consent changes.
- Next.js production build completed through static generation (17/17 pages). The repository prebuild asset-sync step could not write protected shared asset files in this worktree, so the successful build skipped that unchanged sync step.
- Backend Java compilation completed successfully with attachment and consent enforcement. No tests were run.
- No analytics or user feedback was used to infer usability outcomes.
