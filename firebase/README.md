# Firebase emulator & Security Rules tests (FB-005)

Self-contained tooling for the Firebase emulator suite and the baseline
Security Rules tests. It is **not** wired into the Gradle/KMP build and does not
affect any Android/iOS build command.

## Files

| Path | Purpose |
|---|---|
| `../firebase.json` | CLI + emulator configuration (pinned ports) |
| `../firestore.rules` | Baseline authenticated owner-only Firestore Rules |
| `../storage.rules` | Baseline authenticated owner-only Storage Rules |
| `../.firebaserc` | Project aliases — **placeholder only**, see below |
| `test/` | `@firebase/rules-unit-testing` Rules tests |

## Pinned emulator ports

| Emulator | Port |
|---|---|
| Authentication | 9099 |
| Cloud Firestore | 8080 |
| Cloud Storage | 9199 |
| Emulator UI | 4000 |
| Emulator hub | 4400 |

The Firestore emulator also opens a UI websocket on 9150 (assigned by the
emulator, not configurable in `firebase.json`). Ports are pinned so the Rules
tests and, later, the Android/iOS clients can hard-code emulator endpoints.

## Prerequisites

- Node.js (developed against v24) and a JDK (the Firestore and Storage
  emulators are Java processes).
- `npm install` in this directory. `firebase-tools` is a local devDependency;
  **no global install and no `firebase login` is required**, because all
  commands below use the reserved `demo-` project id `demo-fluxit`, which the
  CLI treats as emulator-only and never contacts Google for.

## Commands

```sh
cd firebase
npm install
npm test        # starts auth+firestore+storage emulators, runs Rules tests, shuts down
npm run emulators   # long-running emulator suite incl. UI at http://127.0.0.1:4000
```

## `.firebaserc` is a placeholder

`default` is set to `demo-fluxit`. This is **not** a real Firebase project — the
`demo-` prefix is a Firebase-reserved, emulator-only convention. Per `DEC-002a`
no project has been provisioned yet. `FB-004`/`MAN-001` must add the real
development project alias once the user creates it. Until then any `firebase
deploy` will fail loudly rather than write somewhere unintended.

## Rules scope

These are the Phase 0 **baseline** Rules per `PLAN-002`: deny-by-default,
authenticated, owner-only. Field-level validation, type/range checks,
counter-integrity rules and immutable-ownership enforcement are Phase 6
(`FB-601`/`FB-602`) and are deliberately out of scope here.
