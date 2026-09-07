// Shape guard for firestore.indexes.json and its firebase.json wiring.
//
// Unlike rules.test.js this does not touch the emulator: the Firestore emulator does not read
// firestore.indexes.json at all (it is handed only `rules`), so it can neither enforce a
// composite index nor apply an exemption. A green emulator run therefore proves nothing about
// this file — these assertions are the only automated check that it stays well-formed and that
// `firebase deploy --only firestore` still covers indexes as well as rules.
//
// Live verification (that the exemption is actually applied) has to happen against the real
// project — see README, "Deploying Firestore rules and indexes".

const fs = require("fs");
const path = require("path");

const repoRoot = path.resolve(__dirname, "..");
const readJson = (rel) => JSON.parse(fs.readFileSync(path.join(repoRoot, rel), "utf8"));

describe("firebase.json wiring", () => {
  const firebaseJson = readJson("firebase.json");

  test("firestore config points at both the rules and the indexes file", () => {
    expect(firebaseJson.firestore.rules).toBe("firestore.rules");
    expect(firebaseJson.firestore.indexes).toBe("firestore.indexes.json");
  });

  test("the referenced indexes file exists and is strict JSON", () => {
    expect(() => readJson(firebaseJson.firestore.indexes)).not.toThrow();
  });
});

describe("firestore.indexes.json", () => {
  const spec = readJson("firestore.indexes.json");

  test("declares no composite indexes", () => {
    // The read pattern is a single orderBy(occurredAt, desc) + client-side filtering
    // (migration.md §4 area 3). A where() alongside the orderBy would need a composite index
    // here AND a client change; neither belongs in this file by accident.
    expect(spec.indexes).toEqual([]);
  });

  test("exempts observations.details from all single-field index modes", () => {
    const override = spec.fieldOverrides.find(
      (f) => f.collectionGroup === "observations" && f.fieldPath === "details"
    );
    expect(override).toBeDefined();
    // Empty `indexes` == ascending, descending and array-contains all disabled. Subfields
    // inherit a parent map's exemption, so this covers every details.* scalar and the
    // details.symptoms array without needing per-subfield overrides.
    expect(override.indexes).toEqual([]);
    // `collectionGroup` is the only scope Firestore offers for single-field config; there is no
    // way to scope it to the households/{id}/observations parent path, and nothing else in the
    // schema uses the collection id `observations`.
    expect(Object.keys(override).sort()).toEqual(["collectionGroup", "fieldPath", "indexes"]);
  });

  test("does not exempt any envelope field that is sorted or filtered on", () => {
    // occurredAt is the sole orderBy field; petId/type/loggedByUid are rule- or
    // client-filter-load-bearing (architecture.md §3). Exempting any of them would break the
    // timeline query or silently drop a query path.
    const exempted = spec.fieldOverrides
      .filter((f) => f.collectionGroup === "observations")
      .map((f) => f.fieldPath);
    for (const envelopeField of ["occurredAt", "petId", "type", "loggedByUid", "createdAt", "updatedAt"]) {
      expect(exempted).not.toContain(envelopeField);
    }
  });
});
