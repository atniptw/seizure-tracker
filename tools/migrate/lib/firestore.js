'use strict';

// Admin SDK plumbing shared by backup.js and restore.js.

const fs = require('fs');
const os = require('os');
const path = require('path');
const admin = require('firebase-admin');
const { getFirestore } = require('firebase-admin/firestore');
const {
  encodeDocument, decodeDocument, collectIntegralDoubles, compareEncodedDocument, newComparison,
} = require('./codec');

/**
 * migration.md §5: batched in chunks of 400 writes (Firestore's hard limit is 500).
 *
 * This bounds documents per commit, not request bytes — Firestore also caps a commit at ~10MiB.
 * Fine for this dataset (a few thousand small documents; photo/video attachments are backlogged
 * out of the release), but a future shape with large payloads could fail an oversized batch, and a
 * failed batch in restore.js's delete pass leaves the interrupted state its README documents.
 */
const BATCH_SIZE = 400;
/** getAll() takes a variadic ref list; keep each fan-out read modest. */
const READ_CHUNK = 250;

/**
 * Collections this migration knows about, per level. Anything found outside these lists is still
 * dumped, but reported loudly — the point of discovering subcollections with listCollections()
 * instead of hardcoding is that a collection added later is never silently missed.
 *
 * A level with no entry here is itself unknown, and `crawlCollection` reports every collection at
 * such a level rather than staying silent. Otherwise the guarantee would only hold to the three
 * depths listed below: `households/{h}/observations/{o}/attachments` — exactly the backlogged
 * attachments feature — would be dumped and never mentioned, and `backup.js` is also the tool
 * that takes the fresh dump against the new shape right before the §7 cleanup delete.
 */
const KNOWN_COLLECTIONS = {
  root: ['households', 'codeIndex'],
  'households/*': [
    // shipped shape
    'seizures', 'healthNotes', 'pets', 'vets', 'petVetLinks', 'members',
    // target shape (migration.md §3)
    'observations', 'private', 'exportLog',
  ],
  'households/*/pets/*': ['medications'],
};

/**
 * What a dump is expected to contain. Selected with --expect.
 *
 * NOTE, and this is why a missing expected collection is a warning rather than a hard failure by
 * default: in Firestore an *empty* collection does not exist. A household that has never logged a
 * health note legitimately has no `healthNotes` collection, and there is no way to tell that apart
 * from "the crawl missed it". Pass --require-expected to turn the warning into a non-zero exit
 * (the pre-window rehearsal should, because there the expected contents are known).
 */
const EXPECTATIONS = {
  legacy: { 'households/*': ['seizures', 'healthNotes', 'pets', 'vets', 'petVetLinks', 'members'] },
  target: { 'households/*': ['observations', 'pets', 'vets', 'petVetLinks', 'members', 'private'] },
  none: {},
};

/** `households/h1/pets/p1/medications` -> `households/*​/pets/*​/medications`. */
function templatePath(path) {
  const parts = path.split('/');
  return parts.map((p, i) => (i % 2 === 1 ? '*' : p)).join('/');
}

/** The level key (`root`, `households/*`, ...) a collection at `path` sits at. */
function levelOf(collectionPath) {
  const parts = collectionPath.split('/');
  if (parts.length === 1) return 'root';
  return templatePath(parts.slice(0, -1).join('/'));
}

/**
 * Where gcloud keeps Application Default Credentials — the same well-known path
 * `admin.credential.applicationDefault()` falls back to when GOOGLE_APPLICATION_CREDENTIALS is
 * unset. `CLOUDSDK_CONFIG` overrides the directory (gcloud honours it too), which is also how the
 * tests exercise this without touching the operator's real gcloud config.
 */
function adcFilePath() {
  const dir = process.env.CLOUDSDK_CONFIG || path.join(os.homedir(), '.config', 'gcloud');
  return path.join(dir, 'application_default_credentials.json');
}

