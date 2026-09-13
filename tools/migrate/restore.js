#!/usr/bin/env node
'use strict';

// restore.js — migration.md §5 "Restore procedure".
//
// From a backup.js dump: **empty the households in the dump's scope** — the delete set is built by
// crawling the *live* tree, so it removes collections the dump never saw, which is what makes this
// a rollback rather than a merge — then re-write every document from the dump, re-read the result
// and compare it against the dump three ways: per-collection counts against the manifest, the
// document-id set, and every field value of every document against the dump's own contents.
// Reports every mismatch and exits non-zero if there is one.
//
// Destructive, but only with --commit: with no --commit it plans the work, prints it and writes
// nothing (there is no --dry-run flag — a dry run is what you get by default). A run against a live
// project additionally needs --allow-prod. There is no outer transaction, so an interrupted
// --commit run can leave a household partly deleted and nothing written back; the recovery is to
// re-run the identical command (see "If a restore is interrupted" in README.md).

const fs = require('fs');
const {
  initFirestore, crawlDocument, crawlCollection, newReport, forgetSubtree, flattenForWrite,
  collectRefsDeepestFirst, commitInChunks, decodeDocument, compareEncodedDocument, newComparison,
  BATCH_SIZE, READ_CHUNK,
} = require('./lib/firestore');
const {
  parseArgs, list, log, warn, countTable, describeCredential, exitWhenFlushed,
  HEALTH_DATA_WARNING,
} = require('./lib/cli');
const { FORMAT } = require('./backup');

const USAGE = `
Usage: node restore.js <dump.json> --project=<id> [--commit] [options]

  --project=<id>            Firebase/GCP project id (or GOOGLE_CLOUD_PROJECT).
  --commit                  Actually delete and re-write. Without it this is a dry run.
  --only=<colPath>[,...]    Restore only these collections and everything nested beneath them,
                            e.g. --only=households/h1/seizures,households/h1/healthNotes.
                            COLLECTION paths only (an odd number of segments) — a document path
                            like households/h1 is rejected, because it would select h1's
                            subcollections but not h1's own document. Checked against the dump:
                            a path that selects nothing in it is rejected too.
                            Default: everything in the dump.
  --codeindex=scoped|all|none
                            Which codeIndex docs to delete before re-writing (default scoped:
                            only docs in the dump or pointing at a household in the dump — the
                            collection is global, so "all" would also drop other households').
  --allow-prod              Required when FIRESTORE_EMULATOR_HOST is unset. Read that twice.
  --allow-project-mismatch  Required when the dump's project id differs from the target. This is
                            the normal case for the rehearsal (prod dump -> emulator).

Every value-taking flag must be written --flag=value: "--only households/h1/seizures" (with a
space) is rejected rather than parsed as a bare --only, which would mean "everything". Every
boolean flag must be written bare: --allow-prod=false is rejected rather than read as the truthy
string "false", which would OPEN the gate it looks like it closes. An unknown flag is rejected.

Emulator:  FIRESTORE_EMULATOR_HOST=localhost:8080 node restore.js dumps/dump-....json \\
             --project=demo-seizuretracker-rules-test --allow-project-mismatch --commit
`;

/** Flags that must be written --flag=value. See lib/cli.js parseArgs for why this list exists. */
const VALUE_FLAGS = ['project', 'only', 'codeindex', 'dump'];
/**
 * Flags that must be written bare. The other half of the same guard, and the half that matters
 * most here: two of these three are the gates between a --commit run and the live project, and
 * `--allow-prod=false` used to open them (a non-empty string is truthy). parseArgs refuses a value
 * on any of these, and the gates below additionally test `=== true` rather than truthiness.
 */
const BOOLEAN_FLAGS = ['commit', 'help', 'h', 'allow-prod', 'allow-project-mismatch'];

/** How many field-level differences to print before summarising the rest. */
const MAX_DIFFS_SHOWN = 40;

/** True if `colPath` is one of `only`, or nested beneath it. Empty `only` selects everything. */
const selected = (colPath, only) =>
  only.length === 0 || only.some((o) => colPath === o || colPath.startsWith(`${o}/`));

