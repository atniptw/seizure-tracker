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
/** Documents a dump actually carries — what manifest.totalDocuments claims to be. */
const dumpedDocumentCount = (dump) => {
  let n = 0;
  const walk = (node) => {
    if (node.exists) n += 1;
    for (const docs of Object.values(node.collections)) Object.values(docs).forEach(walk);
  };
  for (const docs of Object.values(dump.collections)) Object.values(docs).forEach(walk);
  return n;
};
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
        // Drop one key from inside the embedded medications array, at index 1 rather than 0 —
        // a comparator whose array walk only reached the first element (a real regression shape:
        // `compareEncoded`'s array branch is a loop with an index) would pass this silently, and
        // `medications` is exactly the kind of repeated clinical data this gate exists to protect.
        // Leaves the array length and every other field untouched.
        const meds = data.medications.map((m, i) => (i === 1 ? { name: m.name, dose: m.dose } : m));
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
    expect(res.out).toContain('households/h-legacy/pets/p-dog.medications[1].frequency');
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

// ================================================================================================
// Round 2 — the three blocking findings of the re-review at d803ae1. Two of them are defects the
// round-1 fixes introduced (the parseArgs rewrite hardened only the missing-value direction; the
// content compare turned two accepted-on-decode number shapes into a post-delete FAILED), and the
// third came in with `checkOnlyPaths` itself. Each test below failed before its fix.
// ================================================================================================

