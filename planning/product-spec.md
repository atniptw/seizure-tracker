# Product Spec — Pet Health Diary (v1)

**Status:** draft for discussion · **Last updated:** 2026-08-23
**Companion docs:** `architecture.md` (Flutter/tech stack), `security-privacy.md` (threat model, data handling, and how admin/access/ownership actually works).

## 1. One-liner

A cross-platform app for households to log a pet's seizures and other health events, keep
vet contacts organized, and hand a vet a clean report — built so the person filling it out
mid-seizure never has to think.

## 2. Who this is for

| Persona | Role | Needs |
|---|---|---|
| Admin | Can add or remove members; a household can have more than one | Fast logging, full history, exports, control over who's in |
| Member (partner, family member) | Full member, logs from their own device | Same logging/data access as an admin — no hierarchy on the data itself |
| Petsitter / occasional logger | Temporary member, sees the whole household even if only there for one pet | Easy to bring in for a stretch, easy to take back out |
| Vet (indirect) | Never opens the app | Receives a report (PDF/CSV, or later a shared link) |

## 3. Core entities

- **Household** — the shared record a group of people belong to. Everyone in it sees every pet,
  vet, and entry in that household — access is never scoped to a subset of pets. One household
  is a family's whole set of pets, not one pet's care circle: e.g. a couple's household holds
  every pet they own, and anyone added to it (including a petsitter only there for one animal)
  sees all of them. That's intentional — someone who's around the house may notice something
  worth a quick health note on a pet they weren't technically there for, and there's no benefit
  to hiding that pet from them. Has one or more admins (see §2). Access and ownership mechanics
  are specified in `security-privacy.md`.
- **Pet** — belongs to a household: name, species (dog/cat/other), breed, weight, birth date,
  photo, and its own list of maintenance medications. A household can have any number of pets.
- **Vet** — a shared, reusable contact per household (not per pet): name, phone, notes.
- **Pet↔Vet link** — many-to-many, each link labeled with a role (General / Emergency / Neuro
  specialist / Other). One clinic can be "General" for one pet and "Emergency" for another.
- **Seizure entry** — the detailed, high-value entry type: timestamp, duration, seizure type,
  symptom checklist, pre-seizure signs, triggers, recovery time/behavior/notes, rescue med
  given + details, free notes, who logged it.
- **Health note** — a deliberately lightweight entry type for anything else worth mentioning to
  the vet: free-text description, when it started, notes, optional photo, a "flag for vet"
  toggle. Stays unstructured on purpose (see non-goals, §5).
- **Maintenance medication** — belongs to a pet, not an entry: name/dose/frequency/notes. Distinct
  from a rescue med given during a seizure, which is recorded on that seizure entry instead.
- **Member profile** — display name + sign-in method per household member, for showing "logged
  by X" and a member list.

## 4. v1 feature scope

Grouped by usage flow: setup → manage pets/vets/household → log → review → export.

**Get set up**
- Sign in with Google, Apple, or continue without an account (for someone who'd rather not
  attach one, e.g. a petsitter).
- Create a household (pet name + species + your name), becoming its first admin, or get added
  to an existing one by an admin. The mechanism for adding someone is specified in
  `security-privacy.md`.

**Manage pets**
- Add, switch between, and archive multiple pets per household; a pet switcher; a default pet.
- Edit a pet's profile: name, species, breed, weight, birth date, diagnosis date, photo.
- See a pet's linked vets from its profile.
- Maintenance medications: add/edit/remove. No in-app reminders or dose-tracking — a "set an
  alarm" action hands off to the phone's own alarm/reminder app, and marking a dose done stays
  in that same app rather than asking the person to also come log it here.

**Manage vets**
- One shared vet directory per household, not one list per pet.
- Link a vet to a pet with a role label; a pet can have several vets, a vet can serve several
  pets; edit/remove links.

**Manage household**
- Admins: add a new member (mechanism specified in `security-privacy.md`), view the member list,
  remove a member.
- Non-admin members: view the member list; cannot add or remove anyone.

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
- Health note form: free text, start time, notes, photo, a flag-for-vet toggle. Stays minimal —
  see §5.
- Saving notifies the rest of the household, so a partner knows an entry just happened without
  having the app open.
- Logging works fully offline; entries sync automatically once the device is back online.

**Review history**
- Dashboard: days since last seizure, total count, recent entries, a frequency trend, a
  running "mention at next vet visit" list, and a combined all-pets view for multi-pet
  households.
- Full history: filter by pet/type/date/logger, grouped by month.
- View, edit, or delete an entry with confirmation; compare an entry to similar past ones.

**Share with the vet**
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
  text + flag, and only that.
- **Nothing about multi-pet support or the entry-type picker may slow down logging a seizure.**
  If any design decision in onboarding, the entry-type picker, or navigation adds friction to
  that flow, the decision is wrong for v1, full stop.
- **No clinical claims.** This app records owner-observed events and hands them to a
  professional; it does not diagnose, score severity, or suggest treatment. That framing matters
  both ethically and for how much regulatory weight the app has to carry — keep it in mind when
  writing UI copy for both entry types.
- **The photo/video storage design is not decided in this document.** V1 wants photo/video
  attachments (health notes, pet photos), with one constraint set here because it shapes the
  feature itself: media should not be stored in the cloud the way the rest of the household's
  data is. How to still make an attachment available to every household member without a cloud
  copy (on-device only with direct device-to-device transfer, encrypted-at-rest cloud storage
  the provider can't read, something else) is a real open problem, not a settled decision — see
  §6. It's called out here because it may mean attachments behave differently from every other
  entity in this spec, not because the answer is known.
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

1. **Photo/video storage and retention.** Media must not live in the cloud the way the rest of
   the household's data does, but still needs to reach every household member somehow — that's
   the core problem to investigate for `architecture.md`/`security-privacy.md` (candidates:
   direct device-to-device transfer, provider-can't-read encrypted cloud storage, sync only
   while both devices are on the same network, etc.). Once a mechanism is chosen: how long is
   media kept, is it deleted with the entry it's attached to, and does it need the same
   offline-cache behavior as the rest of the data?
