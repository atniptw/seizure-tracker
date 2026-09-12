#!/usr/bin/env node
'use strict';

// restore.js — migration.md §5 "Restore procedure".
//
// From a backup.js dump: delete the named collections, re-write every document, re-read the result
// and compare the per-collection counts (and the document-id sets) against the dump's manifest.
// Reports every mismatch and exits non-zero if there is one.
//
// Destructive, but only with --commit: with no --commit it plans the work, prints it and writes
// nothing (there is no --dry-run flag — a dry run is what you get by default). A run against a live
// project additionally needs --allow-prod.

const fs = require('fs');
const {
  initFirestore, crawlDocument, crawlCollection, newReport, flattenForWrite,
  collectRefsDeepestFirst, commitInChunks, decodeDocument, BATCH_SIZE,
} = require('./lib/firestore');
const {
  parseArgs, list, log, warn, countTable, describeCredential, HEALTH_DATA_WARNING,
} = require('./lib/cli');
const { FORMAT } = require('./backup');

const USAGE = `
Usage: node restore.js <dump.json> --project=<id> [--commit] [options]

  --project=<id>            Firebase/GCP project id (or GOOGLE_CLOUD_PROJECT).
  --commit                  Actually delete and re-write. Without it this is a dry run.
  --only=<colPath>[,...]    Restore only these collections and everything nested beneath them,
                            e.g. --only=households/h1/seizures,households/h1/healthNotes.
                            Default: everything in the dump.
  --codeindex=scoped|all|none
                            Which codeIndex docs to delete before re-writing (default scoped:
                            only docs in the dump or pointing at a household in the dump — the
                            collection is global, so "all" would also drop other households').
  --allow-prod              Required when FIRESTORE_EMULATOR_HOST is unset. Read that twice.
  --allow-project-mismatch  Required when the dump's project id differs from the target. This is
                            the normal case for the rehearsal (prod dump -> emulator).

Emulator:  FIRESTORE_EMULATOR_HOST=localhost:8080 node restore.js dumps/dump-....json \\
             --project=demo-seizuretracker-rules-test --allow-project-mismatch --commit
`;

/** True if `colPath` is one of `only`, or nested beneath it. Empty `only` selects everything. */
const selected = (colPath, only) =>
  only.length === 0 || only.some((o) => colPath === o || colPath.startsWith(`${o}/`));

const collectionOf = (docPath) => docPath.split('/').slice(0, -1).join('/');

function loadDump(file) {
  if (!file) throw new Error(`no dump file given${USAGE}`);
  const dump = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (dump.format !== FORMAT) {
    throw new Error(`${file} is not a ${FORMAT} dump (found format="${dump.format}")`);
  }
  if (!dump.manifest || !dump.manifest.counts) throw new Error(`${file} has no manifest.counts`);
  if (!dump.collections) throw new Error(`${file} has no collections`);
  return dump;
}

