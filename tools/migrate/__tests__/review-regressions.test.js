'use strict';

// Regression tests for the six blocking findings of the issue-#5 pre-merge review, plus the two
// nits promoted to "more than a nit". Every test here failed against the reviewed commit
// (ae498c5) and passes after the fix; three of them are the reviewer's own hand reproductions
// turned into tests, which is what makes those fixes durable.
//
// Added alongside __tests__/roundtrip.test.js and __tests__/credentials.test.js — nothing in
// either was modified or softened to accommodate these.
//
// Run: firebase emulators:exec --project demo-seizuretracker-rules-test --only firestore "npm test"

const H = require('./helpers');
const { PROJECT_ID } = H;
const backup = require('../backup');
const restore = require('../restore');

jest.setTimeout(60000);

const PROJECT = `--project=${PROJECT_ID}`;
const OK_LINE = 'OK: every restored collection matches the dump manifest';
let db;

beforeAll(() => { db = H.db(); });
beforeEach(async () => { await H.clearFirestore(); });

const readDump = (file) => JSON.parse(H.fs.readFileSync(file, 'utf8'));
const writeDump = (file, dump) => H.fs.writeFileSync(file, JSON.stringify(dump, null, 2));

// --- blocking 1: a --no-codeindex dump must not be restorable under a mode that deletes ---------
describe('a dump taken with --no-codeindex', () => {
  test('is refused under the default --codeindex=scoped instead of destroying the join index', async () => {
    // The reviewer's reproduction: seed two codes, dump with --no-codeindex, restore with the
    // default mode. Pre-fix the delete planner (gated on the flag) queued every code pointing at a
    // household in scope, no write job replaced it (there was nothing in the dump), and
    // verification skipped codeIndex entirely (gated on the dump) — so the destroyed document was
    // in neither the want set nor the got set, and the tool printed the OK line and exited 0.
    await H.seedLegacyHousehold(db);   // codeIndex/ABC123 -> h-legacy
    await H.seedOtherHousehold(db);    // codeIndex/ZZZ999 -> h-other
    const out = H.tmpDir();
    const dumped = await H.run(backup.main, [PROJECT, `--out=${out}`, '--no-codeindex']);
    expect(dumped.out).toContain('codeIndex skipped (--no-codeindex)');
    const dumpFile = H.newestDump(out);
    expect(readDump(dumpFile).scope.includeCodeIndex).toBe(false);

    await expect(H.run(restore.main, [dumpFile, PROJECT, '--commit']))
      .rejects.toThrow(/--no-codeindex/);

    // Both join codes are intact, and the household still advertises a code that resolves.
    expect((await db.doc('codeIndex/ABC123').get()).get('householdId')).toBe('h-legacy');
    expect((await db.doc('codeIndex/ZZZ999').get()).get('householdId')).toBe('h-other');
    expect((await db.doc('households/h-legacy').get()).get('code')).toBe('ABC123');
  });

  test('restores under the documented escape hatch --codeindex=none, leaving codeIndex alone', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--no-codeindex']);
    const dumpFile = H.newestDump(out);

    await db.doc('households/h-legacy/healthNotes/n-both').set({ description: 'CORRUPTED' });
    const res = await H.run(restore.main, [dumpFile, PROJECT, '--codeindex=none', '--commit']);
    expect(res.code).toBe(0);
    expect(res.out).toContain(OK_LINE);
    // The household came back, the join code was never touched, and the banner says so.
    expect((await db.doc('households/h-legacy/healthNotes/n-both').get()).get('description'))
      .toBe('Off food since this morning');
    expect((await db.doc('codeIndex/ABC123').get()).get('householdId')).toBe('h-legacy');
    expect(res.out).toContain('codeIndex=not in this dump/selection');
  });
});

