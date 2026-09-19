'use strict';

// Round-trip test for tools/migrate/backup.js + restore.js against the Firebase Local Emulator
// Suite. migration.md §5 "Testing the tooling" — the hand-seeded "legacy shape" household with the
// accumulated cruft a synthetic fixture usually lacks.
//
// Run: firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore "npm test"

const fs = require('fs');
const path = require('path');

const H = require('./helpers');
const { PROJECT_ID } = H;
const backup = require('../backup');
const restore = require('../restore');
const { commitInChunks, BATCH_SIZE } = require('../lib/firestore');

jest.setTimeout(60000);

const PROJECT = `--project=${PROJECT_ID}`;
let db;

beforeAll(() => { db = H.db(); });
beforeEach(async () => { await H.clearFirestore(); });

describe('the Admin SDK assumption the codec rests on', () => {
  test('integers come back as BigInt, doubles as number (useBigInt is in effect)', async () => {
    await db.doc('households/probe').set({ i: 7, d: 7.5 });
    const data = (await db.doc('households/probe').get()).data();
    expect(typeof data.i).toBe('bigint');
    expect(typeof data.d).toBe('number');
  });
});

describe('backup.js', () => {
  test('dumps the legacy household, every subcollection and codeIndex, with a count manifest', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();

    const { code, out: log } = await H.run(backup.main, [PROJECT, `--out=${out}`, '--expect=legacy']);
    expect(code).toBe(0);

    const dump = JSON.parse(H.fs.readFileSync(H.newestDump(out), 'utf8'));
    expect(dump.format).toBe('seizuretracker-firestore-dump/1');
    expect(dump.scope.households).toEqual(['h-legacy']);

    // Subcollections were DISCOVERED, not assumed: futureThing is in the dump and nothing in the
    // tooling knows what it is.
    expect(dump.manifest.counts).toEqual({
      households: 1,
      'households/h-legacy/seizures': 3,
      'households/h-legacy/healthNotes': 2,
      'households/h-legacy/pets': 2,
      'households/h-legacy/pets/p-ghost/medications': 1,
      'households/h-legacy/vets': 1,
      'households/h-legacy/petVetLinks': 1,
      'households/h-legacy/members': 2,
      'households/h-legacy/futureThing': 1,
      codeIndex: 1,
    });
    expect(dump.manifest.totalDocuments).toBe(15);

    expect(log).toContain('collection not known to this migration');
    expect(dump.manifest.unknownCollections).toEqual(['households/h-legacy/futureThing']);

    // The fieldless pet that owns a medications subcollection is recorded, not dropped.
    expect(dump.manifest.missingParents).toEqual(['households/h-legacy/pets/p-ghost']);
    expect(dump.collections.households['h-legacy'].collections.pets['p-ghost'].exists).toBe(false);
    expect(
      dump.collections.households['h-legacy'].collections.pets['p-ghost']
        .collections.medications['m-orphan'].data.name
    ).toBe('Gabapentin');

    // The cruft cases survived the crawl with their types intact.
    const seizures = dump.collections.households['h-legacy'].collections.seizures;
    expect(seizures['s-zero-ts'].data.timestampMillis).toEqual({ '@int': '0' });
    expect(seizures['s-no-createdat'].data.createdAtMillis).toBeUndefined();
    expect(seizures['s-no-createdat'].data.durationSeconds).toBeNull();
    expect(dump.collections.households['h-legacy'].data.members).toEqual(['uid-tom', 'uid-wife', 'uid-ghost']);
    expect(dump.collections.households['h-legacy'].data.code).toBe('ABC123');
  });

  test('reports the integral-double fields a restore cannot reproduce', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    const { out: log } = await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dump = JSON.parse(H.fs.readFileSync(H.newestDump(out), 'utf8'));

    expect(dump.manifest.integralDoubleFields).toEqual(['households/h-legacy/pets/p-cat.weightKg']);
    expect(log).toContain('re-write them as Firestore integers');
  });

  test('--require-expected exits non-zero when an expected collection is absent', async () => {
    // A household with no healthNotes/vets/petVetLinks/members at all.
    await db.doc('households/h-thin').set({ code: 'THIN01', name: 'Thin', members: ['u1'], createdAtMillis: 1 });
    await db.doc('households/h-thin/seizures/s1').set({ timestampMillis: 1 });
    const out = H.tmpDir();

    const warned = await H.run(backup.main, [PROJECT, `--out=${out}`, '--expect=legacy']);
    expect(warned.code).toBe(0);
    expect(warned.out).toContain('expected collection is absent from the dump: households/*/healthNotes');

    const strict = await H.run(backup.main, [PROJECT, `--out=${out}`, '--expect=legacy', '--require-expected']);
    expect(strict.code).toBe(1);
    // The dump is still the artifact even on a failed assertion.
    expect(strict.out).toContain('The dump file was still written');
  });

  test('--expect=none accepts the target shape, and every Firestore value type round-trips', async () => {
    const h = db.collection('households').doc('h-types');
    await h.set({ name: 'Types', members: ['u1'], createdAtMillis: 1 });
    await h.collection('observations').doc('o1').set({
      type: 'seizure',
      occurredAt: H.Timestamp.fromMillis(1756000000123),
      createdAt: new H.Timestamp(1756000000, 123456789),
      where: new H.GeoPoint(35.1495, -90.049),
      blob: Buffer.from([0, 1, 2, 255]),
      big: 9007199254740993n,
      nan: NaN,
      negInf: -Infinity,
      negZero: -0,
      ref: db.doc('households/h-types'),
      nested: { a: [1, 2.5, { b: null }], tagLike: { '@int': 'not a tag' } },
    });
    await h.collection('private').doc('config').set({ joinCode: 'ABC123' });
    await h.collection('members').doc('u1').set({ displayName: 'U', authMethod: 'google', joinedAtMillis: 1, role: 'admin' });
    await h.collection('pets').doc('p1').set({ name: 'P', archived: false, createdAtMillis: 1 });
    await h.collection('pets').doc('p1').collection('medications').doc('m1')
      .set({ name: 'Pheno', active: true, startDate: null, endDate: null });
    await h.collection('vets').doc('v1').set({ name: 'V' });
    await h.collection('petVetLinks').doc('l1').set({ petId: 'p1', vetId: 'v1' });

    const out = H.tmpDir();
    const before = await H.snapshotEncoded(db, db.doc('households/h-types'));
    const { code } = await H.run(backup.main, [PROJECT, `--out=${out}`, '--expect=target', '--require-expected']);
    expect(code).toBe(0);

    const dumpFile = H.newestDump(out);
    await H.clearFirestore();
    const restored = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(restored.code).toBe(0);

    const after = await H.snapshotEncoded(db, db.doc('households/h-types'));
    expect(after).toEqual(before);
  });
});

