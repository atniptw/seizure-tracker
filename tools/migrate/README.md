# `tools/migrate/` — pre-cutover backup & restore

The safeguard the rest of the Phase 1 migration stands on. `migration.md §4` step 0 is "take a
dump, run the rehearsal"; **no backfill area may be run against the live project until a dump of
that project exists and a restore from it has been rehearsed.**

| Script | What it does | Destructive? |
|---|---|---|
| `backup.js` | Recursive Admin SDK read of `households/{id}` + every subcollection + `codeIndex/*` → a timestamped JSON dump + a per-collection count manifest | No. Read-only; it has no write path at all. |
| `restore.js` | From a dump: delete the named collections, re-write every document, re-read and compare counts **and document-id sets** against the manifest, exit non-zero on any mismatch | **Yes, but only with `--commit`.** Without `--commit` it plans the work and writes nothing — that is the default, and there is no `--dry-run` flag to type. A live project additionally needs `--allow-prod`. |

`migrate.js` (the `--area=roles|joincode|observations|meds` backfill) is **not here yet** — it is
issues #6 / #7 / #9 / #10. This package delivers the dump/restore half independently.

## Read this before you run anything

**A dump is an unencrypted copy of a family's health record.** Every seizure entry, every
medication, every vet contact, in plain JSON, on a laptop. `security-privacy.md §2.1` lists it as
an asset in its own right and `§2.3` lists "someone who obtains the migration dump or
service-account key" as an actor with the same reach as the database itself. So:

- The dump directory (`tools/migrate/dumps/`) and any `*service-account*.json` are **gitignored**
  (see the bottom of the repo `.gitignore`). A leaked dump of two people's seizure history cannot
  be un-leaked by a later commit.
- Keep dumps off iCloud/Dropbox/Time Machine-synced locations. `dumps/` under the repo is fine
  only because the repo itself is not synced.
- Dump files are written `chmod 600`.
- **Delete the dumps once the `migration.md §7` cleanup has verified.** The exposure is meant to
  be time-boxed to the migration; it is not an ongoing backup (`security-privacy.md §2.4` — there
  is deliberately no cloud backup or PITR on the Spark plan).

Why a hand-rolled JSON dump instead of `gcloud firestore export`: managed export/import needs the
Blaze plan and a GCS bucket. `architecture.md §9` commits to never needing either — no billing
account on file, and nothing in the product requires one. Two people's history is a few thousand
documents, so the dump takes seconds and costs nothing.

## Install

```bash
cd tools/migrate
npm ci          # firebase-admin + jest
```

## Credentials

### Against the emulator — no credentials at all

Set `FIRESTORE_EMULATOR_HOST` and the Admin SDK talks to the emulator over an insecure channel
with no auth. This is the path for every test and for the second half of the rehearsal.

```bash
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8080
node backup.js --project=demo-seizuretracker-rules-test
```

The `demo-` prefix makes the emulator refuse to reach a real backend, so this cannot touch real
data even if the project id is wrong.

### Against the live project — gcloud ADC (preferred)

The Admin SDK bypasses Security Rules, which is the point (a migration has to write `role` fields
no client is allowed to write). It will authenticate with **either** of two credentials, and the
scripts accept both: your own gcloud **Application Default Credentials**, or a downloaded
**service-account key**. Prefer ADC. A key file is long-lived, and `security-privacy.md §2.3` lists
whoever holds it as an actor with the same reach as the whole database; ADC is a user credential
with nothing persistent to leak beyond a revocable token, and it goes away with one command.

```bash
gcloud auth application-default login       # once — writes the well-known ADC file (see below)
node backup.js --project=<prod-project-id>  # --project is REQUIRED on this path
```

- **`--project` (or `GOOGLE_CLOUD_PROJECT`) is required with ADC.** Unlike a key file, ADC carries
  no project id, so without one the SDK would fail late and confusingly. The scripts refuse to
  start instead.
- The signed-in account needs Firestore access on the project (the project owner does;
  `roles/datastore.user` is enough).
- The well-known file is `~/.config/gcloud/application_default_credentials.json`, or
  `$CLOUDSDK_CONFIG/application_default_credentials.json` if you set `CLOUDSDK_CONFIG`. Both
  scripts print which credential they resolved on the `Creds:` line of their banner — read it.