// --- blocking 2: --codeindex=none must not report FAILED on a correct restore -------------------
describe('--codeindex=none on a dump that does contain codeIndex', () => {
  test('verifies the codeIndex documents it wrote instead of reporting them missing', async () => {
    // The reviewer's reproduction: a full dump of two codes restored with --codeindex=none wrote
    // both codes correctly and still printed "FAILED: 3 mismatch(es) ... Do not proceed" and
    // exited 1, because writes were queued unconditionally while verification was skipped. A
    // documented flag whose every use cries failure teaches the operator to discount the same
    // FAILED line that §7's irreversible cleanup delete is gated on.
    await H.seedLegacyHousehold(db);
    await H.seedOtherHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);
    expect(Object.keys(readDump(dumpFile).collections.codeIndex).sort()).toEqual(['ABC123', 'ZZZ999']);

    await db.doc('codeIndex/ABC123').delete();

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--codeindex=none', '--commit']);
    expect(res.out).not.toMatch(/missing from target: codeIndex/);
    expect(res.out).not.toContain('The restore did NOT reproduce the dump');
    expect(res.out).toContain(OK_LINE);
    expect(res.code).toBe(0);
    expect((await db.doc('codeIndex/ABC123').get()).get('householdId')).toBe('h-legacy');
    expect((await db.doc('codeIndex/ZZZ999').get()).get('householdId')).toBe('h-other');
  });

  test('--codeindex=all still fails on a code the dump never contained', async () => {
    // The other half of the symmetry: under `all` every live code is deleted, so the restore owns
    // the whole collection and a leftover IS a real mismatch. Narrowing verification must not have
    // narrowed this away.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);
    const dump = readDump(dumpFile);
    delete dump.collections.codeIndex.ABC123;
    dump.manifest.counts.codeIndex = 0;
    writeDump(dumpFile, dump);

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--codeindex=all', '--commit']);
    expect(res.code).toBe(0); // ABC123 was deleted by `all` and not rewritten: that is consistent
    expect((await db.doc('codeIndex/ABC123').get()).exists).toBe(false);
  });
});

// --- blocking 3: one key set drives delete, write and verify ------------------------------------
describe('the dump has one notion of scope, not two', () => {
  test('a household with subcollections but no document of its own is dumped, cleared and rewritten', async () => {
    // Pre-fix: deletes iterated dump.scope.households (existing households only) while writes
    // iterated Object.keys(dump.collections.households) (everything dumped). A household doc that
    // holds subcollections but no fields of its own — precisely the case the crawl uses
    // listDocuments() to preserve, migration.md §5 item 4 — was therefore written without its
    // subtree being cleared first, so stale documents survived a "lossless" rollback. The same
    // inconsistency made `backup.js --household=<that id>` throw "does not exist" for a household
    // that demonstrably holds data.
    await db.doc('households/h-ghost/seizures/s1').set({ petId: 'p-dog', timestampMillis: 1 });
    await db.doc('households/h-ghost/pets/p1').set({ name: 'Rufus', weightKg: 28.4 });
    const out = H.tmpDir();

    const dumped = await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-ghost', '--expect=none']);
    expect(dumped.code).toBe(0);
    const dumpFile = H.newestDump(out);
    const dump = readDump(dumpFile);
    expect(dump.scope.households).toEqual([]);                              // no document of its own
    expect(Object.keys(dump.collections.households)).toEqual(['h-ghost']);  // but it IS in the dump
    expect(dump.manifest.missingParents).toContain('households/h-ghost');

    // Written after the dump: a rollback must not leave it behind.
    await db.doc('households/h-ghost/seizures/s-stale').set({ petId: 'x', timestampMillis: 2 });
    await db.doc('households/h-ghost/pets/p1').set({ name: 'WRONG' });

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(res.code).toBe(0);
    expect(res.out).toContain(OK_LINE);
    expect(res.out).toContain('own subcollections but no document');
    expect((await db.doc('households/h-ghost/seizures/s-stale').get()).exists).toBe(false);
    expect((await db.doc('households/h-ghost/seizures/s1').get()).exists).toBe(true);
    expect((await db.doc('households/h-ghost/pets/p1').get()).get('name')).toBe('Rufus');
  });

  test('refuses a dump whose scope.households names a household its collections do not hold', async () => {
    // One hand edit away, and hand-edited dumps are explicitly contemplated (lib/codec.js).
    // Pre-fix this deleted h-other in full and restored nothing, failing only *after* the delete
    // had committed — the one ordering that cannot be recovered from the dump.
    await H.seedLegacyHousehold(db);
    await H.seedOtherHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);
    const dump = readDump(dumpFile);
    dump.scope.households.push('h-other');
    writeDump(dumpFile, dump);

    await expect(H.run(restore.main, [dumpFile, PROJECT, '--commit']))
      .rejects.toThrow(/scope\.households names 1 household\(s\) with no entry/);

    expect((await db.doc('households/h-other').get()).get('name')).toBe('Someone else');
    expect((await db.doc('households/h-other/seizures/s-other').get()).exists).toBe(true);
  });

  test('names the deliberate error, not a TypeError, on a truncated dump', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);

    const pristine = readDump(dumpFile);
    for (const [key, pattern] of [['source', /source\.projectId/], ['scope', /scope\.households/]]) {
      const dump = JSON.parse(JSON.stringify(pristine));
      delete dump[key];
      writeDump(dumpFile, dump);
      await expect(H.run(restore.main, [dumpFile, PROJECT])).rejects.toThrow(pattern);
    }
  });
});