/**
 * True if `only` selects anything at or beneath `colPath` — `selected()` plus the other direction,
 * i.e. an `only` entry nested *below* `colPath`.
 *
 * `selected('codeIndex', only)` is the wrong question for "is codeIndex part of this run": with
 * `--only=codeIndex/QQQ/history` it answers no, while the write-job filter (which tests each
 * document's own collection) answers yes — so documents were written and then never verified,
 * which surfaces as `missing from target` → FAILED, after the delete has committed. Every user of
 * this must apply `selected()` per document as well, exactly as the households pass does; this
 * only decides whether the collection is in play at all.
 */
const intersects = (colPath, only) =>
  selected(colPath, only) || only.some((o) => o.startsWith(`${colPath}/`));

const collectionOf = (docPath) => docPath.split('/').slice(0, -1).join('/');

function loadDump(file) {
  if (!file) throw new Error(`no dump file given${USAGE}`);
  const dump = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (dump.format !== FORMAT) {
    throw new Error(`${file} is not a ${FORMAT} dump (found format="${dump.format}")`);
  }
  if (!dump.manifest || !dump.manifest.counts) throw new Error(`${file} has no manifest.counts`);
  if (!dump.collections) throw new Error(`${file} has no collections`);
  if (!dump.collections.households || typeof dump.collections.households !== 'object') {
    throw new Error(`${file} has no collections.households object`);
  }
  // These two are dereferenced unguarded a few lines into main(), where a truncated or
  // hand-assembled dump would die on a bare TypeError instead of the deliberate message this
  // function exists to produce.
  if (!dump.source || typeof dump.source.projectId !== 'string') {
    throw new Error(`${file} has no source.projectId — truncated, or not written by backup.js?`);
  }
  if (!dump.scope || !Array.isArray(dump.scope.households)) {
    throw new Error(`${file} has no scope.households array — truncated, or not written by backup.js?`);
  }
  // Same class as the two guards above, and the same consequence. `scope.includeCodeIndex` is read
  // as a tri-state by everything downstream (`!== false` means "in scope"), so a dump missing the
  // key reads as a dump that *contains* codeIndex: under the default `scoped` mode the planner then
  // deletes every live code pointing into scope with nothing in the dump to write back. The count
  // check does catch it now — but only after the delete has committed, which is the ordering this
  // whole fix set exists to eliminate. backup.js always writes the key.
  if (typeof dump.scope.includeCodeIndex !== 'boolean') {
    throw new Error(
      `${file} has no boolean scope.includeCodeIndex — truncated, or not written by backup.js? ` +
        'Without it the restore cannot tell a dump that omitted codeIndex from one that contains ' +
        'it, and guessing wrong deletes join codes it cannot rebuild.'
    );
  }

  // The mirror-image inconsistency, refused on the same grounds as the scope.households orphans
  // below: a dump claiming it omitted codeIndex while carrying codeIndex documents. Everything
  // downstream reads the flag, so those documents would be written and then not verified —
  // `missing from target`, FAILED, after the delete. backup.js writes `{}` under --no-codeindex,
  // so this too is one hand edit away and none of its own making.
  if (dump.scope.includeCodeIndex === false && Object.keys(dump.collections.codeIndex || {}).length) {
    throw new Error(
      `${file} is internally inconsistent: scope.includeCodeIndex is false but ` +
        `collections.codeIndex holds ${Object.keys(dump.collections.codeIndex).length} code(s). ` +
        'A restore would write them and then not verify them. Re-take the dump.'
    );
  }

  // A dump carries two notions of "which households": `scope.households`, which backup.js fills
  // with the households that have a document of their own, and the keys of
  // `collections.households`, which is everything the crawl dumped — including a household that
  // owns subcollections but holds no fields. main() drives the delete, the write and the
  // verification off the keys (see there for why). A scope entry with no matching key means the
  // two disagree: it is one hand edit away, and it used to mean "delete this household in full and
  // restore nothing", failing only *after* the delete had committed. Refuse to start instead.
  const dumped = new Set(Object.keys(dump.collections.households));
  const orphans = dump.scope.households.filter((id) => !dumped.has(id));
  if (orphans.length) {
    throw new Error(
      `${file} is internally inconsistent: scope.households names ${orphans.length} household(s) ` +
        `with no entry under collections.households (${orphans.join(', ')}). A restore would ` +
        'delete them and have nothing to write back. Re-take the dump, or fix scope.households.'
    );
  }
  return dump;
}

