const fs = require("fs");
const path = require("path");
const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require("@firebase/rules-unit-testing");
const { doc, getDoc, getDocs, collection, setDoc, updateDoc, deleteDoc } = require("firebase/firestore");

let testEnv;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-seizuretracker-rules-test",
    firestore: {
      rules: fs.readFileSync(path.resolve(__dirname, "..", "firestore.rules"), "utf8"),
      host: "localhost",
      port: 8080,
    },
  });
});

afterAll(async () => {
  await testEnv.cleanup();
});

afterEach(async () => {
  await testEnv.clearFirestore();
});

/** Writes directly to the emulator, bypassing rules, to set up fixture data. */
async function seed(fn) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await fn(context.firestore());
  });
}

describe("codeIndex/{code}", () => {
  test("a signed-in device can get a code by exact id", async () => {
    await seed((db) => setDoc(doc(db, "codeIndex/ABC123"), { householdId: "house1" }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(getDoc(doc(db, "codeIndex/ABC123")));
  });

  test("an unauthenticated device cannot get a code", async () => {
    await seed((db) => setDoc(doc(db, "codeIndex/ABC123"), { householdId: "house1" }));
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(getDoc(doc(db, "codeIndex/ABC123")));
  });

  test("listing codeIndex is always denied, even signed in", async () => {
    await seed((db) => setDoc(doc(db, "codeIndex/ABC123"), { householdId: "house1" }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(getDocs(collection(db, "codeIndex")));
  });

  test("a signed-in device can create a codeIndex entry", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(setDoc(doc(db, "codeIndex/NEWCODE"), { householdId: "house1" }));
  });

  test("an unauthenticated device cannot create a codeIndex entry", async () => {
    const db = testEnv.unauthenticatedContext().firestore();
    await assertFails(setDoc(doc(db, "codeIndex/NEWCODE"), { householdId: "house1" }));
  });

  test("codeIndex entries can never be updated or deleted, even by a signed-in device", async () => {
    await seed((db) => setDoc(doc(db, "codeIndex/ABC123"), { householdId: "house1" }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(updateDoc(doc(db, "codeIndex/ABC123"), { householdId: "house2" }));
    await assertFails(deleteDoc(doc(db, "codeIndex/ABC123")));
  });
});

describe("households/{householdId}", () => {
  test("a non-member cannot read a household", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(getDoc(doc(db, "households/house1")));
  });

  test("a member can read a household", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(getDoc(doc(db, "households/house1")));
  });

  test("creating a household requires the creator's own uid in members", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
  });

  test("creating a household without yourself in members is rejected", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["bob"] }));
  });

  test("an existing member can update arbitrary fields", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(updateDoc(doc(db, "households/house1"), { dogName: "Max" }));
  });

  test("a non-member cannot update arbitrary fields", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(updateDoc(doc(db, "households/house1"), { dogName: "Hacked" }));
  });

  test("a non-member can join by adding only themselves to members", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(updateDoc(doc(db, "households/house1"), { members: ["alice", "bob"] }));
  });

  test("a non-member cannot add themselves plus someone else in the same write", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(updateDoc(doc(db, "households/house1"), { members: ["alice", "bob", "carol"] }));
  });

  test("a non-member cannot add someone else instead of themselves", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertFails(updateDoc(doc(db, "households/house1"), { members: ["alice", "carol"] }));
  });

  test("a non-member cannot remove an existing member", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice", "bob"] }));
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(updateDoc(doc(db, "households/house1"), { members: ["alice"] }));
  });

  test("households can never be deleted, even by a member", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(deleteDoc(doc(db, "households/house1")));
  });
});

describe("households/{householdId}/seizures/{seizureId}", () => {
  test("a member can read and write seizures", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(
      setDoc(doc(db, "households/house1/seizures/s1"), { timestampMillis: 1 })
    );
    await assertSucceeds(getDoc(doc(db, "households/house1/seizures/s1")));
  });

  test("a non-member cannot read or write seizures, even knowing the householdId", async () => {
    await seed((db) => {
      return Promise.all([
        setDoc(doc(db, "households/house1"), { dogName: "Rex", members: ["alice"] }),
        setDoc(doc(db, "households/house1/seizures/s1"), { timestampMillis: 1 }),
      ]);
    });
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(getDoc(doc(db, "households/house1/seizures/s1")));
    await assertFails(setDoc(doc(db, "households/house1/seizures/s2"), { timestampMillis: 2 }));
  });
});

// pets, vets, petVetLinks, and healthNotes all share the exact same membership-gate shape as
// seizures above, so each gets one member/non-member read+write pair rather than re-testing
// every household-rule edge case per collection.
describe("households/{householdId}/pets/{petId}", () => {
  test("a member can read and write pets", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { members: ["alice"] }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(setDoc(doc(db, "households/house1/pets/p1"), { name: "Bear" }));
    await assertSucceeds(getDoc(doc(db, "households/house1/pets/p1")));
  });

  test("a non-member cannot read or write pets", async () => {
    await seed((db) =>
      Promise.all([
        setDoc(doc(db, "households/house1"), { members: ["alice"] }),
        setDoc(doc(db, "households/house1/pets/p1"), { name: "Bear" }),
      ])
    );
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(getDoc(doc(db, "households/house1/pets/p1")));
    await assertFails(setDoc(doc(db, "households/house1/pets/p2"), { name: "Milo" }));
  });
});