- If the SDK complains about a quota project:
  `gcloud auth application-default set-quota-project <prod-project-id>`.

**Revoke when the migration is done:** `gcloud auth application-default revoke` (see Cleanup
below). That is the whole cleanup on this path — there is no key to chase.

### Against the live project — a service-account key (alternative)

Use this only if ADC is not an option (a CI runner, or an account without project access).

1. Firebase console → the SeizureTracker project → **Project settings → Service accounts**.
2. **Generate new private key** → downloads a JSON key for the
   `firebase-adminsdk-…@<project>.iam.gserviceaccount.com` account.
3. Save it **outside the repo** — e.g. `~/.config/seizuretracker/prod-service-account.json`,
   `chmod 600`. (The repo `.gitignore` also covers `*service-account*.json` as a second line of
   defence, but outside the repo is the rule.)
4. Point the scripts at it per-command, not in a shell profile — a key exported in every shell is
   a key you will eventually run a script against by accident:

```bash
GOOGLE_APPLICATION_CREDENTIALS=~/.config/seizuretracker/prod-service-account.json \
  node backup.js --project=<prod-project-id>
```

`GOOGLE_APPLICATION_CREDENTIALS` wins over ADC when both are present (that is the Admin SDK's own
precedence, and the scripts report which one they used).

Least privilege, if you want it: instead of the default Firebase Admin SDK account (which is
broad), create a dedicated service account in the Google Cloud console with only
`roles/datastore.user` and download its key. Both scripts need nothing else.

**Delete the key when the migration is done.** Revoke it in the console (Service accounts → the
key → delete) rather than only removing the file.

### Either way

Make sure `FIRESTORE_EMULATOR_HOST` is **unset** when you mean to hit the live project — if it is
set, the Admin SDK silently talks to the emulator and you get an empty dump that looks successful.
Conversely, when it *is* set the scripts ignore whatever credentials are lying around and talk only
to the emulator, so ambient ADC cannot leak into an emulator run. Both scripts print their target
and their credential on the first two lines; read them.

### Cleanup when the migration is done

```bash
gcloud auth application-default revoke          # the ADC path: removes the local ADC file
                                                # (key-file path: delete the key in the console)
rm tools/migrate/dumps/*.json                   # once migration.md §7 has verified
```

## `backup.js`

```
node backup.js --project=<id> [options]

  --household=<id>[,<id>]  Only these households. Default: every household in the project.
  --out=<dir>              Default: tools/migrate/dumps
  --label=<text>           Appended to the filename, e.g. --label=pre-window
  --expect=legacy|target|none   Which collection set to assert (default legacy)
  --require-expected       Exit non-zero if an expected collection is absent (default: warn)
  --no-codeindex           Skip codeIndex/* (not recommended)
```

Subcollections are **discovered** with `listCollections()`, not read off a hardcoded list, so a
collection added later is dumped rather than silently missed — and any collection the migration
does not know about is reported as `collection not known to this migration`. The known-expected
set is asserted on top of that:

- `--expect=legacy` (the shipped shape): `seizures`, `healthNotes`, `pets`, `vets`, `petVetLinks`,
  `members`.
- `--expect=target` (after the backfill, `migration.md §3`): `observations`, `pets`, `vets`,
  `petVetLinks`, `members`, `private`.

A missing expected collection is a **warning** by default, not a failure, and deliberately so: in
Firestore an empty collection does not exist, so a household that has never logged a health note
genuinely has no `healthNotes` collection and there is no way to tell that apart from a crawl that
missed it. Pass `--require-expected` when you know what the data should contain — the pre-window
rehearsal should.

The dump also records, in `manifest`:

- `counts` — documents per collection path. This is what `restore.js` verifies against.
- `missingParents` — documents that hold subcollections but have no fields of their own. These are
  real and the crawl keeps them (it uses `listDocuments()`, not `collection.get()`, precisely so
  their subtree is not dropped): a pet hard-deleted while its medications remained is exactly this
  shape.
- `unknownCollections`, `expectationProblems`, `integralDoubleFields` (see below).

## `restore.js`

```
node restore.js <dump.json> --project=<id> [--commit] [options]

  --only=<colPath>[,...]   Restore only these collections and everything nested beneath them
  --codeindex=scoped|all|none   Which codeIndex docs to clear first (default scoped)
  --allow-prod             Required when FIRESTORE_EMULATOR_HOST is unset
  --allow-project-mismatch Required when the dump's project id differs from the target
```

