# Aldermoor Hospital department assistant

An AIsolate project: the code, the rules and the settings of the `${AIRLOCK}` airlock, one
department's assistant over the hospital's one health record. The hospital is the space; each
department is an airlock of it, made from this same template:
`aisolate create -s=<space> -t=hospital --airlock=cardiology --language=java`, and again with
`--airlock=psychiatry`. `aisolate deploy` builds the project and deploys it, together with
`aisolate.yaml`, into the space `AISOLATE_SPACE` names in `.env`. The airlock is created or updated from the `Airlock` at the top of `aisolate.yaml` on every
deploy: its name, its description and its own doctors; a field the file leaves out keeps what the AIsolate UI set.
The full walk-through is `docs/develop.md` in the AIsolate repository.

## One health record, what each department needs

The health record is one database, the hospital's single source of truth. Each department's
airlock defines the data source `ehr` on it in the AIsolate UI, with the department's own database
role, which reaches only that department's part of the same patient, the minimum necessary:

| Department | Its role reaches | `tools` | Its context preparator reads |
|---|---|---|---|
| `cardiology` | `cardiology.*` (vitals, ECGs, cardiac labs such as troponin), `ehr.medications`, the patients' name, national ID and birth date, the care team, the cardiology notes | `read_cardiology_records`, `aggregate_cardiology_records`, `read_medications` | the cardiology notes of the doctor's patients |
| `psychiatry` | `psychiatry.*` (therapy sessions, psychiatric diagnoses), `ehr.medications`, the same patient columns, the care team, the psychiatry notes | `read_psychiatry_records`, `aggregate_psychiatry_records`, `read_medications` | the psychiatry notes of the doctor's patients |

`ehr.notes` holds both departments' notes under row-level security: each role sees its own
department's rows. So code in the cardiology airlock, a tool or a context preparator, cannot read a
therapy session or a psychiatry note, whatever it asks the database.

The code is the same in both; the airlock decides. `aisolate.yaml` names the tools after the
airlock (`read_${AIRLOCK}_records`), so cardiology's model sees the medication list, and with it
that a patient takes sertraline, but never a therapy note that says why (the list's `indication`
is always unknown); the deploy warns that the airlock leaves the other department's tools out,
which is the point. The prompt sends every record and medication question to the tools, so the
answer cites the row, never a note's prose; what no tool or note of this airlock holds is "I don't
know".

A doctor sees only the patients they treat. `ehr.clinicians` holds each clinician's e-mail and
department, `ehr.care_team` the treating clinician of a patient in each department (one per
patient and department). `EhrTools`' identity join finds the asking clinician by the token's
e-mail, as `principal.clinician_id`; every entity joins its patient's care team as the hidden
column `<department>_clinician`, and the doctors' rules keep the rows where the two match. The
medication list joins both departments' care teams, so each department's doctor reads the whole
list of their own patients. `ClinicalNotesContextPreparator` reads the notes of the department
whose airlock the exchange runs on, about the asking doctor's patients only, and only for a doctor.

Who may ask is the airlock's: a department's doctors hold the role `doctor`; the chief of medicine's role `chief` is the space's and asks every department, for statistics only. The role lists are the second
line of defence: a doctor admitted to the other department's airlock reads none of its records,
since a department's care teams list only its own clinicians.

## The tree

```
aisolate.yaml                     the airlock (the department): its name, description and settings
.env, .env.example                  AISOLATE_ENDPOINT, AISOLATE_SPACE; no credentials, the airlock holds them
rules/                              one rule set per entity: doctors read their patients' rows, the chief aggregates
src/main/java/com/aldermoor/ehr/
  pre_llm/ClinicalNotesContextPreparator.java   ContextPreparator: the department's clinical notes
  pre_llm/SessionMemory.java                    ConversationMemory
  tools/EhrTools.java                           ToolPlugin: a read and an aggregate tool per department,
                                                and read_medications
  post_llm/LatestReadingVerifier.java           AnswerVerifier
  post_llm/FormularyVerifier.java               AnswerVerifier
  post_llm/PatientVerifier.java                 AnswerVerifier
src/test/java/com/aldermoor/ehr/   the tests, with FakeAIsolateManager standing in for AIsolate
build.gradle.kts                    copies rules/ to aisolate/rules/ in the build
settings.gradle.kts, gradlew, gradle/, libs/aisolate-sdk.jar
```

Every entity joins its patient: `patient` (the name, filtered with `ilike`), `patient_id`,
`national_id` and `age_group` (0-17, 18-39, 40-59, 60-79, 80+, from the birth date), the care
team, then its own columns. A read returns at most 20 rows, in id order: the prompt has the model
filter by date for the latest values.

## Verifiers

Grounding is AIsolate's: it writes every figure in from the evidence and flags every typed number
and every typed value of a fact column: `drug`, `dose` and `frequency` of the medication list,
`diagnosis` and `icd10` of the diagnoses (`docs/verification.md`). The verifiers are the project's,
`AnswerVerifier` handlers in `post_llm/` that judge the finished answer and put a sentence under it,
never blocking it. `LatestReadingVerifier` checks that a question for the last, latest, most recent
or current reading of a lab test, the vital signs, the ECGs or the therapy sessions is answered with
the latest one in the records; earlier ones may come with it, never alone. `FormularyVerifier`
checks that every drug the answer names is one the returned records hold; a drug the question names
is expected, and words quoted from a note are not counted. Its formulary is the hospital-wide drug
names, read through `ehr` from the medication list (names only); a real deployment would read a
drug catalogue. `PatientVerifier` checks that every figure is about a patient the question names.

## Rules

`rules/<entity>.json`, one per entity:

- `patient-care` (on the medication list `cardiology-care` and `psychiatry-care`): the role
  `doctor` reads the rows of the patients on their care team; the national ID is masked.
- `chief-statistics`: the role `chief` aggregates only, in groups of at least five patients; a
  smaller group is left out of the result. Every entity is about its patient
  (`withSubject("patient_id")`), so the minimum counts distinct patients, never readings: one
  patient's five lab values are a group of one. The national ID is masked; the patient's name and
  id, the row ids, the dates and the free-text notes are hidden, so the chief can neither read nor
  group by them.

Every other role gets nothing.

## Commands

- `./gradlew build` compiles, runs the tests and writes `build/libs/hospital.jar`.
- `aisolate login`, then `aisolate deploy`: the airlock `${SPACE}/${AIRLOCK}`, once the space exists in the
  AIsolate UI; it answers once the airlock's data source `ehr`, with the department's role, is set
  there too.
