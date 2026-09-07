---
name: firestore-testing
description: How to run SeizureTracker's test suites — the Firebase emulator wrapper every JVM/Robolectric and rules test needs, the rules suite, which subset to run when, and the known flake. Use before running or writing any test in this repo, and whenever a test run fails in a way that looks like infrastructure rather than a real assertion.
---

# Running SeizureTracker's tests

This is the canonical copy of these commands. They used to be duplicated across `CLAUDE.md`,
three agent personas and a hookify rule, which is four chances to drift.

## The one thing to know

Every suite except the pure unit tests talks to the **Firebase Local Emulator Suite**. Nothing
starts it for you. `./gradlew test` on its own **fails** — that is not a broken test, it is a
missing emulator. Wrap runs in `firebase emulators:exec`, which starts the emulator, runs the
command and tears it down.

`firebase.json` at the repo root pins the ports: Firestore 8080, Auth 9099.

## Commands

JVM + Robolectric suites (repositories, ViewModels, Compose screens):

```bash
firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore,auth "./gradlew test --stacktrace"
```

Security rules (`firestore.rules`) — Node + Jest, a separate suite:

```bash
cd firestore-tests && npm ci && firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore "npm test"
```

Compile only, when you just want to know it builds:

```bash
firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore,auth "./gradlew :app:compileDebugKotlin"
```

Narrow the JVM suite while iterating with `--tests`, e.g.
`"./gradlew test --tests '*SeizureListViewModelTest*'"`. A filtered run is fine for iteration and
**not** sufficient for a green marker.

The project id `demo-seizuretracker-rules-test` is deliberate: the `demo-` prefix makes the
emulator refuse to reach a real backend. These runs need no credentials and cannot touch real
data. They do not use `app/google-services.json`.

Phase 2 (Flutter): `flutter test`, `flutter analyze`, `dart run custom_lint`.

## After a green run

A plain `./gradlew build` immediately afterwards succeeds *without* the emulator — Gradle marks
the test tasks UP-TO-DATE rather than re-running them. That is what CI relies on
(`.github/workflows/ci.yml`), not a sign the tests ran standalone.

## Known traps

- **The timeout flake is not a regression.** The emulator-backed suite runs sequentially in one
  JVM fork doing real round-trips, so on a contended runner the slowest few tests overshoot their
  `withTimeout` budget. It is always a `TimeoutCancellationException`, never an assertion failure,
  and a different test each run. `app/build.gradle.kts` retries a failed test up to 3× **on CI
  only** (gated on the `CI` env var) so a local flake stays visible, with `maxFailures` (5) still
  failing the build fast on a real regression. Re-run once before reporting a failure. Prefer this
  over widening the timeout constant again.
- **Security Rules are not evaluated against the local cache.** A write that rules would reject is
  applied optimistically offline, then silently dropped on flush. Rules alone cannot stop a bad
  client action — the client has to be constrained too.
- **`fake_cloud_firestore` does not evaluate `firestore.rules`** (Phase 2). Rule coverage lives in
  `firestore-tests/`, and only there.
- **Rule changes need both directions.** Every new or reshaped path gets a positive test (a member
  can do X) and a negative one (a non-member, wrong-household or since-demoted user cannot).

## Who writes the green marker

`qa`, and only after a *fully* green run for the change under review — never on a failure or a
filtered-only run of something that needs the full suite. It is the input to a hard-blocking
merge-gate hook, so a marker that overstates what ran defeats the gate:

```bash
{ date -u +%Y-%m-%dT%H:%M:%SZ; echo "branch: $(git rev-parse --abbrev-ref HEAD)"; echo "ran: <what you ran>"; } > .claude/team/last-green
```

Verify the hooks that read it with `.claude/hooks/test-gates.sh`.
