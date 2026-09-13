'use strict';

// Shared setup for the migration-tooling tests. These run against the Firebase Local Emulator
// Suite (Firestore on 8080) exactly like firestore-tests/, but with the Admin SDK rather than
// @firebase/rules-unit-testing — the tooling deliberately bypasses Security Rules, so there is
// nothing here for the rules harness to do.

const fs = require('fs');
const os = require('os');
const path = require('path');

const PROJECT_ID = 'demo-seizuretracker-rules-test';
process.env.FIRESTORE_EMULATOR_HOST = process.env.FIRESTORE_EMULATOR_HOST || 'localhost:8080';

const admin = require('firebase-admin');
const { getFirestore, Timestamp, GeoPoint } = require('firebase-admin/firestore');
const { encodeDocument } = require('../lib/codec');

function db() {
  if (!admin.apps.length) admin.initializeApp({ projectId: PROJECT_ID });
  const instance = getFirestore();
  try { instance.settings({ useBigInt: true }); } catch (_) { /* already set */ }
  return instance;
}

const restBase = () =>
  `http://${process.env.FIRESTORE_EMULATOR_HOST}/v1/projects/${PROJECT_ID}/databases/(default)/documents`;

async function clearFirestore() {
  const res = await fetch(
    `http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
    { method: 'DELETE' }
  );
  if (!res.ok) throw new Error(`clearFirestore failed: ${res.status} ${await res.text()}`);
}

/**
 * Write one field as a true Firestore double via the emulator's REST API.
 *
 * The Node Admin SDK cannot do this: its serializer turns any safe-integer JS number into an
 * integerValue (see lib/codec.js), so an integral double like 12.0 is unwriteable from Node. The
 * Android SDK writes them routinely from a Kotlin `Double`, so the fixture has to forge one to
 * exercise the retype the tooling reports.
 */
async function setIntegralDouble(docPath, field, value) {
  const res = await fetch(`${restBase()}/${docPath}?updateMask.fieldPaths=${field}`, {
    method: 'PATCH',
    // The emulator's REST surface evaluates firestore.rules like any client. "Bearer owner" is the
    // emulator's documented admin bypass, which is what the Admin SDK gets on its gRPC channel.
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer owner' },
    body: JSON.stringify({ fields: { [field]: { doubleValue: value } } }),
  });
  if (!res.ok) throw new Error(`setIntegralDouble failed: ${res.status} ${await res.text()}`);
}

/**
 * Run a script's main(), capturing its console output.
 *
 * A throw carries the output captured up to that point as `err.out`: a refusal has to be checked
 * for what it did NOT print (the success line) as well as for its message, and that evidence is
 * otherwise lost with the spy.
 */
async function run(main, argv) {
  const out = [];
  const spy = jest.spyOn(console, 'log').mockImplementation((...args) => out.push(args.join(' ')));
  try {
    const code = await main(argv);
    return { code, out: out.join('\n') };
  } catch (err) {
    err.out = out.join('\n');
    throw err;
  } finally {
    spy.mockRestore();
  }
}

/** Read everything under a document into a flat { docPath: encodedFields } map. */
async function snapshotEncoded(instance, ref, acc = {}) {
  const snap = await ref.get();
  if (snap.exists) acc[ref.path] = encodeDocument(snap.data());
  for (const col of await ref.listCollections()) {
    for (const child of await col.listDocuments()) await snapshotEncoded(instance, child, acc);
  }
  return acc;
}

/**
 * A temp directory for a test's dump files, removed when the file's tests finish.
 *
 * Cleaned up rather than left behind because what lands in here is a dump: the tooling's own
 * README is emphatic that a dump is an unencrypted health record and must not be left lying
 * around, and a test suite that leaves 6+ of them per run in /tmp teaches the opposite habit.
 * These hold fixture data, so this is hygiene, not exposure.
 */
const tmpDirs = [];
function tmpDir() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'st-migrate-'));
  tmpDirs.push(dir);
  return dir;
}

/** Remove every directory `tmpDir()` handed out. Registered per test file, see below. */
function cleanTmpDirs() {
  while (tmpDirs.length) fs.rmSync(tmpDirs.pop(), { recursive: true, force: true });
}

// Registered here rather than in each test file so a new file cannot forget it. `afterAll` exists
// only under Jest; requiring this module outside a test run must not throw.
if (typeof afterAll === 'function') afterAll(cleanTmpDirs);

/** The newest dump file in `dir`. */
function newestDump(dir) {
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.json')).sort();
  if (!files.length) throw new Error(`no dump written into ${dir}`);
  return path.join(dir, files[files.length - 1]);
}

/**
 * The "legacy shape" fixture from migration.md §5 "Testing the tooling": old collections, a `code`
 * field on the household doc, no roles, an embedded medications array, a `members` array uid with
 * no profile doc, and a doc with timestampMillis == 0.
 */
async function seedLegacyHousehold(instance) {
  const h = instance.collection('households').doc('h-legacy');
  await h.set({
    code: 'ABC123',
    name: 'The Atnips',
    // uid-ghost is in the array but has no members/{uid} doc — the §4 area 1 "reported, not
    // silently normalised" case. Dump/restore must carry it either way.
    members: ['uid-tom', 'uid-wife', 'uid-ghost'],
    createdAtMillis: 1700000000000,
  });

  await h.collection('seizures').doc('s-normal').set({
    petId: 'p-dog',
    loggedByUid: 'uid-tom',
    loggedByName: 'Tom',
    timestampMillis: 1756000000000,
    createdAtMillis: 1756000001000,
    durationSeconds: 240,
    seizureType: 'Generalized (grand mal)',
    symptoms: ['Paddling', 'Drooling'],
    preSeizureSigns: 'restless, pacing',
    possibleTriggers: '',
    recoveryMinutes: 30,
    recoveryNotes: 'slept it off',
    rescueMedGiven: true,
    rescueMedDetails: 'diazepam 5mg',
    notes: '',
  });
  // timestampMillis == 0 AND createdAtMillis == 0 — the Kotlin default, present on early docs.
  await h.collection('seizures').doc('s-zero-ts').set({
    petId: 'p-dog',
    loggedByUid: 'uid-wife',
    loggedByName: 'K',
    timestampMillis: 0,
    createdAtMillis: 0,
    durationSeconds: 0,
    seizureType: '',
    symptoms: [],
    preSeizureSigns: '',
    possibleTriggers: '',
    recoveryMinutes: 0,
    recoveryNotes: '',
    rescueMedGiven: false,
    rescueMedDetails: '',
    notes: '',
  });
  // createdAtMillis absent entirely, nulls present.
  await h.collection('seizures').doc('s-no-createdat').set({
    petId: 'p-cat',
    loggedByUid: 'uid-tom',
    loggedByName: 'Tom',
    timestampMillis: 1750000000000,
    durationSeconds: null,
    seizureType: '',
    symptoms: [],
    recoveryMinutes: null,
    rescueMedGiven: false,
    notes: 'no createdAt on this one',
  });

  await h.collection('healthNotes').doc('n-both').set({
    petId: 'p-cat',
    loggedByUid: 'uid-wife',
    loggedByName: 'K',
    description: 'Off food since this morning',
    notes: 'vomited twice',
    timestampMillis: 1755000000000,
    createdAtMillis: 1755000000000,
  });
  await h.collection('healthNotes').doc('n-photo').set({
    petId: 'p-dog',
    loggedByUid: 'uid-tom',
    loggedByName: 'Tom',
    description: '',
    notes: '',
    photoUri: 'content://legacy/attachment/1',
    timestampMillis: 0,
    createdAtMillis: 0,
  });

  await h.collection('pets').doc('p-dog').set({
    name: 'Rufus',
    species: 'Dog',
    breed: 'Labrador',
    weightKg: 28.4,
    birthDateMillis: 1500000000000,
    // Two entries differing ONLY in notes — migration.md §9's content-hash doc id has to keep them
    // apart, so the fixture has to contain them.
    medications: [
      { name: 'Phenobarbital', dose: '60mg', frequency: '2x daily', notes: '' },
      { name: 'Phenobarbital', dose: '60mg', frequency: '2x daily', notes: 'with food' },
    ],
    createdAtMillis: 1700000000000,
  });
  await h.collection('pets').doc('p-cat').set({
    name: 'Mitts',
    species: 'Cat',
    breed: '',
    weightKg: 5,
    birthDateMillis: null,
    medications: [],
    createdAtMillis: 1700000005000,
  });
  // A document with NO fields of its own that still owns a subcollection: a pet hard-deleted while
  // its medications remained (the orphan quirk migration.md §3 closes with `archived`). A crawl
  // built on collection.get() instead of listDocuments() drops this whole subtree silently.
  await h.collection('pets').doc('p-ghost').collection('medications').doc('m-orphan').set({
    name: 'Gabapentin', dose: '100mg', frequency: '3x daily', notes: '', active: true,
  });

  await h.collection('vets').doc('v1').set({
    name: 'Dr. Reyes', clinic: 'Northside Animal', phone: '555-0100', email: '', notes: '',
  });
  await h.collection('petVetLinks').doc('l1').set({ petId: 'p-dog', vetId: 'v1' });

  await h.collection('members').doc('uid-tom').set({
    displayName: 'Tom', authMethod: 'google', joinedAtMillis: 1700000000000,
  });
  await h.collection('members').doc('uid-wife').set({
    displayName: 'K', authMethod: 'google', joinedAtMillis: 1700000100000,
  });
  // deliberately no members/uid-ghost

  // A collection this migration has never heard of. backup.js must dump it and shout about it.
  await h.collection('futureThing').doc('x1').set({ hello: 'world' });

  await instance.collection('codeIndex').doc('ABC123').set({ householdId: 'h-legacy' });

  // p-cat.weightKg as a genuine integral double (see setIntegralDouble).
  await setIntegralDouble('households/h-legacy/pets/p-cat', 'weightKg', 12);
}

/** A second household that must survive a scoped restore untouched. */
async function seedOtherHousehold(instance) {
  const h = instance.collection('households').doc('h-other');
  await h.set({ code: 'ZZZ999', name: 'Someone else', members: ['uid-stranger'], createdAtMillis: 1 });
  await h.collection('seizures').doc('s-other').set({ petId: 'p-x', timestampMillis: 1 });
  await instance.collection('codeIndex').doc('ZZZ999').set({ householdId: 'h-other' });
}

module.exports = {
  PROJECT_ID, db, clearFirestore, setIntegralDouble, run, snapshotEncoded, tmpDir, cleanTmpDirs,
  newestDump,
  seedLegacyHousehold, seedOtherHousehold, Timestamp, GeoPoint, fs, path,
};