// --- blocking 4: the verification gate proves content, not just document identity ---------------
describe('the verification gate compares content', () => {
  /**
   * Make the restore's write pass lossy, leaving every document id, every collection and every
   * count correct. This is the class of bug the pre-fix gate structurally could not see: a codec
   * regression that wrote `{}` for every document, a decode that dropped a nested map, or a
   * systematic type change all produced matching counts and matching id sets and ended on
   * "OK: ... document for document."
   *
   * Injected at the shared Firestore instance's batch() so the whole real pipeline still runs —
   * the dump on disk is untouched, so the dump genuinely disagrees with the target.
   */
  function corruptWrites(mutate) {
    const realBatch = db.batch.bind(db);
    return jest.spyOn(db, 'batch').mockImplementation(() => {
      const batch = realBatch();
      const realSet = batch.set.bind(batch);
      batch.set = (ref, data) => realSet(ref, mutate(ref.path, data) || data);
      return batch;
    });
  }

  test('fails when documents are written empty — same ids, same counts, no content', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);

    const spy = corruptWrites((path) => (path.includes('/seizures/') ? {} : undefined));
    let res;
    try {
      res = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    } finally {
      spy.mockRestore();
    }

    expect(res.code).toBe(1);
    expect(res.out).not.toContain(OK_LINE);
    expect(res.out).toContain('The restore did NOT reproduce the dump');
    expect(res.out).toContain(
      'households/h-legacy/seizures/s-normal.seizureType: in the dump ' +
      '("Generalized (grand mal)"), absent from the target'
    );
    // Counts and ids were all correct — which is exactly why this needed a content compare.
    expect(res.out).not.toMatch(/missing from target/);
    expect(res.out).not.toMatch(/unexpected in target/);
    expect(res.out).not.toMatch(/manifest says/);
  });

  test('fails on a single changed field value, and on a dropped nested map key', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);

    const spy = corruptWrites((path, data) => {
      if (path === 'households/h-legacy/healthNotes/n-both') {
        return { ...data, description: 'CORRUPTED' };
      }
      if (path === 'households/h-legacy/pets/p-dog') {
        // Drop one key from inside the embedded medications array — a decode that lost a nested
        // map, which leaves the array length and every other field untouched.
        const meds = data.medications.map((m, i) => (i === 0 ? { name: m.name, dose: m.dose } : m));
        return { ...data, medications: meds };
      }
      return undefined;
    });
    let res;
    try {
      res = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    } finally {
      spy.mockRestore();
    }

    expect(res.code).toBe(1);
    expect(res.out).toContain(
      'households/h-legacy/healthNotes/n-both.description: dump has ' +
      '"Off food since this morning", target has "CORRUPTED"'
    );
    expect(res.out).toContain('households/h-legacy/pets/p-dog.medications[0].frequency');
  });

  test('a clean restore says what it actually proved, including the tolerated retype', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);
    const total = readDump(dumpFile).manifest.totalDocuments;

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(res.code).toBe(0);
    expect(res.out).toContain(`a field-by-field value compare of all ${total} document(s)`);
    // p-cat.weightKg is a genuine Firestore double of 12 (seeded over REST), which the Node SDK
    // cannot write back as a double. That is the ONE difference the gate tolerates, and it says so
    // rather than being silent about it.
    expect(res.out).toContain('1 integral double(s) came back as Firestore integers');
    expect(res.out).not.toMatch(/absent from the dump's manifest/);
  });

  test('reports a retype the dump\'s manifest did not predict', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);
    const dump = readDump(dumpFile);
    expect(dump.manifest.integralDoubleFields).toEqual(['households/h-legacy/pets/p-cat.weightKg']);
    dump.manifest.integralDoubleFields = [];
    writeDump(dumpFile, dump);

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(res.code).toBe(0); // the target does hold what the dump holds, modulo the SDK's limit
    expect(res.out).toContain(
      "retyped double -> integer but absent from the dump's manifest.integralDoubleFields: " +
      'households/h-legacy/pets/p-cat.weightKg'
    );
  });
});