describe('restore.js', () => {
  test('round-trips the legacy household field-for-field after a wipe, and matches the manifest', async () => {
    await H.seedLegacyHousehold(db);
    await H.seedOtherHousehold(db);
    const out = H.tmpDir();

    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);
    const before = await H.snapshotEncoded(db, db.doc('households/h-legacy'));

    // Destroy and mutate: delete a whole subcollection's worth, corrupt one doc, add a stray doc
    // that must not survive.
    await db.doc('households/h-legacy/seizures/s-normal').delete();
    await db.doc('households/h-legacy/seizures/s-zero-ts').delete();
    await db.doc('households/h-legacy/healthNotes/n-both').set({ description: 'CORRUPTED' });
    await db.doc('households/h-legacy/seizures/s-stray').set({ petId: 'nope', timestampMillis: 1 });
    await db.doc('households/h-legacy').set({ name: 'WRONG', members: [], createdAtMillis: 0 });

    const { code, out: log } = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(log).toContain('OK: every restored collection matches the dump manifest');
    expect(code).toBe(0);

    const after = await H.snapshotEncoded(db, db.doc('households/h-legacy'));

    // The one documented exception: an integral double comes back as an integer, because the Node
    // Admin SDK cannot write a doubleValue for a safe integer (lib/codec.js).
    expect(before['households/h-legacy/pets/p-cat'].weightKg).toEqual({ '@double': 12 });
    expect(after['households/h-legacy/pets/p-cat'].weightKg).toEqual({ '@int': '12' });
    delete before['households/h-legacy/pets/p-cat'].weightKg;
    delete after['households/h-legacy/pets/p-cat'].weightKg;

    expect(after).toEqual(before);
    expect(Object.keys(after).sort()).toEqual([
      'households/h-legacy',
      'households/h-legacy/futureThing/x1',
      'households/h-legacy/healthNotes/n-both',
      'households/h-legacy/healthNotes/n-photo',
      'households/h-legacy/members/uid-tom',
      'households/h-legacy/members/uid-wife',
      'households/h-legacy/petVetLinks/l1',
      'households/h-legacy/pets/p-cat',
      'households/h-legacy/pets/p-dog',
      'households/h-legacy/pets/p-ghost/medications/m-orphan',
      'households/h-legacy/seizures/s-no-createdat',
      'households/h-legacy/seizures/s-normal',
      'households/h-legacy/seizures/s-zero-ts',
      'households/h-legacy/vets/v1',
    ]);
  });

  test('leaves a household outside the dump scope, and its codeIndex entry, untouched', async () => {
    await H.seedLegacyHousehold(db);
    await H.seedOtherHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);

    const { code, out: log } = await H.run(restore.main, [H.newestDump(out), PROJECT, '--commit']);
    expect(code).toBe(0);
    expect(log).toContain('codeIndex doc left in place');

    expect((await db.doc('households/h-other').get()).get('name')).toBe('Someone else');
    expect((await db.doc('households/h-other/seizures/s-other').get()).exists).toBe(true);
    expect((await db.doc('codeIndex/ZZZ999').get()).get('householdId')).toBe('h-other');
    expect((await db.doc('codeIndex/ABC123').get()).get('householdId')).toBe('h-legacy');
  });

  test('is a dry run by default and writes nothing', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    await db.doc('households/h-legacy/healthNotes/n-both').set({ description: 'CORRUPTED' });

    const { code, out: log } = await H.run(restore.main, [H.newestDump(out), PROJECT]);
    expect(code).toBe(0);
    expect(log).toContain('Dry run — nothing was written');
    expect((await db.doc('households/h-legacy/healthNotes/n-both').get()).get('description')).toBe('CORRUPTED');
  });

  test('--only restores just the named collections and leaves the rest alone', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);

    await db.doc('households/h-legacy/seizures/s-normal').delete();
    await db.doc('households/h-legacy/healthNotes/n-both').set({ description: 'STILL CORRUPTED' });

    const { code } = await H.run(restore.main, [
      dumpFile, PROJECT, '--only=households/h-legacy/seizures', '--commit',
    ]);
    expect(code).toBe(0);

    expect((await db.doc('households/h-legacy/seizures/s-normal').get()).get('seizureType'))
      .toBe('Generalized (grand mal)');
    // Untouched by --only, so still corrupted — proves the selector actually narrows.
    expect((await db.doc('households/h-legacy/healthNotes/n-both').get()).get('description'))
      .toBe('STILL CORRUPTED');
  });

  test('exits non-zero and names the mismatch when the result does not match the manifest', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);

    // Simulate a dump whose manifest and body disagree — which is what a half-written dump, or a
    // restore that silently dropped a batch, looks like from the outside.
    const dump = JSON.parse(H.fs.readFileSync(dumpFile, 'utf8'));
    dump.manifest.counts['households/h-legacy/seizures'] = 99;
    H.fs.writeFileSync(dumpFile, JSON.stringify(dump));

    const { code, out: log } = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(code).toBe(1);
    expect(log).toContain('manifest says 99 doc(s), target has 3');
    expect(log).toContain('The restore did NOT reproduce the dump');
  });

  test('refuses a dump from a different project unless --allow-project-mismatch', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);
    const dump = JSON.parse(H.fs.readFileSync(dumpFile, 'utf8'));
    dump.source.projectId = 'seizuretracker-prod';
    H.fs.writeFileSync(dumpFile, JSON.stringify(dump));

    await expect(H.run(restore.main, [dumpFile, PROJECT, '--commit']))
      .rejects.toThrow(/--allow-project-mismatch/);

    const ok = await H.run(restore.main, [dumpFile, PROJECT, '--allow-project-mismatch', '--commit']);
    expect(ok.code).toBe(0);
  });

  test('refuses to run without an emulator host unless --allow-prod', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);

    // A real (fake-contents) key file, not a path that does not exist: resolveCredentialSource now
    // reads the file to decide whether it names its own project, so a bogus path fails there
    // instead of reaching the gate this test is about. The gate is what is under test, so give it
    // a credential that resolves.
    const keyFile = path.join(H.tmpDir(), 'fake-service-account.json');
    fs.writeFileSync(keyFile, JSON.stringify({
      type: 'service_account',
      project_id: PROJECT_ID,
      private_key_id: 'not-a-real-key-id',
      private_key: '-----BEGIN PRIVATE KEY-----\nNOT-A-REAL-KEY\n-----END PRIVATE KEY-----\n',  // id-scan:ignore — fake fixture, not a key
      client_email: `not-a-real-account@${PROJECT_ID}.iam.gserviceaccount.com`,
    }), { mode: 0o600 });

    const saved = process.env.FIRESTORE_EMULATOR_HOST;
    process.env.FIRESTORE_EMULATOR_HOST = '';
    process.env.GOOGLE_APPLICATION_CREDENTIALS = keyFile;
    try {
      await expect(H.run(restore.main, [dumpFile, PROJECT, '--commit']))
        .rejects.toThrow(/--allow-prod/);
    } finally {
      process.env.FIRESTORE_EMULATOR_HOST = saved;
      delete process.env.GOOGLE_APPLICATION_CREDENTIALS;
    }
  });
});