describe("households/{householdId}/vets/{vetId}", () => {
  test("a member can read and write vets", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { members: ["alice"] }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(setDoc(doc(db, "households/house1/vets/v1"), { name: "Riverside" }));
    await assertSucceeds(getDoc(doc(db, "households/house1/vets/v1")));
  });

  test("a non-member cannot read or write vets", async () => {
    await seed((db) =>
      Promise.all([
        setDoc(doc(db, "households/house1"), { members: ["alice"] }),
        setDoc(doc(db, "households/house1/vets/v1"), { name: "Riverside" }),
      ])
    );
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(getDoc(doc(db, "households/house1/vets/v1")));
    await assertFails(setDoc(doc(db, "households/house1/vets/v2"), { name: "Oakview" }));
  });
});

describe("households/{householdId}/petVetLinks/{linkId}", () => {
  test("a member can read and write pet-vet links", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { members: ["alice"] }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(
      setDoc(doc(db, "households/house1/petVetLinks/l1"), { petId: "p1", vetId: "v1", role: "General" })
    );
    await assertSucceeds(getDoc(doc(db, "households/house1/petVetLinks/l1")));
  });

  test("a non-member cannot read or write pet-vet links", async () => {
    await seed((db) =>
      Promise.all([
        setDoc(doc(db, "households/house1"), { members: ["alice"] }),
        setDoc(doc(db, "households/house1/petVetLinks/l1"), { petId: "p1", vetId: "v1", role: "General" }),
      ])
    );
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(getDoc(doc(db, "households/house1/petVetLinks/l1")));
    await assertFails(
      setDoc(doc(db, "households/house1/petVetLinks/l2"), { petId: "p1", vetId: "v2", role: "Emergency" })
    );
  });
});

describe("households/{householdId}/healthNotes/{noteId}", () => {
  test("a member can read and write health notes", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { members: ["alice"] }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(
      setDoc(doc(db, "households/house1/healthNotes/n1"), { petId: "p1", description: "Limping" })
    );
    await assertSucceeds(getDoc(doc(db, "households/house1/healthNotes/n1")));
  });

  test("a non-member cannot read or write health notes", async () => {
    await seed((db) =>
      Promise.all([
        setDoc(doc(db, "households/house1"), { members: ["alice"] }),
        setDoc(doc(db, "households/house1/healthNotes/n1"), { petId: "p1", description: "Limping" }),
      ])
    );
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(getDoc(doc(db, "households/house1/healthNotes/n1")));
    await assertFails(setDoc(doc(db, "households/house1/healthNotes/n2"), { petId: "p1", description: "x" }));
  });
});

describe("households/{householdId}/members/{memberUid}", () => {
  test("a member can read the member list", async () => {
    await seed((db) =>
      Promise.all([
        setDoc(doc(db, "households/house1"), { members: ["alice", "bob"] }),
        setDoc(doc(db, "households/house1/members/alice"), { displayName: "Alice" }),
      ])
    );
    const db = testEnv.authenticatedContext("bob").firestore();
    await assertSucceeds(getDoc(doc(db, "households/house1/members/alice")));
  });

  test("a non-member cannot read the member list", async () => {
    await seed((db) =>
      Promise.all([
        setDoc(doc(db, "households/house1"), { members: ["alice"] }),
        setDoc(doc(db, "households/house1/members/alice"), { displayName: "Alice" }),
      ])
    );
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(getDoc(doc(db, "households/house1/members/alice")));
  });

  test("a member can create and update their own profile doc", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { members: ["alice"] }));
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(setDoc(doc(db, "households/house1/members/alice"), { displayName: "Alice" }));
    await assertSucceeds(updateDoc(doc(db, "households/house1/members/alice"), { displayName: "Alicia" }));
  });

  test("creating your own profile doc doesn't require a fresh read of current membership " +
    "(avoids a race with the join/create write that lands immediately before it)", async () => {
    await seed((db) => setDoc(doc(db, "households/house1"), { members: ["alice"] }));
    const db = testEnv.authenticatedContext("bob").firestore();
    // bob isn't a member of house1, but can still write his own profile doc under it — the
    // read rule keeps it hidden from everyone but real members either way.
    await assertSucceeds(setDoc(doc(db, "households/house1/members/bob"), { displayName: "Bob" }));
  });

  test("a member cannot create or update another member's profile doc", async () => {
    await seed((db) =>
      Promise.all([
        setDoc(doc(db, "households/house1"), { members: ["alice", "bob"] }),
        setDoc(doc(db, "households/house1/members/bob"), { displayName: "Bob" }),
      ])
    );
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(setDoc(doc(db, "households/house1/members/carol"), { displayName: "Carol" }));
    await assertFails(updateDoc(doc(db, "households/house1/members/bob"), { displayName: "Hacked" }));
  });

  test("any current member can delete another member's profile doc", async () => {
    await seed((db) =>
      Promise.all([
        setDoc(doc(db, "households/house1"), { members: ["alice", "bob"] }),
        setDoc(doc(db, "households/house1/members/bob"), { displayName: "Bob" }),
      ])
    );
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(deleteDoc(doc(db, "households/house1/members/bob")));
  });

  test("a non-member cannot delete a member's profile doc", async () => {
    await seed((db) =>
      Promise.all([
        setDoc(doc(db, "households/house1"), { members: ["alice"] }),
        setDoc(doc(db, "households/house1/members/alice"), { displayName: "Alice" }),
      ])
    );
    const db = testEnv.authenticatedContext("mallory").firestore();
    await assertFails(deleteDoc(doc(db, "households/house1/members/alice")));
  });
});
