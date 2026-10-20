# Project UX rules

- When a setting refers to an entity MDMesh already stores (apps, devices, configurations, releases), let users select it by its human-readable name from the existing library or inventory. Do not make users type package names, IDs, hashes, or URLs that MDMesh already knows.
- Keep stable IDs or package names in API payloads and storage when needed, but resolve them to names in the UI. When an older saved value is missing from the library, show it clearly and preserve it until the user removes it.
- Before shipping a new management field, check its complete flow: library/inventory source, selection UI, save and reload, agent/server payload, and the deployed version that can apply it.
- If an entity can exist outside MDMesh's current library and an external search integration already exists (such as Play Store for Android packages), offer that search in the picker. Add only the metadata needed for selection unless the user explicitly requests installation or download.
- In editors that update on each keystroke, keep expensive derived option lists (especially locale/time-zone formatting or large package inventories) memoized by their real inputs. Verify that effect dependencies do not refetch data when only draft fields change.

# Android Agent design and account rules

- Use the Network Access screen (release 1.11.8 / build 1034 and later) as the shared visual baseline for Agent screens: a consistent light Material palette, readable text, safe areas for system bars/cutouts/IME, compact app-icon lists, and bottom sheets for details and forms.
- Use a standard top app bar with icon actions. Use a subtle single-choice control for switching roles/views. Reserve filled primary buttons for the main action; use text/outlined buttons for secondary actions. Keep consistent spacing, 48dp touch targets, and button shape. Do not turn navigation or every action into a large filled blue button.
- Admin identity and authentication belong to the Agent as a whole. Offer account entry on the home screen and share one server-bound session across all screens; navigating away from a screen must not log the admin out. Store any persisted session encrypted using Android Keystore, never persist passwords, and clear shared state on explicit logout or confirmed server-side session expiry. Keep enrollment credentials separate from admin authentication.
