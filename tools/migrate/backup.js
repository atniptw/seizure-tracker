#!/usr/bin/env node
'use strict';

// backup.js — migration.md §4 "Safeguards" / §5 "The tooling".
//
// Recursive Admin SDK read of households/{id} + every subcollection (discovered with
// listCollections(), not hardcoded) + codeIndex/*, written to a timestamped local JSON file with a
// per-collection count manifest. Deliberately NOT `gcloud firestore export`: managed export needs
// the Blaze plan and a GCS bucket, and architecture.md §9 commits to never needing either.
//
// Read-only. It cannot modify Firestore. See README.md for credentials and the rehearsal transcript.

const fs = require('fs');
const path = require('path');
const {
  initFirestore, crawlDocument, crawlCollection, newReport, checkExpectations, findIntegralDoubles,
} = require('./lib/firestore');
const {
  parseArgs, list, log, warn, countTable, describeCredential, HEALTH_DATA_WARNING,
} = require('./lib/cli');

const FORMAT = 'seizuretracker-firestore-dump/1';
const USAGE = `
Usage: node backup.js --project=<id> [options]

  --project=<id>           Firebase/GCP project id (or GOOGLE_CLOUD_PROJECT).
  --household=<id>[,<id>]  Only these households. Default: every household in the project.
  --out=<dir>              Output directory. Default: tools/migrate/dumps (gitignored).
  --label=<text>           Appended to the filename, e.g. --label=pre-window.
  --expect=legacy|target|none
                           Which collection set to assert (default legacy). See lib/firestore.js.
  --require-expected       Exit non-zero if an expected collection is absent (default: warn).
  --no-codeindex           Skip codeIndex/* (not recommended; the join code lives there).

Emulator:  FIRESTORE_EMULATOR_HOST=localhost:8080 node backup.js --project=demo-seizuretracker-rules-test
Prod:      gcloud auth application-default login  (preferred), then
             node backup.js --project=<real-project>
           or GOOGLE_APPLICATION_CREDENTIALS=/abs/path/key.json node backup.js --project=<real-project>
`;

