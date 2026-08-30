# Product Spec — Pet Health Diary (v1)

**Status:** draft for discussion · **Last updated:** 2026-08-30
**Companion docs:** `architecture.md` (Flutter/tech stack), `security-privacy.md` (threat model, data handling, admin/access/ownership), `migration.md` + `flutter-migration.md` (how the next release gets built).

> **Target, not current — and "v1" ≠ "the next release".** §4 describes the intended
> *product*. The shipped Kotlin app already does multi-pet / health notes / vets, but has no
> admin/member roles and no iOS. **§4.0 draws the line** between what the next release
> actually contains (shipped-app parity + roles + join-code relocation + an export log) and
> what §4 describes but defers to a later release. `architecture.md §0` has the full gap list.

## 1. One-liner

A cross-platform app for households to log a pet's seizures and other health events, keep
vet contacts organized, and hand a vet a clean report — built so the person filling it out
mid-seizure never has to think.

## 2. Who this is for

| Persona | Role | Needs |
|---|---|---|
| Admin | Manages the household — pets, vets, medications, members, exports, the join code; a household can have more than one admin | Fast logging, full history, exports, full control over the household's setup and who's in |
| Member (partner, family member who co-manages) | Should be an **admin** — see above | — |
| Member / petsitter / occasional logger | Non-admin: reads the whole household and logs entries (and edits/deletes their own), but can't change pets/vets/medications, household settings, the roster, the join code, or export | Easy to bring in for a stretch, easy to take back out; can log without being able to break the household's setup |
| Vet (indirect) | Never opens the app | Receives a report (PDF/CSV, or later a shared link) |

**A non-admin member is log-and-view only.** They see everything (no data is hidden) and can
log observations, including fixing or deleting ones they logged — but every management
action (pet profiles, medications, vets, renaming, member roster, join code, export) is
admin-only. A partner or family member who co-manages the pets should be made an admin; the
non-admin role is for someone who should only ever add entries. Full capability breakdown in
`security-privacy.md` §4.1.

## 3. Core entities

- **Household** — the shared record a group of people belong to. Everyone in it sees every pet,
  vet, and entry in that household — access is never scoped to a subset of pets. One household
  is a family's whole set of pets, not one pet's care circle: e.g. a couple's household holds
  every pet they own, and anyone added to it (including a petsitter only there for one animal)
  sees all of them. That's intentional — someone who's around the house may notice something
  worth a quick health note on a pet they weren't technically there for, and there's no benefit
  to hiding that pet from them. *Seeing* everything is universal; *changing* the household's
  setup (pets, vets, medications, members) is admin-only (see §2). Has one or more admins.
  Access and ownership mechanics are specified in `security-privacy.md`.
- **Pet** — belongs to a household: name, species (dog/cat/other), breed, weight, birth date,
  and its own list of maintenance medications. A household can have any number of pets.
  Diagnosis date and a pet photo are *later* (§4.0).
- **Vet** — a shared, reusable contact per household (not per pet): name, phone, and one
  free-text address/notes field (the shipped shape). A richer contact (email, structured
  address) is a *later* model change.
- **Pet↔Vet link** — many-to-many, each link labeled with a role (General / Emergency / Neuro
  specialist / Other). One clinic can be "General" for one pet and "Emergency" for another.