// --- QA additions (issue #5): coverage gaps found during mutation-probing --------------------
// See the QA report on #5 for the mutation-probe table these close or explain. Not a rewrite of
// the implementer's tests above — added alongside them.
describe('coverage gaps (QA, #5)', () => {
  test('a household with an empty collection (no healthNotes at all) dumps and restores cleanly', async () => {
    // In Firestore an empty collection does not exist, so this household legitimately has no
    // healthNotes/vets/petVetLinks/members subcollection at all — the case --require-expected
    // softening (lib/firestore.js EXPECTATIONS comment) depends on being indistinguishable from
    // "the crawl missed it" only by intent, not by crashing either script.
    const h = db.collection('households').doc('h-sparse');
    await h.set({ name: 'Sparse', members: ['u1'], createdAtMillis: 1 });
    await h.collection('seizures').doc('s1').set({ petId: 'p1', timestampMillis: 1 });
    // No healthNotes, vets, petVetLinks, members, pets docs at all.

    const out = H.tmpDir();
    const { code: backupCode, out: backupLog } = await H.run(backup.main, [PROJECT, `--out=${out}`, '--expect=legacy']);
    expect(backupCode).toBe(0); // warns, does not fail, without --require-expected
    expect(backupLog).toContain('expected collection is absent from the dump: households/*/healthNotes');

    const dump = JSON.parse(H.fs.readFileSync(H.newestDump(out), 'utf8'));
    expect(dump.manifest.counts).toEqual({ households: 1, 'households/h-sparse/seizures': 1, codeIndex: 0 });
    expect(dump.manifest.counts['households/h-sparse/healthNotes']).toBeUndefined();

    const { code: restoreCode, out: restoreLog } = await H.run(restore.main, [H.newestDump(out), PROJECT, '--commit']);
    expect(restoreCode).toBe(0);
    expect(restoreLog).toContain('OK: every restored collection matches the dump manifest');

    // The restore must not have fabricated an empty healthNotes collection along the way.
    expect((await h.collection('healthNotes').listDocuments()).length).toBe(0);
    expect((await h.collection('seizures').doc('s1').get()).exists).toBe(true);
  });

  test('restore deletes a document under the household that exists in the target but was never in the dump at all', async () => {
    // Distinct from the implementer's "leaves a stray doc in an already-dumped collection" case
    // (round-trip test above, s-stray under seizures, which IS a known collection): this document
    // sits in a collection the dump never saw or discovered at backup time, added to the target
    // only *after* the dump was taken — the shape a real rollback hits if someone writes new data
    // between the dump and the restore.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);

    await db.doc('households/h-legacy/postDumpCollection/late1').set({ hello: 'added after the dump' });

    const { code, out: log } = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(code).toBe(0);
    expect(log).toContain('OK: every restored collection matches the dump manifest');

    // README's restore procedure ("delete the named collections, re-write every document") must
    // match this: the whole household subtree is deleted before rewriting from the dump, so a
    // doc added after the dump and before the restore does not survive it.
    expect((await db.doc('households/h-legacy/postDumpCollection/late1').get()).exists).toBe(false);
  });

  test('a second --commit restore of the same dump is a no-op', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);

    const first = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(first.code).toBe(0);
    const afterFirst = await H.snapshotEncoded(db, db.doc('households/h-legacy'));

    const second = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(second.code).toBe(0);
    expect(second.out).toContain('OK: every restored collection matches the dump manifest');
    const afterSecond = await H.snapshotEncoded(db, db.doc('households/h-legacy'));

    expect(afterSecond).toEqual(afterFirst);
  });

  test('commitInChunks writes and deletes correctly across a batch boundary, not just within one batch', async () => {
    // Every fixture above tops out around 15 documents, far under BATCH_SIZE (400), so the loop in
    // lib/firestore.js never iterates more than once anywhere in the suite above — a bug that only
    // drops or mis-writes the second (or a trailing partial) chunk would pass every test above.
    // This exercises the real BATCH_SIZE with enough documents to force multiple chunks.
    const total = BATCH_SIZE * 2 + 7; // two full batches plus one partial tail batch
    const col = db.collection('households').doc('h-batch').collection('probe');
    const refs = Array.from({ length: total }, (_, i) => col.doc(`d${i}`));

    const written = await commitInChunks(db, refs, (batch, ref) => batch.set(ref, { i: refs.indexOf(ref) }));
    expect(written).toBe(total);

    const snap = await col.get();
    expect(snap.size).toBe(total);
    // The tail batch (the partial one) specifically made it in, not just the full leading batches.
    const lastId = `d${total - 1}`;
    expect((await col.doc(lastId).get()).exists).toBe(true);

    const deleteRefs = (await col.listDocuments());
    const deleted = await commitInChunks(db, deleteRefs, (batch, ref) => batch.delete(ref));
    expect(deleted).toBe(total);
    expect((await col.get()).size).toBe(0);
  });
});