async function main(argv) {
  const { flags, positional } = parseArgs(argv);
  if (flags.help || flags.h) { log(USAGE); return 0; }

  const dumpFile = positional[0] || (flags.dump !== true ? flags.dump : undefined);
  const dump = loadDump(dumpFile);
  const only = list(flags.only);
  const codeIndexMode = flags.codeindex === undefined ? 'scoped' : String(flags.codeindex);
  if (!['scoped', 'all', 'none'].includes(codeIndexMode)) {
    throw new Error(`--codeindex must be scoped|all|none (got "${codeIndexMode}")`);
  }
  const commit = flags.commit === true;

  const { db, emulatorHost, projectId, credential } = initFirestore({ project: flags.project });

  if (!emulatorHost && !flags['allow-prod']) {
    throw new Error(
      'FIRESTORE_EMULATOR_HOST is not set, so this would run against the LIVE project ' +
        `"${projectId}". Re-run with --allow-prod if that is genuinely what you want.`
    );
  }
  if (dump.source.projectId !== projectId && !flags['allow-project-mismatch']) {
    throw new Error(
      `the dump was taken from project "${dump.source.projectId}" but the target is "${projectId}". ` +
        'Re-run with --allow-project-mismatch if that is intended (it is, for the rehearsal).'
    );
  }

  log(`Dump:   ${dumpFile}`);
  log(`        taken ${dump.createdAt} from project=${dump.source.projectId}` +
      `${dump.source.emulatorHost ? ` (emulator ${dump.source.emulatorHost})` : ''}`);
  log(`Target: project=${projectId} ${emulatorHost ? `emulator=${emulatorHost}` : 'LIVE PROJECT'}`);
  log(`Creds:  ${describeCredential(credential)}`);
  log(`Mode:   ${commit ? 'COMMIT (destructive)' : 'dry run (nothing will be written)'}`);
  log(`Scope:  households=[${dump.scope.households.join(', ')}] codeIndex=${codeIndexMode}` +
      `${only.length ? ` only=[${only.join(', ')}]` : ''}`);

  // --- 1. plan the delete -----------------------------------------------------------------------
  const deleteRefs = [];
  for (const householdId of dump.scope.households) {
    const refs = await collectRefsDeepestFirst(db.collection('households').doc(householdId), []);
    for (const ref of refs) if (selected(collectionOf(ref.path), only)) deleteRefs.push(ref);
  }

  const codeIndexKept = [];
  if (codeIndexMode !== 'none' && selected('codeIndex', only)) {
    const dumpedCodes = new Set(Object.keys(dump.collections.codeIndex || {}));
    const scopeIds = new Set(dump.scope.households);
    for (const snap of (await db.collection('codeIndex').get()).docs) {
      const householdId = snap.get('householdId');
      const inScope = dumpedCodes.has(snap.id) || (typeof householdId === 'string' && scopeIds.has(householdId));
      if (codeIndexMode === 'all' || inScope) deleteRefs.push(snap.ref);
      else codeIndexKept.push(`${snap.ref.path} -> ${householdId}`);
    }
  }
  for (const kept of codeIndexKept) {
    warn(`codeIndex doc left in place (outside the dump's scope; --codeindex=all would remove it): ${kept}`);
  }

  // --- 2. plan the writes -----------------------------------------------------------------------
  const jobs = [];
  for (const [householdId, node] of Object.entries(dump.collections.households || {})) {
    flattenForWrite(node, `households/${householdId}`, jobs);
  }
  for (const [code, node] of Object.entries(dump.collections.codeIndex || {})) {
    flattenForWrite(node, `codeIndex/${code}`, jobs);
  }
  const writeJobs = jobs.filter((j) => selected(collectionOf(j.path), only));

  // The delete count is references, not documents: it includes fieldless parents (a doc that owns
  // only subcollections), whose delete is a harmless no-op.
  log(`\nPlan: delete ${deleteRefs.length} document reference(s), write ${writeJobs.length} ` +
      `document(s), in batches of ${BATCH_SIZE}.`);

  const retypes = (dump.manifest.integralDoubleFields || []).filter((f) => selected(collectionOf(f.split('.')[0].split('[')[0]), only));
  if (retypes.length) {
    warn(`${retypes.length} field(s) will come back as Firestore integers rather than doubles`);
    warn('  (Node Admin SDK limit — see lib/codec.js). Not counted as a mismatch:');
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
    batch.set(db.doc(job.path), decodeDocument(job.data, db))
  );
  log(`Wrote ${written} document(s).`);

  // --- 5. verify against the manifest -----------------------------------------------------------
  const report = newReport();
  const actualHouseholds = {};
  for (const householdId of dump.scope.households) {
    actualHouseholds[householdId] = await crawlDocument(db, db.collection('households').doc(householdId), report);
  }
  report.counts.households = Object.values(actualHouseholds).filter((n) => n.exists).length;
  let actualCodeIndex = {};
  if (dump.scope.includeCodeIndex && codeIndexMode !== 'none') {
    actualCodeIndex = await crawlCollection(db, db.collection('codeIndex'), report);
    if (codeIndexMode === 'scoped') {
      // codeIndex is global. With --codeindex=scoped the restore only owns the codes that are in
      // the dump, so verification has to ignore the rest or every other household's join code
      // reads as an "unexpected" document.
      const dumped = new Set(Object.keys(dump.collections.codeIndex || {}));
      for (const code of Object.keys(actualCodeIndex)) if (!dumped.has(code)) delete actualCodeIndex[code];
      report.counts.codeIndex = Object.values(actualCodeIndex).filter((n) => n.exists).length;
    }
  } else {
    delete report.counts.codeIndex;
  }

  const expected = expectedCounts(dump, only);
  const mismatches = [];
  for (const path of new Set([...Object.keys(expected), ...Object.keys(report.counts)])) {
    if (!selected(path, only)) continue;
    const want = expected[path];
    const got = report.counts[path];
    if (want === undefined) mismatches.push(`${path}: not in the manifest but the target now holds ${got} doc(s)`);
    else if (got === undefined) mismatches.push(`${path}: manifest says ${want} doc(s), the collection does not exist in the target`);
    else if (want !== got) mismatches.push(`${path}: manifest says ${want} doc(s), target has ${got}`);
  }

  // Counts alone would pass on a doc written under the wrong id, so diff the id sets too.
  const wantPaths = new Set(writeJobs.map((j) => j.path));
  const gotPaths = new Set();
  for (const [id, node] of Object.entries(actualHouseholds)) collectPaths(node, `households/${id}`, gotPaths);
  for (const [id, node] of Object.entries(actualCodeIndex)) collectPaths(node, `codeIndex/${id}`, gotPaths);
  for (const p of wantPaths) if (!gotPaths.has(p) && selected(collectionOf(p), only)) mismatches.push(`missing from target: ${p}`);
  for (const p of gotPaths) if (!wantPaths.has(p) && selected(collectionOf(p), only)) mismatches.push(`unexpected in target: ${p}`);

  log('\nPost-restore counts (target):');
  log(countTable(report.counts));

  if (mismatches.length) {
    log(`\nFAILED: ${mismatches.length} mismatch(es) against the manifest:`);
    for (const m of mismatches) log(`  - ${m}`);
    log('\nThe restore did NOT reproduce the dump. Do not proceed. Keep the dump file.');
    return 1;
  }

  log('\nOK: every restored collection matches the dump manifest, document for document.');
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

function collectPaths(node, docPath, acc) {
  if (node.exists) acc.add(docPath);
  for (const [colId, docs] of Object.entries(node.collections)) {
    for (const [docId, child] of Object.entries(docs)) collectPaths(child, `${docPath}/${colId}/${docId}`, acc);
  }
}

if (require.main === module) {
  main(process.argv.slice(2))
    .then((code) => process.exit(code))
    .catch((err) => { console.error(`\nrestore.js failed: ${err.message}`); process.exit(2); });
}

module.exports = { main };
