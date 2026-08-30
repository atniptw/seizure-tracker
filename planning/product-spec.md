# Product Spec — Pet Health Diary (v1)

**Status:** draft for discussion · **Last updated:** 2026-08-29
**Companion docs:** `architecture.md` (Flutter/tech stack), `security-privacy.md` (threat model, data handling, and how admin/access/ownership actually works).

> **Target, not current.** This spec describes the intended product. The shipped Kotlin app
> already does multi-pet / health notes / vets, but has **no admin/member roles** yet (every
> member can do everything) and no Apple sign-in. See `architecture.md §0` for the full gap list.

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
  and its own list of maintenance medications. A household can have any number of pets. (A pet
  photo is part of the backlogged attachments work — see §5.)
- **Vet** — a shared, reusable contact per household (not per pet): name, phone, notes.
- **Pet↔Vet link** — many-to-many, each link labeled with a role (General / Emergency / Neuro
  specialist / Other). One clinic can be "General" for one pet and "Emergency" for another.
- **Seizure entry** — the detailed, high-value entry type: timestamp, duration, seizure type,
  symptom checklist, pre-seizure signs, triggers, recovery time/behavior/notes, rescue med
  given + details, free notes, who logged it.
- **Health note** — a deliberately lightweight entry type for anything else worth mentioning to
  the vet: free-text description, when it started, notes. Stays unstructured on purpose (see
  non-goals, §5).
- **Maintenance medication** — belongs to a pet, not an entry: name/dose/frequency/notes. Distinct
  from a rescue med given during a seizure, which is recorded on that seizure entry instead.
- **Member profile** — display name, sign-in method, and role (admin / non-admin) per
  household member, for showing "logged by X", the member list, and gating who can manage
  the household. The "who logged it" on an entry also decides who may edit or delete it: its
  logger, or any admin.

## 4. v1 feature scope

Grouped by usage flow: setup → manage pets/vets/household → log → review → export.

**Get set up**
- Sign in with Google, Apple, or continue without an account (for someone who'd rather not
  attach one, e.g. a petsitter).
- Create a household (pet name + species + your name), becoming its first admin, or get added
  to an existing one by an admin. You can create and use a household solo without an account,
  but must add Google/Apple before inviting anyone (`security-privacy.md` §3.2). The
  mechanism for adding someone is specified in `security-privacy.md`.

**Manage pets** *(editing is admin-only; everyone can view)*
- Add, switch between, and archive multiple pets per household; a pet switcher; a default pet.
- Edit a pet's profile: name, species, breed, weight, birth date, diagnosis date, photo.
- See a pet's linked vets from its profile.
- Maintenance medications: add/edit/remove. No in-app reminders or dose-tracking — a "set an
  alarm" action hands off to the phone's own alarm/reminder app, and marking a dose done stays
  in that same app rather than asking the person to also come log it here.

**Manage vets** *(admin-only; everyone can view)*
- One shared vet directory per household, not one list per pet.
- Link a vet to a pet with a role label; a pet can have several vets, a vet can serve several
  pets; edit/remove links.

**Manage household** *(admin-only)*
- Admins: add a new member (mechanism specified in `security-privacy.md`), view the member list,
  remove a member, rename the household, view/rotate/share the join code.
- Non-admin members: view the member list only. They cannot add or remove anyone, rename
  anything, or see the join code.

**Log an entry** — the core loop, and the one place speed matters most
- Pick entry type (Seizure / Health note), then which pet (defaults to last-viewed). This picker
  must never add a tap or a delay to logging a seizure — that is a hard constraint on every
  design decision in this flow, not a preference.
- Seizure form: date/time, duration, seizure type, a symptom checklist, pre-seizure signs,
  possible triggers, recovery time and behavior, rescue medication given (with details), free
  notes, and who logged it — plus a one-tap in-progress timer and voice dictation for notes, so
  it stays fast to fill out one-handed, under stress. The triggers/notes fields are where a
  missed or late maintenance dose gets captured if it's relevant to this seizure — there's no
  separate dose-tracking feature (see §5); this is the one moment adherence actually matters
  enough to write down.
- Health note form: free text, start time, notes. Stays minimal — see §5.
- Logging works fully offline; entries sync automatically once the device is back online.

**Review history**
- Dashboard: days since last seizure, total count, recent entries, a frequency trend, and a
  combined all-pets view for multi-pet households.
- Full history: filter by pet/type/date/logger, grouped by month.
- View, edit, or delete an entry with confirmation (a non-admin can edit/delete only entries
  they logged themselves; an admin, any entry); compare an entry to similar past ones.

**Share with the vet** *(exporting is admin-only; anyone can hand the vet the phone)*
- Two ways to share, both initiated entirely from your own phone, neither requiring the vet to
  have an account or any access to the app: hand the vet your phone to walk through the
  dashboard, charts, or timeline directly, or export a PDF (or CSV) and send it however's
  convenient — email, print, drop it into a vet's own upload portal — using the phone's normal
  share sheet. The app never talks to a vet's system directly, and there's no vet-facing account
  or portal to build or secure.
- Export by time range (e.g. last 30/90 days, all time); choose whether health notes are
  included; choose which pet(s); include a trend chart; see a log of past exports.

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