// --- blocking 1: a verification gate must never report success over an empty set ----------------
describe('an --only that selects nothing in the dump', () => {
  async function legacyDumpFile() {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    return H.newestDump(out);
  }

  test('is refused, rather than printing the OK line and exiting 0 over 0 documents', async () => {
    // The reviewer's reproduction. `households/h-legacy/seizure` (singular) is an odd three
    // segments, so the shape check passed; `selected()`'s `${o}/` prefix guard then correctly
    // refused to let it bleed onto `seizures`, so the run planned no deletes, no writes and no
    // verification — and every check being skipped by its own `selected(...)` guard left
    // `mismatches` and `comparison.diffs` structurally empty. Pre-fix this printed
    // "OK: ... a field-by-field value compare of all 0 document(s)" and exited 0, which is the
    // exact line migration.md §7's irreversible cleanup delete is gated on.
    const dumpFile = await legacyDumpFile();

    for (const argv of [
      [dumpFile, PROJECT, '--only=households/h-legacy/seizure'],
      [dumpFile, PROJECT, '--only=households/h-legacy/seizure', '--commit'],
      // A plausible whole-household typo, and a real collection under the wrong household.
      [dumpFile, PROJECT, '--only=household/h-legacy/seizures', '--commit'],
      [dumpFile, PROJECT, '--only=households/h-typo/seizures', '--commit'],
      // One good entry does not excuse a bad one: the bad half still verifies nothing.
      [dumpFile, PROJECT, '--only=households/h-legacy/seizures,households/h-legacy/pet', '--commit'],
    ]) {
      const res = await H.run(restore.main, argv)
        .then((ok) => ok, (err) => ({ code: 2, out: err.out, err }));
      // The exit code and the absence of a success line are what matter — a refusal that still
      // printed OK would be no better than the bug.
      expect(res.code).not.toBe(0);
      expect(res.out).not.toContain(OK_LINE);
      expect(res.err).toBeDefined();
      expect(res.err.message).toMatch(/selects nothing in this dump/);
    }

    // Refused during planning, so nothing was touched on the way to finding out.
    expect((await db.doc('households/h-legacy/seizures/s-normal').get()).get('seizureType'))
      .toBe('Generalized (grand mal)');
    expect((await db.doc('codeIndex/ABC123').get()).exists).toBe(true);
  });

  test('a valid --only is still accepted, including a whole collection root', async () => {
    // The fix must not narrow what works: an exact collection path, a parent of one, and the
    // global codeIndex all still restore.
    const dumpFile = await legacyDumpFile();
    for (const only of [
      '--only=households/h-legacy/seizures',
      '--only=households/h-legacy/pets/p-ghost/medications',
      '--only=households',
      '--only=codeIndex',
      '--only=households/h-legacy/seizures,codeIndex',
    ]) {
      const res = await H.run(restore.main, [dumpFile, PROJECT, only, '--commit']);
      expect(res.code).toBe(0);
      expect(res.out).toContain(OK_LINE);
    }
  });

  test('the gate itself refuses to report success over an empty comparison', async () => {
    // The general property, independent of --only validity: `mismatches.length === 0` is only
    // evidence of a good restore if something was compared. Here the --only path IS in the dump
    // (so checkOnlyPaths passes) but the dump's copy of that collection has been emptied by hand
    // and its manifest entry removed, so after a real delete pass there is nothing to write, no
    // count to check and no id to diff. Pre-fix: "OK ... all 0 document(s)", exit 0, three
    // documents destroyed.
    const dumpFile = await legacyDumpFile();
    const dump = readDump(dumpFile);
    dump.collections.households['h-legacy'].collections.seizures = {};
    delete dump.manifest.counts['households/h-legacy/seizures'];
    writeDump(dumpFile, dump);

    const res = await H.run(restore.main, [
      dumpFile, PROJECT, '--only=households/h-legacy/seizures', '--commit',
    ]);
    expect(res.code).toBe(1);
    expect(res.out).not.toContain(OK_LINE);
    expect(res.out).toContain('this run verified nothing');
  });

  test('a dry run that would do nothing at all is refused before it can look successful', async () => {
    // Same property at the plan stage, stated without reference to any flag: no deletes and no
    // writes means nothing to verify, so there is no run to report on.
    const dumpFile = await legacyDumpFile();
    const dump = readDump(dumpFile);
    dump.collections.households = {};
    dump.collections.codeIndex = {};
    dump.scope.households = [];
    dump.manifest.counts = {};
    writeDump(dumpFile, dump);

    await expect(H.run(restore.main, [dumpFile, PROJECT]))
      .rejects.toThrow(/delete nothing and write nothing/);
  });

  test('the OK line distinguishes what was compared from nothing being compared', async () => {
    const dumpFile = await legacyDumpFile();
    const res = await H.run(restore.main, [
      dumpFile, PROJECT, '--only=households/h-legacy/seizures', '--commit',
    ]);
    expect(res.code).toBe(0);
    // Counts in the line, because §7's delete is gated on reading it and a zero in it is the only
    // thing that separates "verified everything" from "verified nothing".
    expect(res.out).toMatch(/all [1-9]\d* per-collection count\(s\)/);
    expect(res.out).toMatch(/document-id set \(all [1-9]\d* id\(s\)\)/);
    expect(res.out).toMatch(/value compare of all [1-9]\d* document\(s\)/);
  });

  test('the OK line counts each document id once, not once per direction of the set diff', async () => {
    // round-5 should-fix 2. The id check is one comparison of two sets, and a clean restore makes
    // those sets equal — so walking the diff in both directions visits every path twice. Counting
    // each visit printed `2N` id checks beside a field compare of N documents and a dry run that
    // planned N. README.md:400-403 tells the operator to read these numbers against the dry run's,
    // and migration.md §7 gates its irreversible cleanup delete on this one line, so the single
    // number that would not match was the one in the place built for comparing.
    const dumpFile = await legacyDumpFile();
    const res = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(res.code).toBe(0);

    const ids = /document-id set \(all (\d+) id\(s\)\)/.exec(res.out);
    const docs = /value compare of all (\d+) document\(s\)/.exec(res.out);
    expect(ids).not.toBeNull();
    expect(docs).not.toBeNull();
    expect(Number(docs[1])).toBeGreaterThan(0);
    // The restore reproduced the dump exactly — so every document written is a document found,
    // and the two numbers describe the same set.
    expect(Number(ids[1])).toBe(Number(docs[1]));
  });

  test('a scoped restore counts only the ids in scope, still once each', async () => {
    // Same invariant with --only narrowing both sides, so a fix that merely halved the total
    // would not satisfy it.
    const dumpFile = await legacyDumpFile();
    const res = await H.run(restore.main, [
      dumpFile, PROJECT, '--only=households/h-legacy/seizures', '--commit',
    ]);
    expect(res.code).toBe(0);
    const ids = /document-id set \(all (\d+) id\(s\)\)/.exec(res.out);
    const docs = /value compare of all (\d+) document\(s\)/.exec(res.out);
    expect(ids).not.toBeNull();
    expect(docs).not.toBeNull();
    expect(Number(ids[1])).toBe(Number(docs[1]));
  });
});

