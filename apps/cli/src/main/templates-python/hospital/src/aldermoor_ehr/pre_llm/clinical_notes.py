from collections.abc import Sequence

from aisolate import AIsolateManager, Connection, DatabaseError, Exchange, Passage, context_preparator

CONTEXT_PREPARATOR = "clinical-notes"
READER_ROLE = "doctor"
# At most three notes, and only close ones: every passage is prompt the model reads before it
# answers.
TOP_K = 3
MAX_DISTANCE = 0.35

# The asking doctor's patients only: the care team in this department, by the token's e-mail.
SEARCH = (
    "SELECT n.title, n.written_on::text AS section, n.body FROM ehr.notes n"
    " JOIN ehr.care_team ct ON ct.patient_id = n.patient_id AND ct.department ="
    " n.department JOIN ehr.clinicians c ON c.id = ct.clinician_id WHERE n.department = %s"
    " AND c.email = %s AND n.embedding IS NOT NULL AND n.embedding <=> %s::vector <= %s"
    " ORDER BY n.embedding <=> %s::vector, n.id LIMIT %s"
)

UNINDEXED = "SELECT id, title, body FROM ehr.notes WHERE department = %s AND embedding IS NULL"

INDEX = "UPDATE ehr.notes SET embedding = %s::vector WHERE id = %s"


@context_preparator(order=1, description="Read clinical notes", data_sources=["ehr"])
class ClinicalNotesContextPreparator:
    """Retrieval over the clinical notes of the health record, the data source ``ehr`` of the
    department's airlock: the question is embedded with the LLM ``nomic``, the closest
    notes are added to the exchange under one evidence id. It reads the notes of one department, the
    one whose airlock the exchange runs on, about the patients on the asking doctor's care team
    there, and only for a doctor: the chief's statistics never come with a patient's notes. The code
    is the same in every department; the airlock's id decides whose notes, and the department's
    database role holds only its own department's notes (row-level security), so cardiology never
    reads, or embeds, a psychiatry note. One instance serves every exchange concurrently: no fields,
    one connection per call."""

    def prepare(self, exchange: Exchange, aisolate: AIsolateManager) -> None:
        if READER_ROLE not in aisolate.principal.roles:
            return
        department = aisolate.airlock.airlock
        try:
            with aisolate.data_source("ehr") as db:
                _embed_missing_notes(db, department, aisolate)
                vector = to_vector(aisolate.llm("nomic").embed(exchange.question))
                cursor = db.cursor()
                cursor.execute(
                    SEARCH,
                    # A token without an e-mail matches no clinician: no notes.
                    (department, aisolate.principal.email, vector, MAX_DISTANCE, vector, TOP_K),
                )
                passages = [Passage(title, section, body) for title, section, body in cursor.fetchall()]
                exchange.add_evidence(CONTEXT_PREPARATOR, passages)
        # Fail closed for notes: no passages, the model says it does not know. A ValueError:
        # no data source ehr, or an LlmError: no model nomic, or one that cannot embed.
        except (ValueError, DatabaseError) as unavailable:
            aisolate.log.warning("clinical-notes unavailable: %s", _cause(unavailable))


# Embeds on the request thread so the sample runs without an indexer; a real project indexes ahead
# of time. The whole department's: the indexer, not what the model reads.
def _embed_missing_notes(db: Connection, department: str, aisolate: AIsolateManager) -> None:
    cursor = db.cursor()
    cursor.execute(UNINDEXED, (department,))
    for row_id, title, body in cursor.fetchall():
        cursor.execute(INDEX, (to_vector(aisolate.llm("nomic").embed(f"{title}\n{body}")), row_id))
    db.commit()


def to_vector(embedding: Sequence[float]) -> str:
    return "[" + ",".join(map(str, embedding)) + "]"


# Never a database error's message: it can echo the database's address. pg8000 carries the
# SQLSTATE in args[0]["C"].
def _cause(error: ValueError | DatabaseError) -> str:
    if isinstance(error, ValueError):
        return str(error)
    fields = error.args[0] if error.args and isinstance(error.args[0], dict) else {}
    return f"{type(error).__module__}.{type(error).__qualname__} SQLState={fields.get('C')}"
