# Android usage management

The Agent's **Sử dụng** destination opens with all configurations. Usage is summed
across devices for the selected calendar date range (today, 7 or 30 days). Search
filters the already-loaded application list without another network request.

Selecting an application opens a bottom sheet. Configuration choices are ordered
by that application's foreground time descending, then by name. The highest
usage configuration is initially selected. Configurations without reports remain
selectable at the end and are labelled as missing data, rather than zero usage.
Only the selected configuration is changed by Save. Each device receives its own
daily allowance, rather than sharing a configuration-wide time budget.

The sheet edits daily limits and an all-day app ban. Unrelated app rules, allowed
windows, and rest schedules are preserved. A policy refetch detects intervening
changes before saving; users must reload the configuration on conflict. As with
the existing configuration editor, the replacement API has no server-side ETag,
so this does not guarantee protection against a simultaneous write during Save.

All-day bans reuse the existing rest-schedule wire contract. Two SELECTED daily
schedules cover [00:00,12:00) and [12:00,00:00), seven days a week, with IDs prefixed
by `app-block:<UUID derived from package>:`. Removing the ban removes only those
schedules. Other block sources, protected packages and Device Owner requirements
continue to be enforced by the existing Agent. These schedules are also visible
in the existing configuration schedule editor. The existing 50-schedule server
limit still applies; validation failures leave the editor draft intact.

`GET /rest/private/app-usage/summary?from=YYYY-MM-DD&to=YYYY-MM-DD` aggregates in SQL
by package and current device configuration, bounds the requested period to 31
calendar days, and checks tenant and configuration access. It uses the existing
usage-date/device index. Historical usage follows a device's current configuration,
matching the existing reports; it is not a historical configuration-assignment log.
The response contains aggregate rows, not raw per-device reports. The server
change must be deployed before this Android screen can load real aggregate data.
No database migration or new Agent enforcement protocol is needed.

The screen uses a lifecycle ViewModel, cancellable requests, lazy keyed rows,
memoized search results, a bounded local-icon cache and configuration/library
metadata cached for the admin session. Save reports a saved policy, not confirmed
device application; devices apply it through normal policy synchronization.

## Verification

- Android debug build and `:core:testDebugUnitTest`.
- `RestScheduleEngineTest`: all-week ban coverage at midnight/noon boundaries,
  no-usage-access enforcement, unrelated packages and removal.
- Maven `AppUsageResourceTest`: existing validation plus paired ban schedules.
- `UsageUiTest` on Pixel 3a API 36: ranking/default selection, changing scope,
  saving/reloading a limit and ban, preserving other rules and configuration
  isolation. Separate test checks that typing does not fetch and changing dates
  reuses metadata. These UI tests use an injected API fixture, not production data.
- Manual launch and bottom-navigation smoke checks on the installed debug APK.