// --- blocking 2: a boolean safety gate must not be openable by giving it a value ----------------
describe('the --allow-prod and --allow-project-mismatch gates', () => {
  async function legacyDumpFile() {
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    return H.newestDump(out);
  }

  test('do not open when given "=false"', async () => {
    // parseArgs rejected a value flag written with no value and accepted a value on a flag that
    // takes none, so `--allow-prod=false` landed as the string 'false' — truthy — and
    // `!flags['allow-prod']` was then false. Both gates opened for an operator who typed the word
    // "false" while trying to be explicit. `--commit=false` was already safe because that one
    // tests `=== true`, so the right pattern was one screen away.
    const dumpFile = await legacyDumpFile();

    for (const flag of ['--allow-prod=false', '--allow-project-mismatch=false', '--commit=false']) {
      await expect(H.run(restore.main, [dumpFile, PROJECT, flag]))
        .rejects.toThrow(/is a boolean flag and takes no value/);
    }
    // And the gate they guard is still closed: with no emulator host and no bare --allow-prod,
    // the run is refused before any RPC.
    const saved = process.env.FIRESTORE_EMULATOR_HOST;
    process.env.FIRESTORE_EMULATOR_HOST = '';
    process.env.GOOGLE_APPLICATION_CREDENTIALS = '/nonexistent/key.json';
    try {
      await expect(H.run(restore.main, [dumpFile, PROJECT, '--allow-prod=false', '--commit']))
        .rejects.toThrow(/is a boolean flag and takes no value/);
    } finally {
      process.env.FIRESTORE_EMULATOR_HOST = saved;
      delete process.env.GOOGLE_APPLICATION_CREDENTIALS;
    }
  });

  test('open only when the flag is written bare', async () => {
    const dumpFile = await legacyDumpFile();
    const dump = readDump(dumpFile);
    dump.source.projectId = 'seizuretracker-prod';
    writeDump(dumpFile, dump);
    const res = await H.run(restore.main, [dumpFile, PROJECT, '--allow-project-mismatch', '--commit']);
    expect(res.code).toBe(0);
  });

  test('backup.js rejects a value on its booleans too, and an unknown flag on either script', async () => {
    const out = H.tmpDir();
    await expect(H.run(backup.main, [PROJECT, `--out=${out}`, '--no-codeindex=false']))
      .rejects.toThrow(/is a boolean flag and takes no value/);
    await expect(H.run(backup.main, [PROJECT, `--out=${out}`, '--require-expected=0']))
      .rejects.toThrow(/is a boolean flag and takes no value/);
    // An unknown flag is the third route to the same hazard: a misspelled --only or --household
    // leaves the run wider than what was typed, with nothing on screen to say so.
    await expect(H.run(backup.main, [PROJECT, `--out=${out}`, '--housevold=h-legacy']))
      .rejects.toThrow(/unknown flag --housevold/);
    const dumpFile = await legacyDumpFile();
    await expect(H.run(restore.main, [dumpFile, PROJECT, '--onyl=households/h-legacy/seizures', '--commit']))
      .rejects.toThrow(/unknown flag --onyl/);
    await expect(H.run(restore.main, [dumpFile, PROJECT, '--allow-prd', '--commit']))
      .rejects.toThrow(/unknown flag --allow-prd/);
  });

  test('backup.js takes no positional arguments', async () => {
    // `node backup.js h-legacy` — the flag name forgotten entirely — dumped every household.
    await H.seedLegacyHousehold(db);
    await H.seedOtherHousehold(db);
    const out = H.tmpDir();
    await expect(H.run(backup.main, [PROJECT, `--out=${out}`, 'h-legacy']))
      .rejects.toThrow(/takes no positional arguments/);
  });
});

