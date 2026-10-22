# Releasing Home Buddy for Android

## One-time setup

1. **Play Console:** create a developer account ($25, with identity verification). Choose a **personal** or **organization** account; personal accounts created after 13 Nov 2023 must run a closed test with at least 12 testers for 14 days before production.
2. **Create the app** `chat.homebuddy.app`, with Play App Signing (Google holds the app signing key).
3. **Upload key:** create one and keep it with your other secrets:
   ```bash
   keytool -genkeypair -v -keystore homebuddy-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
   ```
   Then point `local.properties` at it:
   ```properties
   homebuddy.releaseStoreFile=/path/to/homebuddy-upload.jks
   homebuddy.releaseStorePassword=…
   homebuddy.releaseKeyAlias=upload
   homebuddy.releaseKeyPassword=…
   ```
4. **Backend:** the Android Cognito client, `COGNITO_MOBILE_APP_CLIENT_IDS`, `api.homebuddy.chat`, account deletion and the privacy steps in `../docs/aws-lightsail-deploy.md` are all done, and the contact address is filled in on `/privacy` and `/terms`.

## Store listing and App content

- **Privacy policy:** `https://homebuddy.chat/privacy`
- **Account deletion URL:** `https://homebuddy.chat/?page=delete-account` (deletion is also in the app, under Account)
- **Target audience:** adults; not designed for children.
- **Content rating:** answer the questionnaire; there is no user-to-user content.
- **Ads:** none.
- **Data safety** (keep it in line with the privacy policy):

| Data | Collected | Shared | Why | Notes |
|---|---|---|---|---|
| Email address, name | Yes | No | Account management | From Cognito sign-in |
| Files and docs (PDFs you add) | Yes | Yes, with OpenAI as a service provider | App functionality | Encrypted at rest; deletable |
| Other user-generated content (questions, chats) | Yes | Yes, with OpenAI as a service provider | App functionality | Deleted after 90 days without activity |
| Audio | No | – | – | Dictation becomes text on the phone; only the text is sent |
| App interactions, diagnostics | No | – | – | No analytics or crash SDK yet; add them here if that changes |
| Data encrypted in transit | Yes | | | HTTPS only in release builds |
| Users can request deletion | Yes | | | In the app and at the URL above |

## Each release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Run the whole ladder in `../docs/android-testing.md` up to the rung you're releasing to.
3. Build the bundle:
   ```bash
   ./gradlew bundleProdRelease
   ```
   The output is `app/build/outputs/bundle/prodRelease/app-prod-release.aab`.
4. Upload it to the **internal testing** track first (up to 100 testers, available within minutes). Then **closed testing**, then **production** with a staged rollout: 5% → 20% → 50% → 100%.
5. Read the **pre-launch report** in Play Console for crashes and accessibility findings before widening a rollout.
6. If the API changes in a way older apps can't handle, raise `MIN_ANDROID_VERSION` on the backend **after** the new version is fully rolled out. Older apps then show "Time to update".

## Not done yet

- Release builds aren't minified (`isMinifyEnabled = false`). Turning on R8 needs keep rules checked on a device, especially for kotlinx.serialization, Retrofit and AppAuth.
- There is no crash reporting. If you add Crashlytics or Sentry, update the Data safety answers and the privacy policy.
- The icon is a simple vector, so a designed icon and store screenshots are still needed.