async function main(argv) {
  const { flags } = parseArgs(argv);
  if (flags.help || flags.h) { log(USAGE); return 0; }

  const { db, emulatorHost, projectId, credential } = initFirestore({ project: flags.project });
  const expect = flags.expect === undefined ? 'legacy' : String(flags.expect);
  const outDir = flags.out ? String(flags.out) : path.join(__dirname, 'dumps');
  const wanted = list(flags.household);

  log(`Target: project=${projectId} ${emulatorHost ? `emulator=${emulatorHost}` : 'LIVE PROJECT (no emulator host set)'}`);
  log(`Creds:  ${describeCredential(credential)}`);

  const report = newReport();
  const collections = { households: {}, codeIndex: {} };

  // --- households -------------------------------------------------------------------------------
  const householdRefs = wanted.length
    ? wanted.map((id) => db.collection('households').doc(id))
    : await db.collection('households').listDocuments();

  let householdCount = 0;
  for (const ref of householdRefs) {
    const node = await crawlDocument(db, ref, report);
    if (!node.exists && wanted.length) {
      throw new Error(`--household=${ref.id} does not exist in project ${projectId}`);
    }
    collections.households[ref.id] = node;
    if (node.exists) householdCount += 1;
  }
  report.counts.households = householdCount;
  report.seenCollections.add('households');

  // --- codeIndex --------------------------------------------------------------------------------
  if (!flags['no-codeindex']) {
    collections.codeIndex = await crawlCollection(db, db.collection('codeIndex'), report);
    // codeIndex is a global collection, so a dump narrowed with --household must narrow it too —
    // otherwise the dump carries join codes for households whose data it does not contain, and a
    // restore from it would rewrite another household's index entry. A full-project backup (no
    // --household) keeps every code, which is the case that matters for the real pre-window dump.
    if (wanted.length) {
      const inScope = new Set(wanted);
      let dropped = 0;
      for (const [code, node] of Object.entries(collections.codeIndex)) {
        const householdId = node.exists ? node.data.householdId : null;
        if (typeof householdId === 'string' && inScope.has(householdId)) continue;
        delete collections.codeIndex[code];
        dropped += 1;
        warn(`codeIndex/${code} -> ${householdId} is outside --household, omitted from this dump`);
      }
      report.counts.codeIndex -= dropped;
    }
  } else {
    warn('codeIndex skipped (--no-codeindex): a restore from this dump cannot rebuild the join index.');
  }

  // --- assertions -------------------------------------------------------------------------------
  const householdIds = Object.keys(collections.households).filter((id) => collections.households[id].exists);
  const problems = checkExpectations(report, expect, householdIds);

  for (const p of report.unknownCollections) {
    warn(`collection not known to this migration (dumped anyway, but nothing backfills it): ${p}`);
  }
  for (const p of report.missingParents) {
    warn(`document holds subcollections but has no fields of its own (dumped as exists:false): ${p}`);
  }
  for (const p of problems) warn(p);

  // The one value class a Node Admin SDK restore cannot reproduce (see lib/codec.js). Reported at
  // dump time, not discovered during the window's verification step.
  const integralDoubles = [];
  for (const [id, node] of Object.entries(collections.households)) findIntegralDoubles(node, `households/${id}`, integralDoubles);
  for (const [id, node] of Object.entries(collections.codeIndex)) findIntegralDoubles(node, `codeIndex/${id}`, integralDoubles);
  if (integralDoubles.length) {
    warn(`${integralDoubles.length} field(s) hold a double with an integral value; restore.js will`);
    warn('  re-write them as Firestore integers — a Node Admin SDK limit, see lib/codec.js. The app');
    warn('  widens them back to Double on read, so this is recorded, not blocking:');
    for (const p of integralDoubles.slice(0, 20)) warn(`    ${p}`);
    if (integralDoubles.length > 20) warn(`    ... and ${integralDoubles.length - 20} more (full list in the dump manifest)`);
  }

  // --- write ------------------------------------------------------------------------------------
  const createdAt = new Date();
  const stamp = createdAt.toISOString().replace(/[:.]/g, '-');
  const dump = {
    format: FORMAT,
    createdAt: createdAt.toISOString(),
    tool: 'tools/migrate/backup.js',
    source: { projectId, emulatorHost, expect },
    scope: {
      households: householdIds,
      requestedHouseholds: wanted.length ? wanted : 'all',
      includeCodeIndex: !flags['no-codeindex'],
    },
    manifest: {
      counts: report.counts,
      totalDocuments: Object.values(report.counts).reduce((a, b) => a + b, 0),
      missingParents: report.missingParents,
      unknownCollections: report.unknownCollections,
      expectationProblems: problems,
      integralDoubleFields: integralDoubles,
    },
    collections,
  };

  fs.mkdirSync(outDir, { recursive: true });
  const label = flags.label && flags.label !== true ? `-${String(flags.label).replace(/[^\w.-]/g, '_')}` : '';
  const file = path.join(outDir, `dump-${projectId}-${stamp}${label}.json`);
  fs.writeFileSync(file, `${JSON.stringify(dump, null, 2)}\n`, { mode: 0o600 });

  log('\nPer-collection manifest:');
  log(countTable(report.counts));
  log(`\n  total documents: ${dump.manifest.totalDocuments}`);
  log(`\nWrote ${file}`);
  log(`\n${HEALTH_DATA_WARNING}`);

  if (problems.length && flags['require-expected']) {
    log('\nFAILED: --require-expected was set and the assertions above did not pass.');
    log('The dump file was still written — it is the artifact, do not discard it.');
    return 1;
  }
  return 0;
}

if (require.main === module) {
  main(process.argv.slice(2))
    .then((code) => process.exit(code))
    .catch((err) => { console.error(`\nbackup.js failed: ${err.message}`); process.exit(2); });
}

module.exports = { main, FORMAT };