/**
 * Which credential the Admin SDK is going to use — decided up front so a missing one fails here,
 * with a message naming every option, rather than inside the SDK on the first RPC.
 *
 * Two accepted sources against a live project, and `applicationDefault()` already handles both:
 *
 * - `GOOGLE_APPLICATION_CREDENTIALS` — a downloaded service-account key. Long-lived, and
 *   `security-privacy.md §2.3` lists whoever holds it as an actor with the reach of the whole
 *   database, so it is the fallback, not the default.
 * - gcloud ADC (`gcloud auth application-default login`) — user credentials at the well-known
 *   path, revocable with one command and with no key file to leak. Preferred for the rehearsal.
 *
 * The ADC path additionally requires an explicit project id: unlike a key file, ADC carries no
 * `project_id`, so without one the SDK fails later and confusingly.
 *
 * Returns `{ kind: 'emulator' | 'key-file' | 'adc', path? }`.
 */
function resolveCredentialSource({ emulatorHost, projectId }) {
  if (emulatorHost) {
    if (!projectId) {
      throw new Error('Running against the emulator needs an explicit --project (e.g. --project demo-seizuretracker-rules-test).');
    }
    return { kind: 'emulator' };
  }

  if (process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    return { kind: 'key-file', path: process.env.GOOGLE_APPLICATION_CREDENTIALS };
  }

  const adc = adcFilePath();
  if (fs.existsSync(adc)) {
    if (!projectId) {
      throw new Error(
        `Found Application Default Credentials (${adc}) but no project id.\n` +
          'ADC carries no project id (a service-account key does), so the Admin SDK would fail\n' +
          'later and unhelpfully. Pass --project=<id> or set GOOGLE_CLOUD_PROJECT.'
      );
    }
    return { kind: 'adc', path: adc };
  }

  throw new Error(
    'No credentials: FIRESTORE_EMULATOR_HOST is unset, GOOGLE_APPLICATION_CREDENTIALS is unset,\n' +
      `and there is no Application Default Credentials file at ${adc}.\n` +
      'Pick one (see tools/migrate/README.md):\n' +
      '  emulator   FIRESTORE_EMULATOR_HOST=127.0.0.1:8080  (no credentials at all)\n' +
      '  gcloud ADC gcloud auth application-default login    (preferred for the live project —\n' +
      '             nothing long-lived on disk; then pass --project=<id>)\n' +
      '  key file   GOOGLE_APPLICATION_CREDENTIALS=/abs/path/service-account.json'
  );
}

/**
 * Called when `db.settings()` threw, i.e. settings were already applied on this instance by some
 * earlier initializer. Confirm `useBigInt` actually took effect; the whole codec rests on it.
 *
 * `_settings` is the SDK's own record of the applied settings and is not public API, so an SDK
 * upgrade could remove it. Losing the ability to check is reported, not treated as a failure —
 * but a check that positively shows `useBigInt` off is fatal.
 */
function assertUseBigInt(db, settingsError) {
  const applied = db._settings;
  if (!applied || typeof applied !== 'object') {
    console.log(
      'WARNING: Firestore settings were already applied on this instance and this SDK version does',
      'not expose them, so "useBigInt: true" could not be confirmed. If it is not in effect, every',
      'integer is dumped as a double and an int64 past 2^53 is truncated. Original:',
      settingsError.message
    );
    return;
  }
  if (applied.useBigInt !== true) {
    throw new Error(
      'Firestore settings were already applied on this instance WITHOUT useBigInt: true, and they ' +
        'can only be applied once. The codec cannot tell an integer from a double without it, and ' +
        'the SDK truncates any int64 past 2^53 before the codec sees it, so a dump taken now would ' +
        'be silently lossy. Initialise Firestore through initFirestore() before anything else, or ' +
        'apply { useBigInt: true } in whatever does initialise it.'
    );
  }
}