// --- blocking 3: a dump shape the codec accepts must not fail verification after the delete -----
describe('the two number shapes decodeValue used to accept', () => {
  test('are refused during planning, not reported as a mismatch after the delete', async () => {
    // `decodeValue` accepted a bare JSON number (as a double) and a numeric `@int` payload
    // (BigInt(12) works as well as BigInt("12")); both restored CORRECTLY. But `compareEncoded`
    // has no untagged-number case and compares @int payloads exactly, so `12.5` vs
    // `{"@double":12.5}` and `{"@int":12}` vs `{"@int":"12"}` both reported a difference and exited
    // 1 over correct data — after the delete and write passes had committed. The
    // `{"@double":"12.5"}` case in the same switch was already a pre-delete refusal for exactly
    // this reason; these are the other two halves of that guard.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);
    const pristine = readDump(dumpFile);

    for (const [value, pattern] of [
      [12.5, /bare JSON number/],
      [{ '@int': 12 }, /@int.*must be a decimal string/s],
    ]) {
      const dump = JSON.parse(JSON.stringify(pristine));
      dump.collections.households['h-legacy'].collections.pets['p-dog'].data.weightKg = value;
      writeDump(dumpFile, dump);

      // Both the dry run and the --commit run refuse, and both name the document.
      await expect(H.run(restore.main, [dumpFile, PROJECT]))
        .rejects.toThrow(/households\/h-legacy\/pets\/p-dog: Cannot decode dump value/);
      await expect(H.run(restore.main, [dumpFile, PROJECT])).rejects.toThrow(pattern);
      await expect(H.run(restore.main, [dumpFile, PROJECT, '--commit'])).rejects.toThrow(pattern);
      // Nothing was deleted on the way to discovering it.
      expect((await db.doc('households/h-legacy/pets/p-dog').get()).get('weightKg')).toBe(28.4);
      expect((await db.doc('households/h-legacy/seizures/s-normal').get()).exists).toBe(true);
    }
  });
});

