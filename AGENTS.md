# Project UX rules

- When a setting refers to an entity MDMesh already stores (apps, devices, configurations, releases), let users select it by its human-readable name from the existing library or inventory. Do not make users type package names, IDs, hashes, or URLs that MDMesh already knows.
- Keep stable IDs or package names in API payloads and storage when needed, but resolve them to names in the UI. When an older saved value is missing from the library, show it clearly and preserve it until the user removes it.
- Before shipping a new management field, check its complete flow: library/inventory source, selection UI, save and reload, agent/server payload, and the deployed version that can apply it.
- If an entity can exist outside MDMesh's current library and an external search integration already exists (such as Play Store for Android packages), offer that search in the picker. Add only the metadata needed for selection unless the user explicitly requests installation or download.
- In editors that update on each keystroke, keep expensive derived option lists (especially locale/time-zone formatting or large package inventories) memoized by their real inputs. Verify that effect dependencies do not refetch data when only draft fields change.