/**
 * Every collection path this dump could restore: the manifest's own count keys, plus the
 * collections actually present in the dumped tree (which covers a hand-trimmed manifest), plus the
 * two roots. Used to check `--only` against the dump rather than only against its own shape.
 */
function dumpCollectionPaths(dump) {
  const paths = new Set(['households']);
  const walk = (node, docPath) => {
    for (const [colId, docs] of Object.entries((node && node.collections) || {})) {
      paths.add(`${docPath}/${colId}`);
      for (const [docId, child] of Object.entries(docs)) walk(child, `${docPath}/${colId}/${docId}`);
    }
  };
  for (const [id, node] of Object.entries(dump.collections.households)) walk(node, `households/${id}`);
  if (dump.collections.codeIndex) {
    paths.add('codeIndex');
    for (const [id, node] of Object.entries(dump.collections.codeIndex)) walk(node, `codeIndex/${id}`);
  }
  for (const key of Object.keys(dump.manifest.counts)) paths.add(key);
  return paths;
}

/**
 * --only takes collection paths that exist in the dump.
 *
 * Two separate refusals, because a bad `--only` has two bad shapes:
 *
 *  * An even segment count is a *document* path: it would restore a subtree but not its root doc.
 *  * A path that selects nothing in the dump is the plausible typo — `households/h1/seizure`
 *    (singular) is an odd three segments, so it passes the shape check, and `selected()`'s
 *    `${o}/` prefix guard then correctly refuses to let it bleed onto `seizures`. The result was a
 *    run that planned no deletes, no writes and no verification, and ended on the OK line with
 *    exit 0 — "verified nothing" rendered as "verified everything", which is the exact line
 *    `migration.md §7`'s irreversible cleanup delete is gated on.
 */
function checkOnlyPaths(only, dump) {
  const known = dump ? dumpCollectionPaths(dump) : null;
  for (const o of only) {
    if (o.split('/').length % 2 === 0) {
      throw new Error(
        `--only takes collection paths, and "${o}" has an even number of segments, which makes it ` +
          'a document path. It would select that document\'s subcollections but not the document ' +
          'itself — so e.g. --only=households/h1 would delete and rewrite h1\'s whole subtree ' +
          'while leaving h1\'s own document untouched and stale. Name the collections instead ' +
          `(e.g. --only=${o}/seizures), or drop --only to restore everything in the dump.`
      );
    }
    if (!known) continue;
    // Matched = it names a collection in the dump, or an ancestor of one (`--only=households`).
    const matches = [...known].some((p) => p === o || p.startsWith(`${o}/`));
    if (!matches) {
      // Siblings first (same parent document), then anything else, capped: the list is collection
      // paths, not documents, but a dump with many pets still has more than fits on a screen.
      const parent = o.split('/').slice(0, -1).join('/');
      const siblings = [...known].filter((p) => p.startsWith(`${parent}/`) && !p.slice(parent.length + 1).includes('/'));
      const shown = (siblings.length ? siblings : [...known]).sort().slice(0, 20);
      throw new Error(
        `--only=${o} selects nothing in this dump: no collection in it is "${o}" or nested under ` +
          'it. This run would delete nothing, write nothing and verify nothing — and a gate that ' +
          'verified nothing would print the same OK line a real restore prints, which is why this ' +
          'is refused rather than reported at the end. Check the spelling (a singular collection ' +
          `name is the usual cause).\nCollections the dump does hold${siblings.length ? ' at that level' : ''}:\n  ` +
          `${shown.join('\n  ')}`
      );
    }
  }
}