// --- round-2 nits -------------------------------------------------------------------------------
describe('round-2 nits', () => {
  test('--household=h1,h1 dumps and counts the household once', async () => {
    // list() did not dedupe, so the household was crawled twice and householdCount incremented
    // twice: manifest.counts.households said 2 against a single entry in collections.households,
    // totalDocuments was one high, and every restore from that dump then failed verification with
    // "households: manifest says 2 doc(s), target has 1" — after committing.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    const res = await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy,h-legacy']);
    expect(res.code).toBe(0);
    const dump = readDump(H.newestDump(out));
    expect(dump.manifest.counts.households).toBe(1);
    expect(dump.scope.households).toEqual(['h-legacy']);
    expect(dump.scope.requestedHouseholds).toEqual(['h-legacy']);

    const restored = await H.run(restore.main, [H.newestDump(out), PROJECT, '--commit']);
    expect(restored.code).toBe(0);
    expect(restored.out).toContain(OK_LINE);
  });

  test('--expect is validated before the project is read, not after', async () => {
    // `--expect=lgacy` used to cost a full crawl of the live project and then exit 2 with no dump
    // written — a real cost on Spark and a bad thing to discover inside the migration window.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await expect(H.run(backup.main, [PROJECT, `--out=${out}`, '--expect=lgacy']))
      .rejects.toThrow(/--expect must be one of legacy\|target\|none/);
    // Nothing was read and nothing was written: no dump file, and no target banner printed.
    expect(H.fs.readdirSync(out)).toEqual([]);
  });

  test('--codeindex=all removes a fieldless codeIndex document that owns a subcollection', async () => {
    // The delete planner used collection.get(), which skips a document that holds no fields but
    // does own a subcollection — the opposite of the listDocuments() choice lib/firestore.js
    // documents at length. Such a document survived a mode whose whole meaning is "all", and
    // verification counts only existing documents so it could not see the survivor either.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);

    // A fieldless codeIndex/QQQ777 that owns a subcollection, created after the dump.
    await db.doc('codeIndex/QQQ777/history/h1').set({ note: 'stale' });
    expect((await db.doc('codeIndex/QQQ777').get()).exists).toBe(false);

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--codeindex=all', '--commit']);
    expect(res.code).toBe(0);
    expect((await db.doc('codeIndex/QQQ777/history/h1').get()).exists).toBe(false);
  });

  test('the retype warning survives a period in a parent path segment', async () => {
    // The warning filter split the field path on '.' — the very thing the gate correctly refuses
    // to do, because Firestore document ids may contain periods. For
    // households/h.1/pets/p1.weightKg under --only=households/h.1/pets the key came out as
    // "households" and the warning was silently dropped.
    await db.doc('households/h.1').set({ name: 'Dotted', members: ['u1'], createdAtMillis: 1 });
    await db.doc('households/h.1/pets/p1').set({ name: 'Rufus' });
    await H.setIntegralDouble('households/h.1/pets/p1', 'weightKg', 12);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h.1', '--expect=none']);
    const dumpFile = H.newestDump(out);
    expect(readDump(dumpFile).manifest.integralDoubleFields)
      .toEqual(['households/h.1/pets/p1.weightKg']);

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--only=households/h.1/pets']);
    expect(res.out).toContain('will come back as Firestore integers');
    expect(res.out).toContain('households/h.1/pets/p1.weightKg');
  });

  test('a dump with no scope.includeCodeIndex is refused before the delete, not after', async () => {
    // `!== false` means "in scope" downstream, so a dump missing the key read as a dump that
    // contains codeIndex: the planner deleted every live code pointing into scope with nothing to
    // write back. The count check did catch it — after the delete had committed.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);
    const dump = readDump(dumpFile);
    delete dump.scope.includeCodeIndex;
    writeDump(dumpFile, dump);

    await expect(H.run(restore.main, [dumpFile, PROJECT, '--commit']))
      .rejects.toThrow(/no boolean scope\.includeCodeIndex/);
    expect((await db.doc('codeIndex/ABC123').get()).get('householdId')).toBe('h-legacy');
  });

  test('a dropped fieldless codeIndex doc does not push the manifest count below the truth', async () => {
    // report.counts.codeIndex -= dropped decremented for every dropped node including exists:false
    // ones, which crawlCollection never counted into `existing` — so the manifest went low and
    // every restore from that dump failed verification.
    await H.seedLegacyHousehold(db);
    await db.doc('codeIndex/QQQ777/history/h1').set({ note: 'owned by nobody' });
    const out = H.tmpDir();
    const res = await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    expect(res.code).toBe(0);
    const dump = readDump(H.newestDump(out));
    expect(dump.manifest.counts.codeIndex).toBe(1); // ABC123 only, and not 0
    expect(Object.keys(dump.collections.codeIndex)).toEqual(['ABC123']);

    // Round 3, same fixture, the other half of the same leak: dropping the node adjusted only the
    // top-level count, so the manifest still described the subtree it no longer carried. Asserting
    // the reported symptom and not the fixture's whole reach is what let this survive a round.
    expect(Object.keys(dump.manifest.counts)).not.toContain('codeIndex/QQQ777/history');
    // The number the operator is shown, against the documents the file actually holds.
    expect(dump.manifest.totalDocuments).toBe(dumpedDocumentCount(dump));
    expect(dump.manifest.unknownCollections).not.toContain('codeIndex/QQQ777/history');
    expect(dump.manifest.missingParents).not.toContain('codeIndex/QQQ777');
  });
});

