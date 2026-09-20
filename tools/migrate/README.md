# `tools/migrate/` — pre-cutover backup & restore

The safeguard the rest of the Phase 1 migration stands on. `migration.md §4` step 0 is "take a
dump, run the rehearsal"; **no backfill area may be run against the live project until a dump of
that project exists and a restore from it has been rehearsed.**

| Script | What it does | Destructive? |
|---|---|---|
| `backup.js` | Recursive Admin SDK read of `households/{id}` + every subcollection + `codeIndex/*` → a timestamped JSON dump + a per-collection count manifest | No. Read-only; it has no write path at all. |
| `restore.js` | From a dump: **empty the households in the dump's scope** — including collections the dump never saw — then re-write every document, re-read and compare **counts, document-id sets and every field value** against the dump, exit non-zero on any mismatch | **Yes, but only with `--commit`, and it is irreversible.** Without `--commit` it plans the work and writes nothing — that is the default, and there is no `--dry-run` flag to type. A live project additionally needs `--allow-prod`. |

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
- **A failed restore prints real field values to your terminal.** The verification gate names each
  differing field and shows both values in full (up to 40 lines), which is what makes a failure
  actionable — but it means a `FAILED` run puts seizure descriptions and medication notes into
  shell scrollback and any transcript of that session. Only on a failure, only on your own
  terminal, and worth knowing before you paste the output anywhere.

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
(Anything else `applicationDefault()` accepts — the workload-identity credential types — works too
and is named by its own type in the banner. Neither of the two paths below is it, and nobody here
has a reason to use one.)

```bash
gcloud auth application-default login       # once — writes the well-known ADC file (see below)
node backup.js --project=<prod-project-id>  # --project is REQUIRED on this path
```

- **`--project` (or `GOOGLE_CLOUD_PROJECT`) is required on this path — always, whatever is sitting
  at the ADC path.** ADC carries no project id, so without one the SDK would fail late and
  confusingly; the scripts refuse to start instead. The requirement is unconditional even if the
  file there happens to be a service-account key that names its own project: the ADC path is
  *ambient*, not something you named in the command you are running, and `restore.js --commit`
  deletes and rewrites whatever it is pointed at (`--allow-prod` names no project). On this path the
  target project is always typed out loud, and a `--project` that disagrees with an ADC-path key's
  own `project_id` is not a conflict to resolve — the file's id is never read, so the project you
  typed is the only one the scripts can act on.
- The signed-in account needs Firestore access on the project (the project owner does;
  `roles/datastore.user` is enough).
- The well-known file is `~/.config/gcloud/application_default_credentials.json`, or
  `$CLOUDSDK_CONFIG/application_default_credentials.json` if you set `CLOUDSDK_CONFIG`. Both
  scripts print what they resolved on the `Creds:` line of their banner — **read it.** It names the
  file's actual type *and* where it was found, in that order, because the two are independent:
  `user credentials at the gcloud ADC path (…)` is the ADC you meant, and `service-account key at
  the gcloud ADC path (…)` is a downloaded key someone left there — the reach of the whole database,
  on the path documented here as the safe one.
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
precedence, and the scripts report which one they used). What the scripts check is the *file*, not
the variable: `GOOGLE_APPLICATION_CREDENTIALS` accepts any ADC file, so if you point it at gcloud
user credentials (`"type": "authorized_user"`) rather than a downloaded key, it carries no
`project_id` and `--project` becomes required — the scripts say so up front instead of letting the
SDK fail at the first RPC with "Client is not yet ready to issue requests".

This is the **one** way to not type `--project`: a `service_account` key, named by that variable in
the command you are running, may supply the project it belongs to. Nothing else can — not an
`authorized_user` file however it was named, and not anything at the ADC path (see above).

Two other refusals — on this path and on the ADC one alike, both up front rather than at the first
RPC:

- A file with **no `"type"`** is rejected as not a credential file at all — a `firebase.json`, a
  `google-services.json` or a truncated key gets an error naming the path, not a banner calling it a
  service-account key.
- A file with a `"type"` the scripts do not specifically know (the workload-identity family:
  `external_account`, `impersonated_service_account`, …) is **accepted** — the Admin SDK supports
  them and this tool does not second-guess that list — and the banner prints that type verbatim
  rather than claiming it is a key. Like `authorized_user`, they name no project, so `--project` is
  required.

No refusal ever quotes the file's contents, only its path: a credential file is secret material, and
you can read your own file.

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
does not know about is reported as `collection not known to this migration`, at **any** depth
(a collection below the deepest level the migration has a list for, e.g.
`households/{h}/observations/{o}/attachments`, is reported because its whole level is unknown).
The known-expected set is asserted on top of that:

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

**What it removes.** With `--only` unset, the delete set is built by crawling the **live**
household, not by reading the dump — so it removes collections the dump never contained, including
anything a backfill created (an `observations` collection, say). That is correct and necessary for
a rollback, and it is what makes the result *the dump* rather than the dump merged over whatever is
there. It also means a restore is **not** a partial operation: it empties the households in the
dump's scope and rewrites them.

**Flags are parsed strictly, in both directions, because every loose spelling of one lands on the
same hazard — a run wider or more permissive than what was typed, with nothing on screen to say so:**

- **A value-taking flag must be written `--flag=value`.** `--only households/h1/seizures` with a
  space is rejected, not parsed — as a bare `--only` it used to mean "everything", which in the one
  script that deletes turned a one-collection restore into a full-household one.
- **A boolean flag must be written bare.** `--allow-prod=false` is rejected rather than read as the
  truthy string `"false"`, which would *open* the gate it looks like it closes. A boolean is on when
  you name it and off when you leave it out; there is no `=false` spelling of "off".
- **An unknown flag is rejected.** `--onyl=households/h1/seizures` would otherwise leave `--only`
  unset, and unset means "everything".

`--only` takes **collection** paths (an odd number of segments); a document path like
`households/h1` is rejected, because it would select `h1`'s subcollections but not `h1`'s own
document. It is also checked **against the dump**: a path that selects nothing in it — the singular
typo `…/seizure`, or a household id that is not in the dump — is refused, because a run that
restores nothing verifies nothing and would then print the same `OK` line a real restore prints.
The `Scope:` banner line always names the selection, including when the selection is "everything in
the dump".

**What the verification gate proves.** It deletes, re-writes, then re-reads and compares the result
against the dump three ways:

1. **Per-collection counts** against `manifest.counts`.
2. **The document-id set** — counts alone would pass a document written under the wrong id.
3. **Every field value of every document it wrote**, deep-compared against the dump's own
   contents. Field names, nesting, array order, and Firestore types (integer vs double vs
   timestamp vs bytes) all have to match. The verify crawl already returns each document in the
   dump's encoded format, so this is a comparison and not a second read.

