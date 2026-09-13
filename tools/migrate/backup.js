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
  EXPECTATIONS,
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

Every value-taking flag must be written --flag=value, every boolean flag bare (--no-codeindex,
not --no-codeindex=false), and an unknown flag is rejected rather than ignored. See lib/cli.js.

Emulator:  FIRESTORE_EMULATOR_HOST=localhost:8080 node backup.js --project=demo-seizuretracker-rules-test
Prod:      gcloud auth application-default login  (preferred), then
             node backup.js --project=<real-project>
           or GOOGLE_APPLICATION_CREDENTIALS=/abs/path/key.json node backup.js --project=<real-project>
`;

/** Flags that must be written --flag=value. See lib/cli.js parseArgs for why this list exists. */
const VALUE_FLAGS = ['project', 'household', 'out', 'label', 'expect'];
/** Flags that must be written bare — the other half of the same guard (see lib/cli.js). */
const BOOLEAN_FLAGS = ['help', 'h', 'no-codeindex', 'require-expected'];

async function main(argv) {
  const { flags, positional } = parseArgs(argv, {
    valueFlags: VALUE_FLAGS, booleanFlags: BOOLEAN_FLAGS,
  });
  if (flags.help || flags.h) { log(USAGE); return 0; }

  // This script takes no positional arguments at all, so one is a flag name forgotten entirely:
  // `node backup.js h-legacy` dumped EVERY household instead of the one named. Read-only, and the
  // banner and manifest do record the real scope — but a wider-than-intended dump of a health
  // record is worth the same three lines restore.js gets.
  if (positional.length) {
    throw new Error(
      `unexpected argument(s): ${positional.join(' ')}. backup.js takes no positional arguments — ` +
        `did you mean --household=${positional[0]}? Every option is --flag or --flag=value.`
    );
  }

  // Validated BEFORE initFirestore and the crawl. checkExpectations throws on an unknown name too,
  // but it runs after the whole project has been read: `--expect=lgacy` against the live project
  // cost a full read and then exited 2 with no dump written, which is a real cost on Spark and a
  // bad thing to discover inside the migration window.
  const expect = flags.expect === undefined ? 'legacy' : String(flags.expect);
  if (!EXPECTATIONS[expect]) {
    throw new Error(
      `--expect must be one of ${Object.keys(EXPECTATIONS).join('|')} (got "${expect}"). ` +
        'Refused before reading the project rather than after.'
    );
  }

  const { db, emulatorHost, projectId, credential } = initFirestore({ project: flags.project });
  const outDir = flags.out ? String(flags.out) : path.join(__dirname, 'dumps');
  // list() de-duplicates: --household=h1,h1 crawled h1 twice and counted its household document
  // twice, so the manifest claimed 2 households against one dumped entry and every restore from
  // that dump then failed verification — after committing. See lib/cli.js.
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
    // "Does not exist" has to mean "no document AND no subcollections". A household doc that holds
    // subcollections but no fields of its own is precisely the case the crawl uses listDocuments()
    // to preserve (migration.md §5 item 4); refusing to dump one because `snap.exists` is false
    // would refuse the household that most needs dumping.
    if (!node.exists && wanted.length && Object.keys(node.collections).length === 0) {
      throw new Error(
        `--household=${ref.id} does not exist in project ${projectId}: no document, and no ` +
          'subcollections beneath it either.'
      );
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
        // Only existing documents were counted into report.counts by crawlCollection, so only
        // those may be decremented. Counting a fieldless dropped node (a codeIndex doc that owns a
        // subcollection — reachable, if unlikely) pushed the manifest count below the truth, and
        // every restore from that dump then failed verification after committing.
        if (node.exists) dropped += 1;
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

  // A dump is an unencrypted household health record (security-privacy.md §2.1/§2.3), so the
  // directory holding it is owner-only too — a 600 file inside a 755 directory still advertises
  // the filenames, and the filenames name the project. `mode` applies on creation only, so an
  // existing directory is checked and reported rather than silently trusted.
  fs.mkdirSync(outDir, { recursive: true, mode: 0o700 });
  const dirMode = fs.statSync(outDir).mode & 0o777;
  if (dirMode & 0o077) {
    warn(`${outDir} is mode ${dirMode.toString(8)}, readable outside your user account.`);
    warn(`  Dump files themselves are 0600. Consider: chmod 700 ${outDir}`);
  }
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