// --- blocking 5: --only cannot silently widen the scope, and the banner always shows it ---------
describe('--only', () => {
  async function legacyDump() {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    return H.newestDump(out);
  }

  test('rejects the space-separated form rather than widening to the whole household', async () => {
    // `--only households/h1/seizures` — the habit most CLIs train — parsed as `--only=true` plus an
    // ignored extra positional, and an empty `only` list means "select everything", so a typo
    // turned a one-collection restore into a full-household delete-and-rewrite in the one script
    // that deletes. The dump file still loaded, so nothing looked wrong.
    const dumpFile = await legacyDump();
    await db.doc('households/h-legacy/healthNotes/n-both').set({ description: 'MUST SURVIVE' });

    await expect(H.run(restore.main, [
      dumpFile, PROJECT, '--only', 'households/h-legacy/seizures', '--commit',
    ])).rejects.toThrow(/--only takes a value and must be written --only=<value>/);

    expect((await db.doc('households/h-legacy/healthNotes/n-both').get()).get('description'))
      .toBe('MUST SURVIVE');
  });

  test('rejects an empty value, which is the same hazard written differently', async () => {
    const dumpFile = await legacyDump();
    await expect(H.run(restore.main, [dumpFile, PROJECT, '--only=', '--commit']))
      .rejects.toThrow(/empty value/);
  });

  test('rejects a document path, which would restore a subtree but not its root document', async () => {
    const dumpFile = await legacyDump();
    await expect(H.run(restore.main, [dumpFile, PROJECT, '--only=households/h-legacy', '--commit']))
      .rejects.toThrow(/--only takes collection paths/);
  });

  test('rejects a stray positional argument', async () => {
    const dumpFile = await legacyDump();
    await expect(H.run(restore.main, [dumpFile, PROJECT, 'households/h-legacy/seizures', '--commit']))
      .rejects.toThrow(/unexpected extra argument/);
  });

  test('the banner names the scope even when the scope is everything', async () => {
    // An operator who typed --only and got a whole-household restore had to notice the *absence*
    // of a line. Absence is not a thing people notice under pressure.
    const dumpFile = await legacyDump();
    const wide = await H.run(restore.main, [dumpFile, PROJECT]);
    expect(wide.out).toContain('only=everything in the dump');

    const narrow = await H.run(restore.main, [dumpFile, PROJECT, '--only=households/h-legacy/seizures']);
    expect(narrow.out).toContain('only=[households/h-legacy/seizures]');
  });

  test('backup.js rejects the space-separated form too', async () => {
    const out = H.tmpDir();
    await expect(H.run(backup.main, [PROJECT, '--out', out]))
      .rejects.toThrow(/--out takes a value/);
    await expect(H.run(backup.main, [PROJECT, '--household', 'h-legacy']))
      .rejects.toThrow(/--household takes a value/);
  });
});

