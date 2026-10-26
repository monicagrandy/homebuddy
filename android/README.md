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

Two modules, so the one boundary that matters is enforced: screens can't reach DTOs, Retrofit or Room directly.

| Module | Packages | What it holds |
|---|---|---|
| `:core` | `core.network`, `core.auth`, `core.data` (with `.profile`, `.documents`, `.conversations`), `core.domain` (+ `core.testing` test fixtures) | Retrofit API and models mirroring `backend/schemas.py`, the SSE stream client, sign-in and token storage, the Room cache, the upload worker, stateless repositories, and the use cases that combine them (sign in, sign out, delete account, observe the session) |
| `:app` | `app`, `designsystem`, `feature.auth`, `feature.home`, `feature.documents`, `feature.chat`, `feature.account` | Screens, ViewModels, the theme, the activity, Hilt setup, flavors and the manifest |

In the data layer, a repository never touches a DAO or the API itself. Each feature package in `core.data` has:
- a repository interface and its `Cached…Repository` implementation
- a local data source over its Room DAOs
- a remote data source over `HomeBuddyApi` (and, for conversations, the SSE stream)

Every data source is an interface with one implementation. Remote data sources handle HTTP status codes: a 404 on delete counts as done, and a missing thread or job comes back as `null`. Repositories never see Retrofit's `Response`. Room's entities, DAOs and database stay in `core.data`, so the exported schema files don't move.

Each feature declares its typed Navigation Compose destinations next to its screens (`SignInNavigation.kt`, `ChatNavigation.kt`, `DocumentsNavigation.kt` and so on). There are two `NavHost`s:

- The root one, in `MainActivity`, holds sign-in, home setup and the signed-in app. The session decides which one you're on. Each change navigates there and clears the back stack (`navigateForSession` in `AppNavigation.kt`), so back from the app never returns to sign-in, and back from sign-in never goes into the app.
- `MainScaffold`, the signed-in app, has its own `NavHost` for the tabs and the conversation screen, with a bottom bar.

ViewModels belong to the destination they're created on. Signing out removes the signed-in destination, which clears every ViewModel created in the app, the tabs' included.

UI text lives in `app/src/main/res/values/strings.xml`. ViewModels hand screens a `UiText` (a string resource, or a server message shown as is) and never read resources themselves.

## Sign-in and devices

- Sign-in uses Cognito's hosted UI in a Custom Tab (auth code + PKCE). `SignInPage`, an `ActivityResultContract`, is the only sign-in code that handles Intents; the ViewModel and use cases get a plain `SignInPageResult`.
- Nothing holds the session in memory. Being signed in means a refresh token is stored (encrypted with a Keystore key, in DataStore); the profile is cached in Room. `ObserveSessionUseCase` derives the session from those two.
- Only the refresh token is written to disk. The access token lives in memory and is refreshed when it's about to expire.
- Each phone keeps its own tokens, so one account can be signed in on several devices.
- On a 401 the app refreshes once and retries once. If that still fails, for example because the account was deleted on another device, it wipes local data and returns to sign-in.
- Being offline never signs anyone out.

## Tests

Unit, contract, integration (MockWebServer) and screenshot (Roborazzi) tests run on the JVM. 

// TODO: add detekt
```bash
./gradlew :core:testDebugUnitTest :app:testDevDebugUnitTest
./gradlew :app:verifyRoborazziDevDebug   # compares against app/src/test/screenshots
./gradlew :app:recordRoborazziDevDebug   # after an intended UI change; commit the new images
./gradlew lintDevDebug
```

The manual checklist, for what automated tests can't cover (real sign-in, two phones, QR codes, voice), is in `../docs/android-testing.md`. Releasing to Google Play is in `RELEASE.md`.

Unit tests stay light: everything below runs on the plain JVM except where it says Robolectric. ViewModels don't depend on Android types, which keeps anything that could hold a Context or a lifecycle out of them.

| Layer | Tests | Runs on | Fakes |
|---|---|---|---|
| Room DAOs | `ProfileDaoTest`, `DocumentDaoTest`, `ConversationDaoTest`: the SQL itself | Robolectric (in-memory Room) | none |
| Local data sources | `Room…LocalDataSourceTest` | JVM | fake DAOs (`FakeDaos.kt`) |
| Remote data sources | `Retrofit…RemoteDataSourceTest`: requests and status handling | JVM | MockWebServer |
| Repositories, upload processor | `Cached…RepositoryTest`, `UploadProcessorTest` | JVM | `Test…DataSources.kt` in each feature's test package |
| Use cases, auth | `SessionUseCasesTest`, `NetworkSessionTest`, `AuthRepositoryTest`, `AuthStoreTest` | JVM | the test repositories in `:core`'s test fixtures (`TestProfileRepository` and the others) |
| ViewModels | `SessionViewModelTest` | JVM | the same test repositories |
| Screens, navigation | screenshot tests, `AppNavigationTest`, `MainNavigationTest`, `SignedInScopeTest` | Robolectric | none |

The test doubles fake only what some test has needed so far. Anything else fails loudly with `notFaked()`, so add it to the double when a test needs it. `ConversationViewModelTest` still runs on Robolectric against real Room until it moves to the test repositories.

The contract tests read the backend's recorded responses from `../contracts/fixtures`. When the backend changes its API it regenerates those (`python scripts/export_contract.py`), and these tests show whether the app still understands them.