function initFirestore({ project }) {
  const emulatorHost = process.env.FIRESTORE_EMULATOR_HOST || null;
  const projectId =
    project || process.env.GOOGLE_CLOUD_PROJECT || process.env.GCLOUD_PROJECT || undefined;

  const credential = resolveCredentialSource({ emulatorHost, projectId });

  if (!admin.apps.length) {
    admin.initializeApp(
      emulatorHost
        ? { projectId }
        : { credential: admin.credential.applicationDefault(), projectId }
    );
  }
  const db = getFirestore();
  // Integers must come back as BigInt or the codec cannot tell 12 from 12.0, and any int64 past
  // 2^53 is truncated by the SDK before the codec ever sees it. Settings can only be applied once
  // per instance, so a second call throws — but a throw only means "already applied", NOT
  // "already applied with useBigInt". Assert rather than assume: swallowing this is how a
  // losslessness tool loses precision silently.
  try {
    db.settings({ ignoreUndefinedProperties: false, useBigInt: true });
  } catch (err) {
    assertUseBigInt(db, err);
  }

  return { db, emulatorHost, projectId: projectId || db.projectId, credential };
}

/**
 * Recursively read a document and everything beneath it.
 *
 * Uses listDocuments() rather than collection.get() deliberately: listDocuments() also returns
 * "missing" documents — ids that hold no fields but do own subcollections. collection.get() skips
 * those, which would drop their entire subtree. A pet hard-deleted while its medications
 * subcollection still had docs is exactly that case (migration.md §4 area 4).
 */
async function crawlDocument(db, ref, report) {
  const [snap, collections] = await Promise.all([ref.get(), ref.listCollections()]);
  const node = { id: ref.id, exists: snap.exists, data: snap.exists ? encodeDocument(snap.data()) : null, collections: {} };

  if (!snap.exists) {
    report.missingParents.push(ref.path);
  }

  for (const col of collections) {
    node.collections[col.id] = await crawlCollection(db, col, report);
  }
  return node;
}

async function crawlCollection(db, colRef, report) {
  const level = levelOf(colRef.path);
  const known = KNOWN_COLLECTIONS[level];
  // No entry for this level means the level itself is unknown to the migration, which is at least
  // as noteworthy as an unknown collection at a known level. Both are reported.
  if (!known || !known.includes(colRef.id)) {
    report.unknownCollections.push(colRef.path);
  }
  report.seenCollections.add(templatePath(colRef.path));

  const refs = await colRef.listDocuments();
  const docs = {};
  let existing = 0;

  for (let i = 0; i < refs.length; i += READ_CHUNK) {
    const chunk = refs.slice(i, i + READ_CHUNK);
    // getAll only gives us the doc itself; subcollections still need a per-doc listCollections().
    const snaps = await db.getAll(...chunk);
    for (let j = 0; j < chunk.length; j += 1) {
      const snap = snaps[j];
      const ref = chunk[j];
      const subcollections = await ref.listCollections();
      if (!snap.exists) report.missingParents.push(ref.path);
      else existing += 1;
      const node = {
        id: ref.id,
        exists: snap.exists,
        data: snap.exists ? encodeDocument(snap.data()) : null,
        collections: {},
      };
      for (const col of subcollections) {
        node.collections[col.id] = await crawlCollection(db, col, report);
      }
      docs[ref.id] = node;
    }
  }

  report.counts[colRef.path] = existing;
  return docs;
}

function newReport() {
  return { counts: {}, missingParents: [], unknownCollections: [], seenCollections: new Set() };
}

/**
 * Un-record a document and everything beneath it from a crawl report.
 *
 * A crawl records into FOUR places keyed by path — `counts` (one key per collection path, at every
 * depth), `unknownCollections`, `missingParents` and `seenCollections` — so anything that drops a
 * crawled document after the fact has to drop all four or the report describes documents that are
 * no longer there. Both callers do exactly that: `backup.js` drops a `codeIndex` code outside
 * `--household`, and `restore.js` disowns an out-of-scope code during verification. Adjusting only
 * the top-level count (the obvious key) left the subcollection keys behind, and then a manifest
 * claimed a collection the dump did not carry / a verification counted a collection the restore
 * deliberately left alone — reported as `FAILED` on a correct restore, after the delete committed.
 *
 * `docPath` is a full document path (`codeIndex/QQQ777`). Its own top-level count key is NOT
 * touched: that is the count of the collection it lived in, which still holds its siblings, so its
 * caller owns that number (`counts.codeIndex -= 1` for an existing document, nothing for a
 * fieldless one).
 */