- **Seizure entry** — the detailed, high-value entry type: timestamp, duration, seizure type,
  symptom checklist, pre-seizure signs, triggers, recovery time (minutes) + recovery notes,
  rescue med given + details, free notes, who logged it. (No separate "recovery behavior"
  field — it's folded into recovery notes.)
- **Health note** — a deliberately lightweight entry type for anything else worth mentioning to
  the vet: free-text description, when it started, notes. Stays unstructured on purpose (see
  non-goals, §5).
- **Maintenance medication** — belongs to a pet, not an entry: name/dose/frequency/notes. Distinct
  from a rescue med given during a seizure, which is recorded on that seizure entry instead.
- **Member profile** — display name, sign-in method, and role (admin / non-admin) per
  household member, for showing "logged by X", the member list, and gating who can manage
  the household. The "who logged it" on an entry also decides who may edit or delete it: its
  logger, or any admin.

## 4. Feature scope

### 4.0 What the next release contains

The next release = **everything the shipped Kotlin app already does**, re-platformed to
Flutter for iOS + Android (`flutter-migration.md`), on the migrated Firestore shape
(`migration.md`), **plus** exactly what `migration.md` adds:

- **Admin / member roles** — the log-and-view vs. manage split (§2, `security-privacy.md §4.1`).
- **Join-code relocation** to an admin-only doc (`security-privacy.md §8 item 7`).
- **An export log** — `households/{id}/exportLog` (`architecture.md §7`).

**Deferred to a later release** (described in §4 below as part of the target product, but not
built next):

| Item | Where it's noted |
|---|---|
| Apple sign-in | with App Store distribution — `security-privacy.md §3.1` |
| In-progress seizure timer, voice dictation | design-brief "new" items |
| Pet `archived` (archive instead of hard-delete), `diagnosisDate` | `architecture.md §3` |
| History filters (pet / type / date / logger), month grouping | design-brief "new" items |
| Compare an entry to similar past ones | design-brief "new" item |
| Frequency-trend chart (dashboard and in the PDF), combined all-pets dashboard view | design-brief "new" items; needs a charting package (`flutter-migration.md §4`) |
| Join-code rotation | `security-privacy.md §4.2` — genuinely new feature work |
| Photo/video attachments; household notifications | §5 (backlogged) |
| Web dashboard | `architecture.md §2/§10` |
| A written privacy policy, App Check, cloud backup / PITR | `security-privacy.md §10` |

§4 below is grouped by usage flow: setup → manage pets/vets/household → log → review → export.
Items marked *(later)* are in the table above.

**Get set up**
- Sign in with Google, or continue without an account (for someone who'd rather not attach
  one, e.g. a petsitter). *Apple sign-in is later (§4.0).*
- Create a household (pet name + species + your name), becoming its first admin, or get added
  to an existing one by an admin. The force-link-before-invite gate (`security-privacy.md
  §3.2`) is *later* — it only matters once anonymous sign-in is in use.

**Manage pets** *(editing is admin-only; everyone can view)*
- Add and switch between multiple pets per household; a pet switcher; a default pet. Deleting
  a pet hard-deletes it; *archive-instead-of-delete is later (§4.0)*.
- Edit a pet's profile: name, species, breed, weight, birth date. *Diagnosis date and photo
  are later (§4.0).*
- See a pet's linked vets from its profile.
- Maintenance medications: add/edit/remove. No in-app reminders or dose-tracking — a "set an
  alarm" action hands off to the phone's own alarm/reminder app, and marking a dose done stays
  in that same app rather than asking the person to also come log it here.

**Manage vets** *(admin-only; everyone can view)*
- One shared vet directory per household, not one list per pet.
- Link a vet to a pet with a role label; a pet can have several vets, a vet can serve several
  pets; edit/remove links.

**Manage household** *(admin-only)*
- Admins: add a new member (mechanism in `security-privacy.md`), view the member list, remove
  a member, rename the household, view/share the join code. *Rotating the code is later
  (§4.0).*
- Non-admin members: view the member list only. They cannot add or remove anyone, rename
  anything, or see the join code.

**Log an entry** — the core loop, and the one place speed matters most
- Pick entry type (Seizure / Health note), then which pet (defaults to last-viewed). This picker
  must never add a tap or a delay to logging a seizure — that is a hard constraint on every
  design decision in this flow, not a preference.
- Seizure form: date/time, duration, seizure type, a symptom checklist, pre-seizure signs,
  possible triggers, recovery time, recovery notes, rescue medication given (with details),
  free notes, and who logged it. *A one-tap in-progress timer and voice dictation are later
  (§4.0).* The triggers/notes fields are where a missed or late maintenance dose gets captured
  if it's relevant to this seizure — there's no separate dose-tracking feature (see §5).
- Health note form: free text, start time, notes. Stays minimal — see §5.
- Logging works fully offline; entries sync automatically once the device is back online.
  **This is a hard requirement** — a rules-rejected or biometric-failed save must never block
  the logging path (`architecture.md §4`, `security-privacy.md §6`).

**Review history**
- Dashboard: days since last seizure, total count, recent entries. *A frequency-trend chart
  and a combined all-pets view are later (§4.0).*
- Full history: every entry, most recent first, grouped by month. *Filters (pet / type / date
  / logger) are later (§4.0).*
- View, edit, or delete an entry with confirmation (a non-admin can edit/delete only entries
  they logged themselves; an admin, any entry). *"Compare to similar past entries" is later
  (§4.0).*

**Share with the vet** *(exporting is admin-only; anyone can hand the vet the phone)*
- Two ways to share, both initiated entirely from your own phone, neither requiring the vet to
  have an account or any access to the app: hand the vet your phone to walk through the
  dashboard, charts, or timeline directly, or export a PDF (or CSV) and send it however's
  convenient — email, print, drop it into a vet's own upload portal — using the phone's normal
  share sheet. The app never talks to a vet's system directly, and there's no vet-facing account
  or portal to build or secure.
- Export by time range (e.g. last 30/90 days, all time); choose whether health notes are
  included; choose which pet(s); see a log of past exports. *Including a trend chart in the
  export is later (§4.0).*

## 5. Non-goals for v1

- **The health note stays unstructured.** No severity scale, category picker, or vitals fields
  until there's real usage data on what people actually want to record there — it ships as free
  text, and only that.
- **Nothing about multi-pet support or the entry-type picker may slow down logging a seizure.**
  If any design decision in onboarding, the entry-type picker, or navigation adds friction to
  that flow, the decision is wrong for v1, full stop.
- **No clinical claims.** This app records owner-observed events and hands them to a
  professional; it does not diagnose, score severity, or suggest treatment. That framing matters
  both ethically and for how much regulatory weight the app has to carry — keep it in mind when
  writing UI copy for both entry types.
- **No granular permissions.** The role split is exactly two levels — admin and non-admin —
  and applies household-wide. No per-pet access, no per-field editing rights, no "can edit
  vets but not pets" middle tier. If the binary turns out too blunt in real use, that's a
  post-v1 conversation (`security-privacy.md` §10), not a v1 feature.
- **No photo/video attachments in the next release.** Health-note photos, pet photos, and
  seizure video are all backlogged. When they're built, one constraint holds: media must not
  be stored in the cloud the way the rest of the household's data is. `architecture.md` §8
  settled the *approach* (local-only, on-device, shared device-to-device via the OS share
  sheet — no cloud copy, no encryption to design); what's left for that later work is the
  receipt UX (matching a shared file back to its entry) and retention. The shipped app's
  half-built photo capture has been removed.
- **No household notifications in the next release.** "Saving notifies the rest of the
  household" is backlogged — it needs a Cloud Function (push can't go device-to-device), which
  the backend deliberately avoids (`architecture.md` §5, §9). Until then a partner sees a new
  entry the next time they open the app.
- **No in-app medication reminders or dose-given tracking.** We're not taking on responsibility
  for an alarm firing reliably (background execution, OS battery/notification restrictions,
  etc.), and marking a dose done belongs in whatever alarm/reminder app the person already used
  to set the reminder — not a second place to remember to log it. Adherence still gets captured
  where it actually matters: as a note on the seizure entry itself, if a missed or late dose is
  relevant to that seizure (see §4).
- **No vet-facing account, portal, or direct system access, ever.** Sharing is always
  phone-in-hand or an export the owner sends themselves — the app never gives a vet or their
  staff their own login or a direct line into the data. This is a deliberate line, not a
  v1-only limitation: it avoids taking on a whole second class of user, their access control,
  and their credentials.

## 6. Open questions

Access, admin, and ownership mechanics (how someone joins, how admin status is granted or
transferred, account durability) belong in `security-privacy.md` and are intentionally not
listed here — this doc only tracks product-scope questions.

1. **Structured health-note fields.** Whether severity / category / vitals fields are worth
   adding to the health note is deferred until there's real usage data — see §5. Not
   actionable now.

Backlogged features (not open questions — decided to defer, design captured elsewhere):

- **Photo/video attachments** — approach settled in `architecture.md` §8 (local-only). Open
  sub-parts for when it's built: the receipt UX and retention (is media deleted with its
  entry, does it need offline-cache behavior).
- **Household notifications** — `architecture.md` §5. Carries a cost decision (a Cloud
  Function means the Blaze plan).