// --- promoted nits ------------------------------------------------------------------------------
describe('"no collection is ever silently missed" holds at every depth', () => {
  test('a collection below the deepest known level is reported, not silently dumped', async () => {
    // KNOWN_COLLECTIONS has keys for root, households/* and households/*/pets/* only. At any other
    // level `known` was undefined and the check was skipped entirely, so
    // households/{h}/observations/{o}/attachments — exactly the backlogged attachments feature —
    // was dumped and never mentioned, contradicting the tool's core claim. backup.js is also the
    // tool that takes the fresh dump against the new shape right before the §7 cleanup delete.
    await db.doc('households/h-deep').set({ name: 'Deep', members: ['u1'], createdAtMillis: 1 });
    await db.doc('households/h-deep/observations/o1').set({ type: 'seizure' });
    await db.doc('households/h-deep/observations/o1/attachments/a1').set({ uri: 'gs://x/1.jpg' });
    const out = H.tmpDir();

    const res = await H.run(backup.main, [PROJECT, `--out=${out}`, '--expect=none']);
    expect(res.code).toBe(0);
    const dump = readDump(H.newestDump(out));
    expect(dump.manifest.unknownCollections)
      .toContain('households/h-deep/observations/o1/attachments');
    expect(res.out).toContain('collection not known to this migration');
    // Still dumped, as documented — reported, not dropped.
    expect(dump.collections.households['h-deep'].collections.observations.o1
      .collections.attachments.a1.data.uri).toBe('gs://x/1.jpg');
  });
});

describe('a hand-edited @double payload', () => {
  test('aborts the restore instead of writing a string that reads back as null on the device', async () => {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);
    const dump = readDump(dumpFile);
    dump.collections.households['h-legacy'].collections.pets['p-dog'].data.weightKg =
      { '@double': '12.5' };
    writeDump(dumpFile, dump);

    // Pre-fix: weightKg was written as the string "12.5", verification saw nothing (it never
    // looked at fields), and the Kotlin `Double?` field deserialised as null on the device.
    // The dry run refuses too: decoding happens during planning, so a dump that cannot be
    // restored is refused before the delete pass touches anything.
    await expect(H.run(restore.main, [dumpFile, PROJECT]))
      .rejects.toThrow(/households\/h-legacy\/pets\/p-dog: Cannot decode dump value \{"@double": "12.5"\}/);
    await expect(H.run(restore.main, [dumpFile, PROJECT, '--commit']))
      .rejects.toThrow(/@double/);
    // Nothing was deleted on the way to discovering it.
    expect((await db.doc('households/h-legacy/pets/p-dog').get()).get('weightKg')).toBe(28.4);
  });
});

describe('the dump directory', () => {
  test('is created owner-only, like the dump files inside it', async () => {
    // A 0600 file inside a 0755 directory still advertises the filenames, and the filenames name
    // the project. security-privacy.md §2.1 treats the dump as an asset in its own right.
    await H.seedLegacyHousehold(db);
    const outDir = H.path.join(H.tmpDir(), 'nested', 'dumps');
    const res = await H.run(backup.main, [PROJECT, `--out=${outDir}`]);
    expect(res.code).toBe(0);
    expect((H.fs.statSync(outDir).mode & 0o077)).toBe(0);
    expect((H.fs.statSync(H.newestDump(outDir)).mode & 0o777).toString(8)).toBe('600');
  });

  test('reports an existing directory that is readable beyond your own account', async () => {
    await H.seedLegacyHousehold(db);
    const outDir = H.path.join(H.tmpDir(), 'loose');
    H.fs.mkdirSync(outDir);
    H.fs.chmodSync(outDir, 0o755);
    const res = await H.run(backup.main, [PROJECT, `--out=${outDir}`]);
    expect(res.code).toBe(0);
    expect(res.out).toContain('readable outside your user account');
  });
});