async function main(argv) {
  const { flags, positional } = parseArgs(argv, {
    valueFlags: VALUE_FLAGS, booleanFlags: BOOLEAN_FLAGS,
  });
  if (flags.help || flags.h) { log(USAGE); return 0; }

  // Exactly one positional (the dump file). An extra one is almost always the value half of a
  // space-separated flag that parseArgs has already rejected — but if a new flag is ever added
  // without being declared in VALUE_FLAGS, this is the second net under the same mistake.
  if (positional.length > 1) {
    throw new Error(
      `unexpected extra argument(s): ${positional.slice(1).join(' ')}. The only positional ` +
        'argument is the dump file; every flag is --flag or --flag=value.'
    );
  }

  const dumpFile = positional[0] || (flags.dump !== true ? flags.dump : undefined);
  const dump = loadDump(dumpFile);
  const only = list(flags.only);
  checkOnlyPaths(only, dump);
  const codeIndexMode = flags.codeindex === undefined ? 'scoped' : String(flags.codeindex);
  if (!['scoped', 'all', 'none'].includes(codeIndexMode)) {
    throw new Error(`--codeindex must be scoped|all|none (got "${codeIndexMode}")`);
  }
  const commit = flags.commit === true;

  // A --no-codeindex dump holds no join codes at all. Under any mode that deletes, the delete
  // planner below matches every live code pointing at a household in scope, and there is nothing
  // in the dump to write back — so the household keeps its `code` field, loses its index entry,
  // and its join flow is dead. The verification gate cannot see it either (the destroyed doc is in
  // neither the want set nor the got set). backup.js warns at dump time; refuse at restore time.
  if (dump.scope.includeCodeIndex === false && codeIndexMode !== 'none') {
    throw new Error(
      `${dumpFile} was taken with --no-codeindex (scope.includeCodeIndex is false), so it carries ` +
        `no join codes. Restoring it with --codeindex=${codeIndexMode} would DELETE every live ` +
        'codeIndex entry pointing at a household in this dump and have nothing to write back: ' +
        'those households would still advertise a `code` with no index entry and nobody could join ' +
        'them. Re-run with --codeindex=none to leave codeIndex untouched, or restore from a dump ' +
        'that includes it.'
    );
  }

  const { db, emulatorHost, projectId, credential } = initFirestore({ project: flags.project });

  // `=== true`, not truthiness: parseArgs already refuses `--allow-prod=false`, and these two
  // gates are the last thing between a --commit run and the live project, so they do not depend on
  // a second file getting that right. `--commit` above has always been written this way.
  if (!emulatorHost && flags['allow-prod'] !== true) {
    throw new Error(
      'FIRESTORE_EMULATOR_HOST is not set, so this would run against the LIVE project ' +
        `"${projectId}". Re-run with --allow-prod if that is genuinely what you want.`
    );
  }
  if (dump.source.projectId !== projectId && flags['allow-project-mismatch'] !== true) {
    throw new Error(
      `the dump was taken from project "${dump.source.projectId}" but the target is "${projectId}". ` +
        'Re-run with --allow-project-mismatch if that is intended (it is, for the rehearsal).'
    );
  }

  // ONE key set drives the delete, the write and the verification. `collections.households` is the
  // authoritative one — it is what the crawl actually dumped — and unlike `scope.households` it
  // includes a household that owns subcollections but has no document of its own. That household
  // must have its subtree cleared before the rewrite or stale documents survive a "lossless"
  // rollback, and it must be crawled during verification or its documents read as "missing from
  // target" instead of as the stale data they are. loadDump has already refused a dump whose
  // scope.households names anything outside this set.
  const restoreHouseholds = Object.keys(dump.collections.households);
  const fieldless = restoreHouseholds.filter((id) => !dump.collections.households[id].exists);

  // codeIndex is in play unless the dump omitted it or --only excludes it. Both the delete planner
  // and the verification below read this one flag, so they cannot disagree about whether the
  // collection is part of this restore — which is what let --codeindex=none report FAILED on a
  // perfect restore (writes queued, verification skipped).
  const codeIndexInScope = dump.scope.includeCodeIndex !== false && intersects('codeIndex', only);
  const dumpedCodes = new Set(Object.keys(dump.collections.codeIndex || {}));

  log(`Dump:   ${dumpFile}`);
  log(`        taken ${dump.createdAt} from project=${dump.source.projectId}` +
      `${dump.source.emulatorHost ? ` (emulator ${dump.source.emulatorHost})` : ''}`);
  log(`Target: project=${projectId} ${emulatorHost ? `emulator=${emulatorHost}` : 'LIVE PROJECT'}`);
  log(`Creds:  ${describeCredential(credential)}`);
  log(`Mode:   ${commit ? 'COMMIT (destructive)' : 'dry run (nothing will be written)'}`);
  // `only` is always printed, including when it is "everything": an operator who typed --only and
  // got a whole-household restore would otherwise have to notice the *absence* of a line.
  log(`Scope:  households=[${restoreHouseholds.join(', ')}]` +
      `${fieldless.length ? ` (${fieldless.length} of them own subcollections but no document)` : ''}` +
      ` codeIndex=${codeIndexInScope ? codeIndexMode : 'not in this dump/selection'}` +
      ` only=${only.length ? `[${only.join(', ')}]` : 'everything in the dump'}`);

  // --- 1. plan the delete -----------------------------------------------------------------------
  const deleteRefs = [];
  for (const householdId of restoreHouseholds) {
    const refs = await collectRefsDeepestFirst(db.collection('households').doc(householdId), []);
    for (const ref of refs) if (selected(collectionOf(ref.path), only)) deleteRefs.push(ref);
  }

  const codeIndexKept = [];
  if (codeIndexMode !== 'none' && codeIndexInScope) {
    const scopeIds = new Set(restoreHouseholds);
    // listDocuments() + getAll(), not collection.get(), for the reason lib/firestore.js's
    // crawlDocument documents at length: get() skips a document that holds no fields but does own
    // a subcollection, so under --codeindex=all such a document survived a mode whose whole
    // meaning is "all", and verification counts only existing documents so it could not see the
    // survivor either. The fields are still needed (scoped mode matches on householdId), hence the
    // fan-out read rather than listDocuments() alone.
    const codeRefs = await db.collection('codeIndex').listDocuments();
    for (let i = 0; i < codeRefs.length; i += READ_CHUNK) {
      const chunk = codeRefs.slice(i, i + READ_CHUNK);
      for (const snap of await db.getAll(...chunk)) {
        const householdId = snap.exists ? snap.get('householdId') : undefined;
        const inScope = dumpedCodes.has(snap.ref.id)
          || (typeof householdId === 'string' && scopeIds.has(householdId));
        // The subtree, not just the document: deleting a document does not delete its
        // subcollections, so a `codeIndex/{code}/...` document would survive a mode whose whole
        // meaning is "all" — the same reason the household delete set is built with this function.
        if (codeIndexMode === 'all' || inScope) {
          // Filtered per reference against `only`, the same way the households pass above does.
          // Unfiltered, an `--only` naming a collection *beneath* codeIndex/ (which now puts
          // codeIndex in play, see intersects) would delete the code document itself and its other
          // subcollections — widening the delete past what --only named. With no --only, or
          // --only=codeIndex, every reference passes and this is the previous behaviour exactly.
          for (const ref of await collectRefsDeepestFirst(snap.ref, [])) {
            if (selected(collectionOf(ref.path), only)) deleteRefs.push(ref);
          }
        } else codeIndexKept.push(`${snap.ref.path} -> ${householdId}`);
      }
    }
  }
  for (const kept of codeIndexKept) {
    warn(`codeIndex doc left in place (outside the dump's scope; --codeindex=all would remove it): ${kept}`);
  }

  // --- 2. plan the writes -----------------------------------------------------------------------
  const jobs = [];
  for (const householdId of restoreHouseholds) {
    flattenForWrite(dump.collections.households[householdId], `households/${householdId}`, jobs);
  }
  for (const [code, node] of Object.entries(dump.collections.codeIndex || {})) {
    flattenForWrite(node, `codeIndex/${code}`, jobs);
  }
  // Decode every document NOW, during planning, rather than lazily inside the write batches. The
  // decode path is where a malformed dump is caught (a bad @double payload, an undecodable value),
  // and there is no outer transaction — decoding inside the write pass meant the delete pass had
  // already committed by the time the dump was found to be unusable. This way a bad dump is
  // refused before anything is touched, and a dry run catches it too.
  const writeJobs = jobs
    .filter((j) => selected(collectionOf(j.path), only))
    .map((j) => {
      try {
        return { path: j.path, data: j.data, decoded: decodeDocument(j.data, db) };
      } catch (err) {
        throw new Error(`${dumpFile} cannot be restored — ${j.path}: ${err.message}`);
      }
    });

  // The delete count is references, not documents: it includes fieldless parents (a doc that owns
  // only subcollections), whose delete is a harmless no-op.
  log(`\nPlan: delete ${deleteRefs.length} document reference(s), write ${writeJobs.length} ` +
      `document(s), in batches of ${BATCH_SIZE}.`);

  // A run with nothing to delete AND nothing to write cannot be verified — there is no document to
  // compare and no collection to count — so its OK line would be a claim about the empty set. That
  // is the shape the typo'd --only produced, and `checkOnlyPaths` now refuses that case by name and
  // earlier; this is the same property stated without reference to any flag, so a future scope
  // narrowing cannot reintroduce it. It is not reachable from a whole-dump restore: the delete set
  // always contains at least the household references themselves.
  if (!deleteRefs.length && !writeJobs.length) {
    throw new Error(
      `this run would delete nothing and write nothing, so there would be nothing to verify — and ` +
        'a verification gate with an empty input prints the same OK line a real restore prints. ' +
        `Refusing instead. ${only.length
          ? `--only=[${only.join(', ')}] selects no documents in this dump`
          : `${dumpFile} holds no documents`}.`
    );
  }

  // `integralDoubleFields` entries are `<docPath>.<field>`, and that string cannot be split back
  // into its halves: Firestore document ids may contain periods and map keys may contain slashes.
  // Splitting on '.' really did drop warnings (`households/h.1/pets/p1.weightKg` yielded
  // `households`) — the round-2 fix. Cutting at the last '/' instead, as that fix did, is NOT
  // reachable: the mis-cut prefix for a slash-bearing field name is always a path *below* the
  // document's own collection, so `selected()`'s trailing-slash prefix test still matches it for
  // every `only` that selects the document at all (probed over 320 doc/field/only combinations:
  // zero drops). This asks the question directly instead, so no parsing rule has to be right: an
  // entry belongs to this run iff some document this run writes is a prefix of it at a '.'
  // boundary — and `writeJobs` is already narrowed to `only`. Every '.' is tried, because the
  // boundary cannot be identified without the document set.
  const writtenDocPaths = new Set(writeJobs.map((j) => j.path));
  const retypes = (dump.manifest.integralDoubleFields || []).filter((f) => {
    for (let i = f.indexOf('.'); i !== -1; i = f.indexOf('.', i + 1)) {
      if (writtenDocPaths.has(f.slice(0, i))) return true;
    }
    return false;
  });
  if (retypes.length) {
    warn(`${retypes.length} field(s) will come back as Firestore integers rather than doubles`);
    warn('  (Node Admin SDK limit — see lib/codec.js). This retype is the ONLY difference the');
    warn('  field-by-field content check tolerates; any other field difference fails the restore:');
    for (const f of retypes.slice(0, 20)) warn(`    ${f}`);
  }

  if (!commit) {
    log('\nPer-collection counts this dump would restore:');
    log(countTable(expectedCounts(dump, only)));
    log('\nDry run — nothing was written. Re-run with --commit.');
    return 0;
  }

  // --- 3. delete --------------------------------------------------------------------------------
  const deleted = await commitInChunks(db, deleteRefs, (batch, ref) => batch.delete(ref));
  log(`Deleted ${deleted} document reference(s).`);

  // --- 4. re-write ------------------------------------------------------------------------------
  const written = await commitInChunks(db, writeJobs, (batch, job) =>
    batch.set(db.doc(job.path), job.decoded)
  );
  log(`Wrote ${written} document(s).`);

  // --- 5. verify against the dump ---------------------------------------------------------------
  const report = newReport();
  const actualHouseholds = {};
  for (const householdId of restoreHouseholds) {
    actualHouseholds[householdId] = await crawlDocument(db, db.collection('households').doc(householdId), report);
  }
  report.counts.households = Object.values(actualHouseholds).filter((n) => n.exists).length;
  let actualCodeIndex = {};
  if (codeIndexInScope) {
    actualCodeIndex = await crawlCollection(db, db.collection('codeIndex'), report);
    if (codeIndexMode !== 'all') {
      // codeIndex is global. Under `scoped` the restore only owns the codes in the dump and the
      // ones pointing into it; under `none` it owns only the ones it wrote. Either way
      // verification has to ignore the rest or every other household's join code reads as an
      // "unexpected" document. Under `all` every other code was deleted, so the whole collection
      // is ours and a leftover IS a real mismatch.
      for (const code of Object.keys(actualCodeIndex)) {
        if (dumpedCodes.has(code)) continue;
        delete actualCodeIndex[code];
        // Disowning the node means disowning everything the crawl recorded beneath it too. A
        // `codeIndex/{code}` document that owns a subcollection contributes
        // counts['codeIndex/<code>/<sub>'], and that key outlived the node — so the count check
        // found a collection the manifest does not mention and reported FAILED on a restore that
        // had correctly left that code alone (it is even warned about, one screen up, as
        // "left in place"). Same defect as backup.js's --household narrowing, same helper.
        forgetSubtree(report, `codeIndex/${code}`);
      }
      report.counts.codeIndex = Object.values(actualCodeIndex).filter((n) => n.exists).length;
    }
  } else {
    delete report.counts.codeIndex;
  }

  const expected = expectedCounts(dump, only);
  const mismatches = [];
  // Every check below is counted, not just its failures. `mismatches.length === 0` means "nothing
  // disagreed", which is also what an empty comparison produces — see the guard before the OK line.
  let countChecks = 0;
  for (const path of new Set([...Object.keys(expected), ...Object.keys(report.counts)])) {
    if (!selected(path, only)) continue;
    if (path === 'codeIndex' && !codeIndexInScope) continue;
    countChecks += 1;
    const want = expected[path];
    const got = report.counts[path];
    if (want === undefined) mismatches.push(`${path}: not in the manifest but the target now holds ${got} doc(s)`);
    else if (got === undefined) mismatches.push(`${path}: manifest says ${want} doc(s), the collection does not exist in the target`);
    else if (want !== got) mismatches.push(`${path}: manifest says ${want} doc(s), target has ${got}`);
  }

  // Counts alone would pass on a doc written under the wrong id, so diff the id sets too.
  const gotDocs = new Map();
  for (const [id, node] of Object.entries(actualHouseholds)) collectDocs(node, `households/${id}`, gotDocs);
  for (const [id, node] of Object.entries(actualCodeIndex)) collectDocs(node, `codeIndex/${id}`, gotDocs);
  const wantPaths = new Set(writeJobs.map((j) => j.path));
  let idChecks = 0;
  for (const p of wantPaths) {
    if (!selected(collectionOf(p), only)) continue;
    idChecks += 1;
    if (!gotDocs.has(p)) mismatches.push(`missing from target: ${p}`);
  }
  for (const p of gotDocs.keys()) {
    if (!selected(collectionOf(p), only)) continue;
    idChecks += 1;
    if (!wantPaths.has(p)) mismatches.push(`unexpected in target: ${p}`);
  }

  // And identity alone says nothing about content: matching counts and matching ids are exactly
  // what a codec regression that wrote {} for every document produces. The verify crawl already
  // returns each document in the dump's own encoded format, so comparing it against the dump's
  // contents is a comparison and not extra I/O. The only tolerated difference is the documented
  // integral-double retype, and every tolerated field is recorded so the tolerance is visible.
  const comparison = newComparison();
  let contentChecks = 0;
  for (const job of writeJobs) {
    const got = gotDocs.get(job.path);
    if (got === undefined) continue; // already reported above as "missing from target"
    contentChecks += 1;
    compareEncodedDocument(job.data, got, job.path, comparison);
  }

  log('\nPost-restore counts (target):');
  log(countTable(report.counts));

  // The gate's own integrity check, and the one invariant every other check rests on: zero
  // disagreements is only evidence of a good restore if something was actually compared. With an
  // empty input — no count check, no id check, no field compare — `mismatches` and
  // `comparison.diffs` are structurally empty, and the OK line below would then report "verified
  // everything" for a run that verified nothing. Both known routes to an empty input are refused
  // before the delete (an unmatched --only, and a plan that does nothing), so this should be
  // unreachable; it is here because the property "success implies a non-empty verified set" belongs
  // in the gate rather than in the list of ways to reach it.
  if (countChecks + idChecks + contentChecks === 0) {
    log('\nFAILED: this run verified nothing — no collection count, no document id and no field ' +
        'value was compared, so there is no evidence the target reproduces the dump.');
    log(`  Scope was: only=${only.length ? `[${only.join(', ')}]` : 'everything in the dump'}, ` +
        `${writeJobs.length} write job(s), ${deleteRefs.length} delete ref(s).`);
    log('\nTreat this exactly like a mismatch: do not proceed, and keep the dump file.');
    return 1;
  }

  const total = mismatches.length + comparison.diffs.length;
  if (total) {
    log(`\nFAILED: ${total} mismatch(es) — the target does not reproduce the dump:`);
    for (const m of mismatches) log(`  - ${m}`);
    for (const d of comparison.diffs.slice(0, MAX_DIFFS_SHOWN)) log(`  - ${d}`);
    if (comparison.diffs.length > MAX_DIFFS_SHOWN) {
      log(`  - ... and ${comparison.diffs.length - MAX_DIFFS_SHOWN} more field difference(s)`);
    }
    log('\nThe restore did NOT reproduce the dump. Do not proceed. Keep the dump file.');
    return 1;
  }

  // A retype the dump did not predict is not a restore failure — the target holds what the dump
  // holds, modulo the SDK's one limit — but it means manifest.integralDoubleFields understated it,
  // and that manifest is what the operator was shown in the dry run.
  const manifestRetypes = new Set(dump.manifest.integralDoubleFields || []);
  for (const p of comparison.retyped) {
    if (!manifestRetypes.has(p)) {
      warn(`retyped double -> integer but absent from the dump's manifest.integralDoubleFields: ${p}`);
    }
  }

  // The numbers are in the line deliberately: "compared nothing" and "compared everything" have to
  // read differently at a glance, because migration.md §7's irreversible cleanup delete is gated on
  // this one line and a zero in it is the only thing distinguishing the two.
  log(`\nOK: every restored collection matches the dump manifest — all ${countChecks} ` +
      `per-collection count(s), the document-id set (${idChecks} check(s)), and a field-by-field ` +
      `value compare of all ${contentChecks} document(s).`);
  if (comparison.retyped.length) {
    log(`    (${comparison.retyped.length} integral double(s) came back as Firestore integers — ` +
        'the one documented retype, reported above and in manifest.integralDoubleFields.)');
  }
  log(`\n${HEALTH_DATA_WARNING}`);
  return 0;
}

/** Manifest counts, narrowed to the --only selection. */
function expectedCounts(dump, only) {
  const out = {};
  for (const [path, count] of Object.entries(dump.manifest.counts)) {
    if (selected(path, only)) out[path] = count;
  }
  return out;
}

/** Every existing document under `node` as docPath -> encoded field map. */
function collectDocs(node, docPath, acc) {
  if (node.exists) acc.set(docPath, node.data);
  for (const [colId, docs] of Object.entries(node.collections)) {
    for (const [docId, child] of Object.entries(docs)) collectDocs(child, `${docPath}/${colId}/${docId}`, acc);
  }
  return acc;
}

if (require.main === module) {
  // exitWhenFlushed, not process.exit: stdout is asynchronous to a pipe and process.exit()
  // discards what is still queued, which silently ate the final verdict line under `| less`.
  main(process.argv.slice(2))
    .then((code) => exitWhenFlushed(code))
    .catch((err) => { console.error(`\nrestore.js failed: ${err.message}`); exitWhenFlushed(2); });
}

module.exports = { main };