It deletes, re-writes, then **re-reads and verifies**: per-collection counts against the dump's
manifest, plus a document-id set diff (counts alone would pass a document written under the wrong
id). Any mismatch is listed and the exit code is 1. Writes and deletes are batched in chunks of
400 (`migration.md §5`; Firestore's hard limit is 500).

`codeIndex` is a global collection, not a household subcollection, so `--codeindex=scoped` (the
default) only clears codes that are in the dump or point at a household in the dump; anything else
is left in place and reported. A dump narrowed with `--household` narrows its `codeIndex` to match,
so it stays a self-consistent unit.

### One thing it cannot round-trip: an integral double

The Node Admin SDK's serializer encodes any JS `number` that passes `Number.isSafeInteger()` as a
Firestore **integer**, and offers no way to force a double. So a Firestore double of exactly `12.0`
comes back out of a restore as the integer `12`. There is no workaround from Node.

Both scripts report every affected field by path (`integralDoubleFields` in the manifest,
`WARNING: … will come back as Firestore integers` on the console) so it is never a surprise during
the `migration.md §5` verification gate. Practical impact is small: the only double in the shipped
shape is `Pet.weightKg`, the Firebase Android SDK widens an integer to a `Double?` field on read,
and Firestore's own numeric comparisons span integer and double. Non-integral doubles, negative
zero, `NaN` and the infinities all round-trip exactly. See the comment on
`collectIntegralDoubles` in `lib/codec.js`.

## Restore procedure (`migration.md §5`)

**Before cutover** (no writes from the new build yet — a lossless rollback):

```bash
cd tools/migrate
unset FIRESTORE_EMULATOR_HOST
# Credentials: `gcloud auth application-default login` (preferred), or export
# GOOGLE_APPLICATION_CREDENTIALS=~/.config/seizuretracker/prod-service-account.json

# 1. Dry run first (no --commit = nothing is written). Read the plan and the counts.
node restore.js dumps/<the-window-dump>.json --project=<prod-project-id> --allow-prod

# 2. Commit. Must end with "OK: every restored collection matches the dump manifest".
node restore.js dumps/<the-window-dump>.json --project=<prod-project-id> --allow-prod --commit
```

Then redeploy the previous `firestore.rules` and the previous app build. If step 2 prints mismatches
instead, **stop** — do not deploy anything, keep the dump, and work out why.

**After cutover** there is no lossless restore: entries logged by the new build exist only in the
new shape, so a restore from a pre-window dump loses them. The options are (a) fix forward with a
corrective `migrate.js` pass, or (b) restore and manually re-enter whatever was logged since the
dump. At two users that manual re-entry is the accepted fallback (`migration.md §9`).

## The real-data rehearsal (`migration.md §5` "Testing the tooling")

Required before the window, and it needs credentials for the prod project — gcloud ADC is enough
(preferred; see Credentials above). Run it top to bottom; every command is here so nothing has to be
reconstructed under pressure.

```bash
cd /Users/tom/Claude/Projects/SeizureTracker/tools/migrate
npm ci

PROD=<prod-project-id>          # the real Firebase project
EMU=demo-seizuretracker-rules-test

# ---- 1. Dump prod. Read-only: backup.js has no write path. ----
unset FIRESTORE_EMULATOR_HOST
gcloud auth application-default login       # preferred; or export GOOGLE_APPLICATION_CREDENTIALS
node backup.js --project=$PROD --label=rehearsal --expect=legacy --require-expected
# -> confirm the banner says the LIVE project, not an emulator, and names the credential you meant
# -> note the manifest counts; keep the filename:
PROD_DUMP=$(ls -t dumps/dump-$PROD-*-rehearsal.json | head -1)
# -> scope: the project holds THREE households (migration.md §2 — the live one plus two 08-16
#    test households) and three codeIndex entries. The default dumps all of them, which is what
#    the rehearsal wants. --household=<id> narrows it, but --require-expected is evaluated per
#    collection *template* across the whole dump, so a dump narrowed to a household that has no
#    subcollections at all will fail it — correctly.

# ---- 2. Sanity-check the counts against the app before trusting them ----
# Open the app: the number of seizures + health notes, the pet list, the member list. If the
# manifest says fewer entries than the app shows, stop — the dump is not complete.

# ---- 3. Load the dump into the emulator (a different shell for the emulator) ----
unset GOOGLE_APPLICATION_CREDENTIALS        # if you used the key-file path. ADC is ambient and
                                            # cannot be unset per-shell — that is fine: with
                                            # FIRESTORE_EMULATOR_HOST set the scripts talk only to
                                            # the emulator, and a live target needs --allow-prod.
# shell B:
#   /Users/tom/.nvm/versions/node/v24.13.0/bin/firebase emulators:start --project $EMU --only firestore
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8080
node restore.js "$PROD_DUMP" --project=$EMU --allow-project-mismatch --commit
# -> must end: "OK: every restored collection matches the dump manifest, document for document."

# ---- 4. Prove it from a clean slate too — this is the step that matters ----
curl -X DELETE "http://127.0.0.1:8080/emulator/v1/projects/$EMU/databases/(default)/documents"
node restore.js "$PROD_DUMP" --project=$EMU --allow-project-mismatch --commit
# -> same OK line, same counts

# ---- 5. Dump the emulator and diff the manifests ----
node backup.js --project=$EMU --out=/tmp/rehearsal --label=from-emulator
EMU_DUMP=$(ls -t /tmp/rehearsal/*.json | head -1)
manifest() { node -e 'const p=require("path");console.log(JSON.stringify(require(p.resolve(process.argv[1])).manifest.counts,null,2))' "$1"; }
diff <(manifest "$PROD_DUMP") <(manifest "$EMU_DUMP")
# -> no output. (The only expected difference is `codeIndex`, if prod holds codes for households
#    outside the dump; backup.js prints a warning naming each one.)

# ---- 6. Clean up ----
rm -rf /tmp/rehearsal
gcloud auth application-default revoke      # or delete the service-account key in the console
# Keep $PROD_DUMP until the migration.md §7 cleanup verifies, then delete it.
```

Once the emulator holds real-shaped data, it is also the input for the backfill rehearsal in
`migration.md §5` — but that needs `migrate.js`, which lands with issues #6 / #7 / #9 / #10.

## Tests

Its own Node + Jest package, run against the emulator like `firestore-tests/`:

```bash
cd tools/migrate && npm ci
firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore "npm test"
```

`__tests__/roundtrip.test.js` seeds the "legacy shape" household from `migration.md §5` — old
collections, a `code` field, no roles, an embedded medications array (including two entries that
differ only in `notes`), a `members` array uid with no profile doc, a doc with
`timestampMillis == 0`, a fieldless pet that still owns a medications subcollection, a collection
the migration has never heard of — then dumps it, wipes and corrupts the household, restores, and
asserts the documents come back field-for-field with their Firestore types intact.

It is a **separate package from `firestore-tests/`** on purpose: that package tests
`firestore.rules` with `@firebase/rules-unit-testing` and the client SDK and is owned by
`rules-engineer`, while this one uses `firebase-admin` and deliberately *bypasses* rules. Sharing
one package would put a rules-bypassing admin suite in the rules owner's directory and make every
rules-suite `npm ci` pull `firebase-admin`.

**CI is not wired up for this package yet** — `.github/workflows/ci.yml` needs two steps added
after the existing `firestore-tests` ones:

```yaml
      - name: Install migration tooling dependencies
        run: npm ci
        working-directory: tools/migrate

      - name: Migration tooling round-trip tests (Firebase emulator)
        run: firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore "npm test"
        working-directory: tools/migrate
```

## Environment notes

- The `firebase` CLI is not on the default node's `PATH`. It is at
  `/Users/tom/.nvm/versions/node/v24.13.0/bin/firebase`.
- `gcloud` is authenticated on this machine; the ADC file
  (`~/.config/gcloud/application_default_credentials.json`) is created by
  `gcloud auth application-default login` and is a separate thing from `gcloud auth login`. Until
  it exists, a live-project run stops with an error naming all three credential options.
- The emulator ports come from the repo-root `firebase.json`: Firestore 8080, Auth 9099.
- `firebase emulators:exec` sets `FIRESTORE_EMULATOR_HOST=127.0.0.1:8080` itself, so commands run
  inside it need no export.
