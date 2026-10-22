# NutriTrack UI refresh

Latest follow-up: 2026-10-22 public snapshot. Settings account/data cards, sample registration assistance, explicit AI availability, baseline score labels and always-available fruit lookup were verified with 21 selected API 35 tests, including light/dark draft views and 1.3× text. See [PUBLIC_RELEASE.md](PUBLIC_RELEASE.md) for the new screenshots and exact scope. The 2026-10-13 results below are historical.

Verified on 2026-10-13. This is a presentation-only update to the native Compose app.

## Design

- Warm ivory canvas, forest-green actions, sage surfaces, and a matching dark palette.
- Bundled Geist variable font; the SIL OFL notice ships in `assets/licenses/geist_ofl.txt`.
- Editorial food imagery, a fixed brand header, floating bottom navigation, readable score progress,
  and native page/content transitions. The gpt-taste web patterns were adapted to Compose rather
  than introducing React, GSAP, a WebView, automatic marquees, or new network images.
- Refreshed Welcome, Login/Register, Home, Insights, NutriCoach, Questionnaire, Daily Intake,
  and Settings. Agent planning comes first; fruit lookup and motivation remain available.
- All existing event callbacks, test tags, validation, navigation rules, health disclaimers,
  draft/adoption controls, and asynchronous data ownership remain intact.

## Verification

- `assembleDebug`, `assembleDebugAndroidTest`, and `compileDebugAndroidTestKotlin` passed.
- `testDebugUnitTest`: 148 tests passed; no failures, errors, or skipped tests.
- `lintDebug`: 0 errors; 69 remaining project warnings. No new UI-test naming warning remains.
- Selected API 35 instrumentation: 18 tests passed across `VisualRefreshComposeTest`,
  `HomeNavigationComposeTest`, `CriticalUserJourneyComposeTest`, and `NutriCoachAgentComposeTest`.
- The 6 visual cases were run again successfully for screenshot export. Light/dark draft pages,
  a 1.3x font-scale login form, safety text, explicit actions, and intake navigation were checked.
- Screenshots are actual rendered production Composables with synthetic UI fixtures, not live
  model outputs. This run made zero real AI requests.
- Device: separate `Pixel_9_API_35` / `emulator-5556`, with replacement installs only. The prior
  acceptance AVD, its app data, and registered App Check credentials were not touched.
- Room remains v12; historical schemas, migrations, DataStore, models, SDKs, Firebase,
  App Check, and background lifecycle behavior were not changed. Original stash preserved.

## Boundaries

No new real Firebase acceptance was performed. This update does not add cloud sync, reminders,
or automatic plan execution, and does not replace the existing Agent acceptance evidence.
Geist source: https://github.com/google/fonts/tree/main/ofl/geist
