'use strict';

// Round-trip test for tools/migrate/backup.js + restore.js against the Firebase Local Emulator
// Suite. migration.md §5 "Testing the tooling" — the hand-seeded "legacy shape" household with the
// accumulated cruft a synthetic fixture usually lacks.
//
// Run: firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore "npm test"

const H = require('./helpers');
const { PROJECT_ID } = H;
const backup = require('../backup');
const restore = require('../restore');

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

    const saved = process.env.FIRESTORE_EMULATOR_HOST;
    process.env.FIRESTORE_EMULATOR_HOST = '';
    process.env.GOOGLE_APPLICATION_CREDENTIALS = '/nonexistent/key.json';
    try {
      await expect(H.run(restore.main, [dumpFile, PROJECT, '--commit']))
        .rejects.toThrow(/--allow-prod/);
    } finally {
      process.env.FIRESTORE_EMULATOR_HOST = saved;
      delete process.env.GOOGLE_APPLICATION_CREDENTIALS;
    }
  });
});