function forgetSubtree(report, docPath) {
  const prefix = `${docPath}/`;
  const under = (p) => p === docPath || p.startsWith(prefix);

  for (const key of Object.keys(report.counts)) if (under(key)) delete report.counts[key];
  report.unknownCollections = report.unknownCollections.filter((p) => !under(p));
  report.missingParents = report.missingParents.filter((p) => !under(p));

  // seenCollections is templated (`codeIndex/*/history`), so one entry can be contributed by
  // several sibling documents and a surviving sibling must keep it. Drop only the templates
  // beneath this document's own level that nothing left in `counts` still contributes — and only
  // those, so a count key an unrelated caller deleted on purpose cannot take a template with it.
  const levelPrefix = `${templatePath(docPath)}/`;
  const stillSeen = new Set(Object.keys(report.counts).map(templatePath));
  for (const template of [...report.seenCollections]) {
    if (template.startsWith(levelPrefix) && !stillSeen.has(template)) {
      report.seenCollections.delete(template);
    }
  }
}

/** Check the crawl against EXPECTATIONS[name]. Returns a list of human-readable problems. */
function checkExpectations(report, name, householdIds) {
  // Own-property check: a bracket lookup resolves through Object.prototype, so "constructor" and
  // "toString" were accepted here as silent no-ops (see backup.js's flag validation).
  if (!Object.keys(EXPECTATIONS).includes(name)) {
    throw new Error(`Unknown --expect value "${name}" (${Object.keys(EXPECTATIONS).join('|')})`);
  }
  const expectation = EXPECTATIONS[name];
  const problems = [];
  for (const [level, required] of Object.entries(expectation)) {
    for (const collectionId of required) {
      const template = `${level}/${collectionId}`;
      if (!report.seenCollections.has(template)) {
        problems.push(`expected collection is absent from the dump: ${template}`);
      }
    }
  }
  if (householdIds.length === 0) problems.push('the dump contains no household documents');
  return problems;
}

/** Field paths in a dump subtree that a restore will retype from double to integer (see codec). */
function findIntegralDoubles(node, docPath, acc) {
  if (node.exists) collectIntegralDoubles(node.data, docPath, acc);
  for (const [colId, docs] of Object.entries(node.collections)) {
    for (const [docId, child] of Object.entries(docs)) {
      findIntegralDoubles(child, `${docPath}/${colId}/${docId}`, acc);
    }
  }
  return acc;
}

/** Flatten a dump subtree into { path, data } write jobs and a per-collection count. */
function flattenForWrite(node, docPath, jobs) {
  if (node.exists) jobs.push({ path: docPath, data: node.data });
  for (const [colId, docs] of Object.entries(node.collections)) {
    for (const [docId, child] of Object.entries(docs)) {
      flattenForWrite(child, `${docPath}/${colId}/${docId}`, jobs);
    }
  }
}

/** Every document reference under `ref`, deepest-first, including missing parents. */
async function collectRefsDeepestFirst(ref, acc) {
  const collections = await ref.listCollections();
  for (const col of collections) {
    for (const child of await col.listDocuments()) {
      await collectRefsDeepestFirst(child, acc);
    }
  }
  acc.push(ref);
  return acc;
}

async function commitInChunks(db, items, apply) {
  let written = 0;
  for (let i = 0; i < items.length; i += BATCH_SIZE) {
    const batch = db.batch();
    for (const item of items.slice(i, i + BATCH_SIZE)) apply(batch, item);
    await batch.commit();
    written += Math.min(BATCH_SIZE, items.length - i);
  }
  return written;
}

module.exports = {
  BATCH_SIZE,
  READ_CHUNK,
  EXPECTATIONS,
  KNOWN_COLLECTIONS,
  initFirestore,
  assertUseBigInt,
  resolveCredentialSource,
  adcFilePath,
  crawlDocument,
  crawlCollection,
  newReport,
  forgetSubtree,
  checkExpectations,
  flattenForWrite,
  findIntegralDoubles,
  collectRefsDeepestFirst,
  commitInChunks,
  templatePath,
  levelOf,
  decodeDocument,
  compareEncodedDocument,
  newComparison,
};