The **one** tolerated difference is the integral double below, and every tolerated field is
reported by path and cross-checked against `manifest.integralDoubleFields`. Any other difference
is listed and the exit code is 1. The `OK` line carries the number of count checks, id checks and
documents compared, and a run that compared **nothing** fails rather than passing: zero
disagreements is only evidence of a good restore if something was actually compared. Writes and deletes are batched in chunks of 400
(`migration.md §5`; Firestore's hard limit is 500) — that bounds documents per commit, not request
bytes, which is ample for this dataset but is a document-count guard only.

The dump is also fully **decoded during planning**, before anything is deleted, so a malformed or
hand-edited dump is refused while the target is still untouched — and a dry run catches it too.

`codeIndex` is a global collection, not a household subcollection, so `--codeindex=scoped` (the
default) only clears codes that are in the dump or point at a household in the dump; anything else
is left in place and reported. A dump narrowed with `--household` narrows its `codeIndex` to match,
so it stays a self-consistent unit. `--codeindex=none` clears nothing and still rewrites (and
verifies) the codes the dump holds. A dump taken with `--no-codeindex` holds no codes at all, so
**restoring one is refused unless you pass `--codeindex=none`** — under any other mode it would
delete the live join codes of the households in the dump and have nothing to write back, leaving
them advertising a `code` that resolves to nothing.

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

### The rest of the fidelity inventory

Everything else in the shipped and target shapes round-trips exactly — field names containing
dots, slashes or spaces; integers past `Number.MAX_SAFE_INTEGER`; empty maps and arrays; arrays of
maps; and user maps whose single key is literally `@int` or `@map` (the codec double-wraps those).
The remaining limits, all deliberate:

- **A `DocumentReference` is re-rooted.** It is encoded as its project-relative path and decoded
  against the *target* instance, so restoring a dump into a different project or database silently
  re-points the reference at that project. Correct for the rehearsal (prod dump → emulator) and
  there are no references anywhere in the shipped shape, but it is not a byte-for-byte round trip.
- **An unrecognised Firestore value type aborts the dump** rather than being silently dropped.
  `firebase-admin ^13.6.0` ships `VectorValue`, which nothing here uses; a document containing one
  would fail `backup.js` with `Unsupported Firestore value of type …`. That is the intended
  behaviour — a dump that quietly omits a field is worse than no dump.
- **A hand-edited dump is validated on decode, not trusted.** Every number in a dump is tagged,
  and there is exactly one spelling of each: `{"@double": <JSON number>}` (or one of the strings
  `"NaN"`, `"Infinity"`, `"-Infinity"`, `"-0"`) and `{"@int": "<decimal string>"}`. The three
  plausible hand edits are all refused during planning, before anything is deleted:
  `{"@double": "12.5"}` (which used to restore `weightKg` as the *string* `"12.5"`, read back as
  `null` by the Kotlin `Double?` field), a bare `12.5`, and a numeric `{"@int": 12}`. The last two
  restored the right value but then failed the content compare — a `FAILED` verdict over correct
  data, *after* the delete — because the gate compares one encoded form against one encoded form.

## Restore procedure (`migration.md §5`)

### Read this first — what the commit step actually does

> **`restore.js --commit` is irreversible.** It does not "roll back to" the dump. It **deletes
> every document in the households the dump names** — including collections the dump never saw,
> such as an `observations` collection a backfill just created — and then writes the dump's
> documents in their place. After the delete pass has committed, the only copy of what was there
> is whatever dump *you* took beforehand. There is no undo, no transaction around it, and no
> server-side backup on the Spark plan (`security-privacy.md §2.4`).

So the procedure has three steps, not two, and step 1 is not optional.

### Before cutover (no writes from the new build yet)

```bash
cd tools/migrate
unset FIRESTORE_EMULATOR_HOST
# Credentials: `gcloud auth application-default login` (preferred), or export
# GOOGLE_APPLICATION_CREDENTIALS=~/.config/seizuretracker/prod-service-account.json

# ---- 1. Dump the CURRENT state first. Read-only, takes seconds, and it is the only copy of
#         whatever the backfill wrote. Do not skip this because the rollback is "to a known good
#         dump" — the state you are about to destroy is the state you may need to diff against.
node backup.js --project=<prod-project-id> --label=pre-rollback --expect=target
# -> keep the filename. This is your undo for the undo.

# ---- 2. Dry run the restore (no --commit = nothing is written). ----
node restore.js dumps/<the-window-dump>.json --project=<prod-project-id> --allow-prod
# -> read three things before continuing:
#      * "Scope: households=[...] codeIndex=... only=..."  — is that the set you meant?
#      * "Plan: delete N document reference(s), write M document(s)" — N is what is about to be
#        destroyed. If N is much larger than M, something is in the target that is not in the
#        dump; understand what before you commit. If either number is 0 or far smaller than you
#        expected, STOP: the scope is not what you meant. A run with nothing to delete and nothing
#        to write is refused outright (it could not be verified), but a run that would restore only
#        part of what you intended is not — that one only shows up in these numbers.
#      * the per-collection count table.

# ---- 3. Commit. Same command plus --commit. IRREVERSIBLE. ----
node restore.js dumps/<the-window-dump>.json --project=<prod-project-id> --allow-prod --commit
# -> must end with "OK: every restored collection matches the dump manifest — all C
#    per-collection count(s), the document-id set (all I id(s)), and a field-by-field value compare
#    of all N document(s)." Read the numbers, not just the word OK: N is how many documents were
#    compared field by field, and it should be the count the dry run planned. On a clean restore
#    I == N — every document written is a document found.
```

Then redeploy the previous `firestore.rules` and the previous app build. If step 3 prints
mismatches instead, **stop** — do not deploy anything, keep both dumps, and work out why.

### If a restore is interrupted

`restore.js` commits batches sequentially with **no outer transaction** (`lib/firestore.js`
`commitInChunks`). If the delete pass fails or the process is killed part-way — a dropped network,
a `^C`, a batch error — you are left with an arbitrary subset of the households deleted and
nothing written back, and the script exits 2. This is the one state where it matters that you know
what to do next, so:

> **Re-run the identical command, including `--commit`.** That is safe and it is the correct
> recovery. The restore is idempotent: the delete pass re-crawls the live tree (whatever is left of
> it) and the write pass re-writes the dump's documents at fixed paths with `set()`, so a second run
> completes a partial one and a second run of a *complete* one is a no-op. Both are covered by the
> test suite (`a second --commit restore of the same dump is a no-op`,
> `__tests__/roundtrip.test.js`).

Do **not** reach for `--only` to "finish off" what the first run missed, and do not hand-edit the
dump. Neither is needed, and `--only` narrows the delete pass too, so it can leave the stale
documents the full run would have removed.

If the *write* pass is what failed, the same answer applies for the same reason.

### After cutover

There is no lossless restore: entries logged by the new build exist only in the new shape, so a
restore from a pre-window dump loses them. The options are (a) fix forward with a corrective
`migrate.js` pass, or (b) restore and manually re-enter whatever was logged since the dump. At two
users that manual re-entry is the accepted fallback (`migration.md §9`) — and step 1 above is what
turns "re-enter it from memory" into "re-enter it from a file".

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
                                            # the emulator; and a live target needs --allow-prod AND
                                            # a --project you typed, which the ADC path never
                                            # supplies for you whatever file is sitting there.
# shell B:
#   /Users/tom/.nvm/versions/node/v24.13.0/bin/firebase emulators:start --project $EMU --only firestore
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8080
node restore.js "$PROD_DUMP" --project=$EMU --allow-project-mismatch --commit
# -> must end: "OK: every restored collection matches the dump manifest — all C per-collection
#    count(s), the document-id set (all I id(s)), and a field-by-field value compare of all N
#    document(s)." The numbers carry the signal: N is how many documents were compared field by
#    field, and it must match what the dry run planned. I == N on a clean restore.

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

Four suites:

- `__tests__/roundtrip.test.js` — the dump/restore round trip against the legacy-shape fixture,
  plus QA's coverage-gap cases (empty collections, post-dump documents, re-running a `--commit`
  restore, a multi-batch commit).
- `__tests__/review-regressions.test.js` — one case per finding of the #5 pre-merge review:
  the `--no-codeindex` refusal, `--codeindex=none` symmetry, the single-scope key set, content
  verification (fault-injected at the batch layer, so the dump on disk genuinely disagrees with
  the target), the `--only` parse guards and banner, the unknown-collection depth hole, the
  `@double` payload guard, and the dump directory's mode.
- `__tests__/codec.test.js` — the codec's decode guards and the content comparator, no emulator.
- `__tests__/credentials.test.js` — credential resolution (ADC vs key file), no emulator.

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