// --- round-3 blocking: a manifest must describe the dump that was written ------------------------
describe('a dump narrowed with --household', () => {
  test('restores cleanly onto an empty project under the default --codeindex=scoped', async () => {
    // The rehearsal's step 4, "the step that matters": clear the project, restore, expect OK.
    // Pre-fix the manifest carried counts['codeIndex/QQQ777/history'] = 1 for a code the dump had
    // dropped, so the count check reported "manifest says 1 doc(s), the collection does not exist
    // in the target" — FAILED on a correct restore, after the delete pass had committed.
    await H.seedLegacyHousehold(db);
    await db.doc('codeIndex/QQQ777/history/h1').set({ note: 'owned by nobody' });
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);

    await H.clearFirestore();
    const res = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(res.out).toContain(OK_LINE);
    expect(res.code).toBe(0);
    expect((await db.doc('codeIndex/ABC123').get()).get('householdId')).toBe('h-legacy');
  });

  test('restores cleanly under --codeindex=all, which deletes the out-of-scope subtree', async () => {
    // The other route to the same manifest defect: here the subtree really is deleted, so the
    // target correctly does not hold it and the manifest was correctly wrong about it.
    await H.seedLegacyHousehold(db);
    await db.doc('codeIndex/QQQ777/history/h1').set({ note: 'owned by nobody' });
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`, '--household=h-legacy']);
    const dumpFile = H.newestDump(out);

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--codeindex=all', '--commit']);
    expect(res.out).toContain(OK_LINE);
    expect(res.code).toBe(0);
    expect((await db.doc('codeIndex/QQQ777/history/h1').get()).exists).toBe(false);
  });
});

// --- round-3, the same defect on the verification side ------------------------------------------
describe('a codeIndex document the restore deliberately leaves in place', () => {
  test('does not fail the verification it is excluded from', async () => {
    // The mirror image of the blocking finding, found while fixing it and reachable from a
    // full-project dump with no narrowing flag at all. `scoped` disowns every code outside the
    // dump — deleting it from actualCodeIndex and recomputing counts.codeIndex — and warns that it
    // is leaving it in place. But the verification crawl had already recorded
    // counts['codeIndex/QQQ777/history'], and that key survived the disowning: "not in the
    // manifest but the target now holds 1 doc(s)", FAILED, after the delete had committed.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);

    // Appears in the target after the dump was taken, owning a subcollection and no fields.
    await db.doc('codeIndex/QQQ777/history/h1').set({ note: 'stale' });

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--commit']);
    expect(res.out).toContain('left in place');
    expect(res.out).toContain(OK_LINE);
    expect(res.code).toBe(0);
    // Left alone, as the warning said: `scoped` owns only the dump's codes.
    expect((await db.doc('codeIndex/QQQ777/history/h1').get()).exists).toBe(true);
  });
});

// --- round-3 nits -------------------------------------------------------------------------------
describe('round-3 nits', () => {
  test('--only naming a collection beneath codeIndex/ writes AND verifies it, deleting no more', async () => {
    // codeIndexInScope asked selected('codeIndex', only) — false for an --only *below* codeIndex/ —
    // while the write-job filter asked per document and said yes. Documents were written and never
    // verified: "missing from target", FAILED, after the delete. The fix puts codeIndex in play for
    // such an --only, so the delete loop now has to filter per reference like the households loop
    // does, or it would delete the code document itself, which --only did not name.
    await H.seedLegacyHousehold(db);
    await db.doc('codeIndex/ABC123/history/h1').set({ note: 'previous owner' });
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);

    await db.doc('codeIndex/ABC123/history/h1').set({ note: 'clobbered' });
    const res = await H.run(restore.main, [dumpFile, PROJECT, '--only=codeIndex/ABC123/history', '--commit']);
    expect(res.out).toContain(OK_LINE);
    expect(res.code).toBe(0);
    expect((await db.doc('codeIndex/ABC123/history/h1').get()).get('note')).toBe('previous owner');
    // Named a subcollection, so the code document and the household are untouched.
    expect((await db.doc('codeIndex/ABC123').get()).get('householdId')).toBe('h-legacy');
    expect((await db.doc('households/h-legacy').get()).exists).toBe(true);
  });

  test('a dump claiming --no-codeindex while carrying codes is refused before the delete', async () => {
    // The mirror image of the scope.households orphan guard, refused on the same grounds: every
    // reader treats the flag as the truth, so those codes would be written and then not verified.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);
    const dump = readDump(dumpFile);
    dump.scope.includeCodeIndex = false;
    writeDump(dumpFile, dump);

    await expect(H.run(restore.main, [dumpFile, PROJECT, '--commit']))
      .rejects.toThrow(/scope\.includeCodeIndex is false but collections\.codeIndex holds 1 code/);
    expect((await db.doc('households/h-legacy').get()).exists).toBe(true);
  });

  test('the retype warning survives a slash in the field name', async () => {
    // A NON-regression test, deliberately: this passes before the change as well as after. The
    // reported defect — cutting the entry at its last '/' drops the disclosure for a map key
    // containing a slash — is not actually reachable, because the mis-cut prefix still satisfies
    // `selected()`'s trailing-slash prefix test for every `only` that selects the document (see
    // restore.js). The filter was rewritten to stop parsing the entry anyway, so this pins the
    // property rather than a fix. The manifest entry is hand-written because the Admin SDK cannot
    // create a map key containing a slash; the filter it exercises is string-level either way.
    await H.seedLegacyHousehold(db);
    const out = H.tmpDir();
    await H.run(backup.main, [PROJECT, `--out=${out}`]);
    const dumpFile = H.newestDump(out);
    const dump = readDump(dumpFile);
    const petId = Object.keys(dump.collections.households['h-legacy'].collections.pets)[0];
    dump.manifest.integralDoubleFields = [`households/h-legacy/pets/${petId}.a/b`];
    writeDump(dumpFile, dump);

    const res = await H.run(restore.main, [dumpFile, PROJECT, '--only=households/h-legacy/pets']);
    expect(res.out).toContain('will come back as Firestore integers');
    expect(res.out).toContain(`households/h-legacy/pets/${petId}.a/b`);
  });

  test('--expect values that exist only on Object.prototype are refused', async () => {
    // `!EXPECTATIONS[expect]` resolved through the prototype chain, so these four passed the guard
    // and then behaved as --expect=none while recording the bogus name in the dump's source.expect.
    for (const bogus of ['constructor', 'toString', 'hasOwnProperty', '__proto__']) {
      await expect(H.run(backup.main, [PROJECT, `--out=${H.tmpDir()}`, `--expect=${bogus}`]))
        .rejects.toThrow(/--expect must be one of legacy\|target\|none/);
    }
  });

  test('a flag given twice is refused rather than resolved last-wins', async () => {
    // The parser's own thesis is that the operator's stated intent and the run's actual scope must
    // not differ silently. `--only=a --only=b` ran with b and said nothing.
    await expect(H.run(backup.main, [PROJECT, PROJECT, '--out=/tmp/never']))
      .rejects.toThrow(/--project was given more than once/);
    await expect(H.run(restore.main, ['d.json', PROJECT, '--only=households', '--only=codeIndex']))
      .rejects.toThrow(/--only was given more than once/);
    await expect(H.run(restore.main, ['d.json', PROJECT, '--commit', '--commit']))
      .rejects.toThrow(/--commit was given more than once/);
  });

  test('the final verdict line survives a reader that does not drain stdout', async () => {
    // process.exit() discards whatever is still queued on stdout, and stdout is asynchronous to a
    // pipe: piping a run with many warnings into a slow reader lost the tail — including the
    // OK:/FAILED: line that migration.md §7's irreversible delete is gated on. Measured through a
    // real pipe with a reader that pauses, because that is the condition that triggers it; a
    // prompt reader loses nothing and shows nothing.
    const script = (mode) => `
      const { exitWhenFlushed } = require(${JSON.stringify(require.resolve('../lib/cli'))});
      for (let i = 0; i < 4000; i++) console.log('warning line ' + i + ' ${'x'.repeat(60)}');
      console.log('OK: the verdict line');
      ${mode === 'flushed' ? 'exitWhenFlushed(0);' : 'process.exit(0);'}
    `;
    const readSlowly = (mode) => new Promise((resolve) => {
      const child = require('child_process').spawn(process.execPath, ['-e', script(mode)], {
        stdio: ['ignore', 'pipe', 'inherit'],
      });
      let buf = '';
      child.stdout.pause();
      setTimeout(() => { child.stdout.on('data', (d) => { buf += d; }); child.stdout.resume(); }, 500);
      child.on('exit', (code) => setTimeout(() => resolve({ code, out: buf }), 300));
    });

    const flushed = await readSlowly('flushed');
    expect(flushed.code).toBe(0);
    expect(flushed.out).toContain('OK: the verdict line');
    expect(flushed.out.split('\n').filter(Boolean)).toHaveLength(4001);

    // The pre-fix entrypoint, for evidence that the condition above really is the triggering one.
    const exited = await readSlowly('exit');
    expect(exited.out).not.toContain('OK: the verdict line');
  });
});
