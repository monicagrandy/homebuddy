# Android testing

The automated tests cover logic, the API contract, networking and how screens look. They don't cover a real sign-in, a real phone's network, the camera, the microphone, or several devices at once. Those are checked by hand, on the rungs below, before each release.

## The ladder

Each rung only after the one before it passes.

1. **Backend:** `pytest -q` (plus `PG_TEST_URL=… pytest -q tests/test_migrations.py`).
2. **Android:** from `android/`:
   ```bash
   ./gradlew :core:testDebugUnitTest :app:testDevDebugUnitTest :app:verifyRoborazziDevDebug lintDevDebug
   ```
3. **Your phone against your laptop:** the `devDebug` build with the backend running locally (see `android/README.md`). Work through the checklist below.
4. **Deployed backend:** deploy, then run the smoke test (next section). Then a manual web check: sign in, upload, ask.
5. **Internal testing track** on two of your own devices, running the "Two phones, one account" section.
6. **Closed test** with at least 12 testers for 14 days, if your Play account requires it (`android/RELEASE.md`).
7. **Staged rollout:** 5% → 20% → 50% → 100%, watching crashes and logs at each step.

## Smoke test of a deployed backend

```bash
python scripts/mobile_smoke.py --base-url https://api.homebuddy.chat --token "$HOMEBUDDY_TEST_TOKEN"
```

Use a dedicated test account in the beta group, with a household and at least one document. The run asks a document question and the gas question in two new conversations, checks that the answers stream, cite sources and escalate, then deletes the conversations.

To get the account's access token without the app, allow `ALLOW_ADMIN_USER_PASSWORD_AUTH` on the Android app client. Then, with AWS credentials allowed `cognito-idp:AdminInitiateAuth`:

```bash
aws cognito-idp admin-initiate-auth --user-pool-id "$COGNITO_USER_POOL_ID" --client-id "$ANDROID_CLIENT_ID" \
  --auth-flow ADMIN_USER_PASSWORD_AUTH --auth-parameters USERNAME=smoke@example.com,PASSWORD='…' \
  --query AuthenticationResult.AccessToken --output text
```

## Manual checklist

Mark each with the build, the device and the date.

### Sign-in
- [ ] Sign in with the hosted UI in a Custom Tab. Password managers fill it, MFA works, and you come back to the app.
- [ ] A brand-new account goes to "Set up your home". An account with a home goes straight in.
- [ ] Cancel sign-in with Back: you're returned to "Sign in", with no error.
- [ ] An account outside the beta group sees "This account isn't part of the Home Buddy beta yet."
- [ ] Sign out, then sign in again as a **different** account: the hosted UI asks again rather than reusing the first.
- [ ] Leave the app for more than an hour (access tokens last an hour), then use it: still signed in, with no error.

### Documents
- [ ] Upload a PDF with the picker. It shows "Uploading…", then "Reading the document…", then appears in the list.
- [ ] Share a PDF to Home Buddy from Files or Gmail, and share a link from Chrome.
- [ ] Scan a QR code that links straight to a PDF: the site is shown, then the PDF imports.
- [ ] Scan a QR code that links to a product page: "This link opens a web page…" with **Open link**.
- [ ] Scan an http:// QR code and a Wi-Fi QR code: both are refused with a clear message.
- [ ] Upload a scanned (image-only) PDF: "This PDF has no readable text (it may be a scan)."
- [ ] Upload the same PDF twice: "Already in your documents."
- [ ] Start an upload and switch to another app straight away: it still completes.
- [ ] Turn on airplane mode, start an upload, wait, then turn it off: it completes without being added twice.
- [ ] Delete a document, and pull to refresh.

### Chat
- [ ] Ask a document question: status lines, then the answer, then "From your documents" chips.
- [ ] Ask "I smell gas in the kitchen": the safety card appears, and **Call 911** opens the dialer without calling.
- [ ] Tap Stop mid-answer, then reopen the conversation later: the full answer is there.
- [ ] Turn on airplane mode mid-answer: the message about the dropped connection, and the answer appears later.
- [ ] Rotate the phone and background the app mid-answer: nothing crashes or duplicates.
- [ ] Delete a conversation.

### Voice
- [ ] The first mic tap asks for microphone permission; denying it shows a message and typing still works.
- [ ] Dictate a question: words appear in the box as you speak, and **nothing is sent until you tap Send**.
- [ ] Dictate in a noisy room, and add dictation to typed text.
- [ ] On a phone with on-device recognition (Android 12+, for example a Pixel), dictation works in airplane mode.

### Two phones, one account
- [ ] Sign in on both. A document added on one appears on the other after pull to refresh.
- [ ] Delete a document on phone A while phone B shows it: B drops it on refresh, and deleting it on B too isn't an error.
- [ ] Upload the same PDF on both: the second one reports "Already in your documents."
- [ ] Ask a question on A, and while it's answering ask in the **same conversation** on B: B shows "Still answering your last question…" and keeps the text to resend.
- [ ] Delete a conversation on A while B has it open: B shows it as gone when reopened.
- [ ] Delete the account on A: B is signed out on its next action, and its local data is gone.

### Account
- [ ] The privacy policy and terms open.
- [ ] Delete account requires typing DELETE, then returns to sign-in with "Your account and its data have been deleted." Signing in again starts a fresh account.
- [ ] Set `MIN_ANDROID_VERSION` above the app's version on a test backend: the app shows "Time to update".

### Accessibility and display
- [ ] TalkBack: every button is announced meaningfully (mic, send, stop, delete, Call 911), and streamed status is read out.
- [ ] Largest font size and display size: nothing is cut off; screens scroll.
- [ ] Dark mode.
