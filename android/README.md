# Home Buddy for Android

Native Android app (Kotlin, Jetpack Compose) for Home Buddy. It talks to the same FastAPI backend and Postgres database as the web app; nothing is stored only on the phone. The API contract the tests check against is in `../contracts/`.

## Requirements

- JDK 17 or newer (Android Studio's bundled JDK works).
- Android SDK with platform `android-36.1` and build-tools 36.
- Gradle comes from the wrapper (`./gradlew`, Gradle 9.2.1). Builds use AGP 9.0.1 with its built-in Kotlin.

## Setup

1. Copy `local.properties.example` to `local.properties`. Android Studio adds `sdk.dir` itself.
2. Fill in the Cognito hosted-UI domain and the **Android** app client id. See "Android app client" in `../docs/aws-lightsail-deploy.md`; the client must allow the callback `homebuddy://auth/callback` and the sign-out URL `homebuddy://auth/signout`.

You can also pass the values on the command line: `./gradlew assembleDevDebug -Phomebuddy.cognitoClientId=…`.

## Running against your laptop's backend

The `dev` flavor calls `http://10.0.2.2:8000`, which is your Mac as seen from the emulator. Plain HTTP is allowed only for that address and localhost, and only in dev builds.

1. Start the backend from the repo root: `uvicorn backend.api:app --reload`.
2. Emulator: install and run `devDebug`.
3. Physical phone over USB: run `adb reverse tcp:8000 tcp:8000` and build with `-Phomebuddy.devApiBaseUrl=http://localhost:8000`.

The `prod` flavor calls `https://api.homebuddy.chat`.

## Modules

| Module | What it holds |
|---|---|
| `:app` | Activity, Hilt setup, flavors and BuildConfig, manifest (sign-out redirect, network security) |
| `:feature:auth` | Sign-in screen, session ViewModel, theme (moves to a design-system module once there are more features) |
| `:core:auth` | `SessionRepository` (sign-in state, refresh, sign-out), AppAuth behind the `AuthClient` interface, Keystore-encrypted token storage |
| `:core:network` | Retrofit API, models mirroring `backend/schemas.py`, Bearer and `X-Client` headers, refresh-once-on-401 |
| `:core:testing` | Shared test helpers, including `ContractFixtures`, which reads `../contracts/fixtures` |

## Sign-in and devices

- Sign-in uses Cognito's hosted UI in a Custom Tab (auth code + PKCE).
- Each phone keeps its own tokens, so one account can be signed in on several devices.
- On a 401 the app refreshes once and retries once. If that still fails, for example because the account was deleted on another device, it wipes local data and returns to sign-in.
- Being offline never signs anyone out.

## Tests

Unit, contract, integration (MockWebServer) and screenshot (Roborazzi) tests run on the JVM. There are no UI-automation or end-to-end device tests by design.

```bash
./gradlew testDevDebugUnitTest :core:network:testDebugUnitTest :core:auth:testDebugUnitTest
./gradlew :feature:auth:verifyRoborazziDebug   # compares against src/test/screenshots
./gradlew :feature:auth:recordRoborazziDebug   # after an intended UI change; commit the new images
./gradlew lintDevDebug
```

The contract tests read the backend's recorded responses from `../contracts/fixtures`. When the backend changes its API it regenerates those (`python scripts/export_contract.py`), and these tests show whether the app still understands them.
