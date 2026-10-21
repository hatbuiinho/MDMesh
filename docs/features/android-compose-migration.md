# Android Compose migration

## First screen

AppRequestsActivity now hosts a Material 3 Compose screen. Home, Network Access,
account, enrollment and device-management services retain their existing UI and
behavior. This is an incremental migration; Views and Compose coexist.

The shared `ui/compose` package provides the light MDMesh theme, top app bar,
compact role switch, app rows/icons, status badges and messages. New management
screens should reuse these components and follow the Network Access visual
baseline. Details, selection and forms use modal bottom sheets with IME insets.

## State and integration

AppRequestsViewModel owns immutable screen state, draft restoration, explicit
search, paging and actions. AppRequestsRepository adapts the existing client and
shared encrypted admin session. Composables render state and emit events; they
do not perform network requests. Typing a reason, note or query does not refetch
the inventory. Late results are discarded after role changes or cancelled
searches. Polling pauses while a sheet is open.

Both app discovery paths remain available: bridge search inside Agent and
Google Play link sharing through the existing exported share receiver. Receiving
a link prepares a form; submitting still requires an explicit action and reason.
Opening an approved app revalidates approval with the server first.

Device labels show the device name, falling back to its identifier only when
the name is absent. The backend must provide the name in its response.

## Verification

Run `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest`.
Unit coverage includes draft restoration, cancelled/late requests, account
changes, duplicate submission, approval revalidation and name fallback.
Compose instrumentation covers picker actions and device labels; compiling its
APK does not imply it has run on a device. The managed Realme blocks the debug
package, so physical UI verification requires an approved signed build.

No release publication is part of this migration step.
