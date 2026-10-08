# AGENTS.md

Unofficial Baidu Tieba Android client. Single Gradle module `:app`; active branch is `4.0-dev` (Jetpack Compose rewrite — older View-system code still lives in `activities/`, `fragments/`, `components/`, `adapters/`). Codebase comments/strings are Chinese.

## Toolchain & build

- Requires JDK 17 and an Android SDK with platform 36 + build-tools 36.0.0 (`minSdk 23`, `targetSdk 36`). `local.properties` and `keystore.properties` are gitignored and not checked in — configure `JAVA_HOME` + `ANDROID_HOME` (or `local.properties`) before building.
- Build: `./gradlew assembleDebug` (`.\gradlew.bat assembleDebug` in PowerShell). CI runs `assembleRelease` with JDK 17.
- App version comes from root `application.properties` (`versionCode`/`versionName`, pre-release suffix) — not from `build.gradle.kts`. When `keystore.properties` exists every variant is signed with it; otherwise debug signing is used.
- Dependency versions live in `gradle/libs.versions.toml` (managed by the `refreshVersions` Gradle plugin); don't hand-edit `versions.properties`. Add deps to the catalog, reference via `libs.*`.
- `.github/workflows/build.yml` only runs on `v*` tags and manual dispatch — normal pushes/PRs get no CI, so verify locally.

## Code generation (rebuild after changes)

- KSP generates Hilt, Room, Glide, and Compose Destinations code. Room schemas are exported to `app/schemas/` — commit updated schema JSON when entities change.
- Wire: edit `.proto` files in `app/src/main/protos`; Kotlin is generated at build into `com.huanchengfly.tieba.post.api.models.protos.**`. Only hand-write extensions in `api/models/protos/Extensions.kt`. The whole generated proto package is declared stable via `compose_stability_configuration.txt`.
- Compose Destinations: screens are `@Destination` composables under `ui/page/`; `NavGraphs`/`*Destination` classes are KSP-generated. `MainPage` is `@RootNavGraph(start = true)`; deep links use the `tblite://` scheme, and `MainActivityV2` maps `tieba.baidu.com` URLs.

## Architecture

- `arch/` — MVI base. A screen is `ui/page/<feature>/XxxPage.kt` + `XxxViewModel.kt` (intent/state/partial-change/event types usually defined in the ViewModel file). `BaseViewModel<Intent, PartialChange, State, Event>` reduces `PartialChange`s fed by a `PartialChangeProducer`. UI reads slices with `collectPartialAsState(...)`, sends `viewModel.send(intent)`, handles events with `onEvent<...>` / `pageViewModel()`; cross-screen messages use `GlobalEvent`.
- `api/` — network layer. Endpoints are declared in `api/interfaces/ITiebaApi.kt` and implemented in `api/interfaces/impls/MixedTiebaApiImpl.kt` (provided as a singleton by `api/TiebaApi.kt`); both must be updated together. Retrofit clients for the different Tieba backends are in `api/retrofit/RetrofitTiebaApi.kt`. Protobuf requests are built with `buildProtobufRequestBody` (multipart `data` part, SToken auto-attached). Catch errors via `getErrorMessage()` / `getErrorCode()` from `api/retrofit/exception`.
- `repository/` — page data loading (`PbPageRepository`, `FrsPageRepository`, …); put load/more/refresh logic here, not in the ViewModel.
- Preference storage is DataStore (`appPreferences`, `rememberPreferenceAsMutableState` in `DataStore.kt`). Theming is custom (`ExtendedTheme`, `ThemeUtil` under `ui/common/theme`) and uses **Material 2** (`androidx.compose.material`) — Material3 is not used here.

## Testing

There is effectively no test suite (only `ExampleUnitTest`/`ExampleInstrumentedTest` placeholders). Validate changes by compiling: `./gradlew :app:assembleDebug` or `:app:compileDebugKotlin`. Instrumented tests would need a device/emulator.

## Conventions

- Commit messages are Chinese with conventional prefixes (`feat:`, `fix:`, `refactor:`) and often an issue reference (`#123`).
- UI strings: add only to `app/src/main/res/values/strings.xml`; Chinese is the only translation set.
