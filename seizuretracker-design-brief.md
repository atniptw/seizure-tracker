# Seizure Tracker → Pet Diary — design brief

Source: story map artifact `seizuretracker-story-map`. This condenses the backbone and
priority stack into a brief for Claude Design. Legend: **(existing)** = works in the app
today, **(new)** = idea for the revamp, not yet built.

## Context for Claude Design

Android app (Kotlin + Jetpack Compose) for logging a household's pets' seizures and health
events, sharing a household via Firebase, and exporting a report for the vet. Read the
repo's `app/src/main/java/.../ui/theme/` (Color.kt, Theme.kt, Type.kt)
to pick up the existing Material 3 theme before designing new screens, so mockups stay
on-brand rather than generic.

## Two roles: admin and member  *(new — not in the app today)*

The role split below is **not built yet** — the current app has no roles and every member can
do everything. It's a design target for this revamp. Every screen below has to work for two
kinds of user (see `planning/security-privacy.md` §4.1 for the full breakdown):

- **Admin** — full control: manages pets, medications, vets, the member roster, the join
  code, and exports. The household creator, and anyone they promote.
- **Member (non-admin)** — **log-and-view only**. Sees everything (nothing is hidden), can
  log seizures and health notes, and can edit or delete entries *they* logged. Cannot edit
  pet profiles, medications, or vets; cannot rename anything, manage members, see the join
  code, or export. This is the petsitter / occasional-logger role.

Design implication: the manage-pets, manage-vets, manage-household, and export screens need
a **read-only member variant** — same information, no edit/add/remove affordances, no join
code shown. On the entry detail screen, edit/delete controls appear only on the viewer's own
entries unless they're an admin. Where it materially changes the layout, mock both states.

## Why this revamp: from seizure log to pet health diary

The app shipped as a single-purpose seizure tracker: one dog, one household, a detailed
form for exactly one kind of event. That's still the core and the best-built part of the
app — the seizure form captures duration, type, symptoms, triggers, recovery, and rescue
meds in real detail, and it needs to stay fast to fill out, because it's often being
filled out under stress right after something scary happened.

The expansion is to turn it into a general pet health diary: a place to jot down anything
worth mentioning to the vet — a limp, a change in appetite, an odd behavior — not just
seizures, and to support a household with more than one pet (dog, cat, other), each of
whom may see multiple vets (general care, emergency, a neurologist for a seizure
patient, etc.). So this isn't a rebuild from scratch — it's the existing seizure-first
app growing a second, lighter-weight entry type and a pet/vet dimension around it,
without slowing down or burying the thing it already does well. That tension —
add breadth without adding friction to the one flow that matters most in the moment —
is the central design problem for this revamp.

## Backbone (in journey order)

### 1. Get set up
- Sign in: Google (existing), continue without Google for a petsitter (existing), Apple sign-in (new), biometric re-auth (new)
- Create a household: enter pet + your name (existing), create (existing), add a pet photo (new), pick species — dog/cat/other (new); creator becomes the first admin. *Can be done without an account, but inviting anyone later triggers a required "add Google/Apple" prompt (new — `security-privacy.md` §3.2)*
- Join a household: enter 6-char code (existing), enter name & join (existing), scan a QR code (new), preview pet before confirming (new); a joiner lands as a non-admin member unless an admin promotes them

### 2. Manage pets — *admin-only actions; members get a read-only view*
- Add & switch pets *(all new)*: add a pet with name + species, switch via a pet switcher, set a default pet, archive/remove a pet — *switching pets and setting your own default work for everyone (per-device); adding/archiving is admin-only*
- Pet info: edit name/species/breed/weight (existing), add photo (new), birth date & diagnosis date (new), see this pet's linked vets (new) — *editing is admin-only; everyone sees the profile and the linked vets*
- Medications: add/edit/remove (existing), dose reminders (new), log that a dose was actually given (new) — *the medication list is admin-managed; setting a reminder and logging a dose-given are available to everyone (a dose-given is just an entry)*

### 3. Manage vets — *admin-only; members get a read-only view*
- Manage vet contacts: add a vet contact — clinic/vet/phone/email (existing single-field version today; new model), label the relationship per pet (general care / emergency / neuro / other) (new), link one vet to multiple pets (new), link multiple vets to one pet (new), edit/remove a contact (new), see which pets share a vet (new) — *members see the directory and links but no add/edit/remove controls*

### 4. Manage household — *admin-only*
- Household members: view & copy join code — *admins only; a member never sees the code*, see who's in the household (new) — *everyone*, remove a member (new) — *admins remove others; anyone can leave themselves*, rename household/pet after setup (new) — *admins only*

### 5. Log an entry
- Pick entry type *(all new)*: **seizure logging stays one tap away, never buried behind the type picker** (top priority — this is a hard requirement, not a nice-to-have), choose Seizure or Other/health note, pick which pet (defaults to last-viewed)
- Capture a seizure: date/time, duration, seizure type, symptoms, pre-seizure signs, triggers, recovery time/behavior, rescue meds given, notes — all existing today. New: one-tap timer during an active seizure, voice dictation, photo/video attachment
- Capture a health note (Other) *(all new, kept deliberately simple)*: what's going on (free text), when it started, notes, photo, flag for the vet. *Open question — not yet designed: which structured fields (severity, category, vitals) are worth adding later vs. just noise; don't over-build this before that research happens.*
- Save & confirm: save (existing), quick-log widget/lock screen (new), notify household on save (new)

### 6. Review history
- See it at a glance: days since last seizure, total count, recent entries (existing); switch pet, frequency trend chart, med-adherence status, "mention at next vet visit" list, combined all-pets view (new)
- Browse full history: full list (existing); filter by pet/type/date/logger, group by month (new)
- View & edit an entry: view detail, edit, delete with confirm (existing) — *a member sees edit/delete only on entries they logged; an admin, on any entry*; compare to similar past entries (new)

### 7. Share with the vet — *exporting is admin-only; handing the vet the phone is for anyone*
- Export a report: choose time range, share as PDF, share as CSV (existing); choose whether to include health notes, choose which pet(s), email directly to a chosen vet contact, include trend charts, see past exports (new) — *members can see the past-exports list but have no export button; the "hand the vet your phone and walk the dashboard" path needs no special role*

## Priorities called out explicitly by the product owner
1. Logging a seizure must never get harder or slower because of the new entry-type picker or multi-pet context — it should be the fastest possible path, even one-handed under stress.
2. Vets are shared, reusable contacts (many-to-many with pets, each link labeled with a role), not a flat field on one pet.
3. The health-note entry type is intentionally minimal right now — resist adding structured fields until there's research on what's actually useful vs. noise.
4. Non-admin members are log-and-view only. Every management screen needs a clean read-only state for them — not a disabled-looking one, and never a dead-end that implies they're missing something.
