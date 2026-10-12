from collections.abc import Sequence

from aisolate import AIsolateManager, Connection, DatabaseError, Exchange, Passage, context_preparator

CONTEXT_PREPARATOR = "audit-documents"
READER_ROLE = "auditor"
# At most three sections, and only close ones: every passage is prompt the model reads before it
# answers. Over the demo's documents a relevant section lies under 0.35, noise from 0.41 up.
TOP_K = 3
MAX_DISTANCE = 0.35

SEARCH = (
    "SELECT title, section, body FROM audit.documents WHERE context_preparator = %s AND"
    " embedding IS NOT NULL AND embedding <=> %s::vector <= %s ORDER BY embedding <=>"
    " %s::vector, id LIMIT %s"
)

UNINDEXED = "SELECT id, title, section, body FROM audit.documents WHERE context_preparator = %s AND embedding IS NULL"

INDEX = "UPDATE audit.documents SET embedding = %s::vector WHERE id = %s"


@context_preparator(order=1, description="Read minutes and policies", data_sources=["ledger"])
class AuditDocumentsContextPreparator:
    """Retrieval over the audited company's audit documents (board minutes, policies, the engagement
    letter), read from the data source ``ledger``, which the company's airlock defines: the question
    is embedded with the LLM ``nomic``, the closest sections are added to the exchange
    under one evidence id. Only an auditor reads them, as only an auditor reads the books: the rules
    bind the tools, this check binds the handler, which reads its data source directly. One instance
    serves every exchange concurrently: no fields, one connection per call."""

    def prepare(self, exchange: Exchange, aisolate: AIsolateManager) -> None:
        if READER_ROLE not in aisolate.principal.roles:
            return
        try:
            with aisolate.data_source("ledger") as db:
                _embed_missing_sections(db, aisolate)
                vector = to_vector(aisolate.llm("nomic").embed(exchange.question))
                cursor = db.cursor()
                cursor.execute(SEARCH, (CONTEXT_PREPARATOR, vector, MAX_DISTANCE, vector, TOP_K))
                passages = [Passage(title, section, body) for title, section, body in cursor.fetchall()]
                exchange.add_evidence(CONTEXT_PREPARATOR, passages)
        # Fail closed for documents: no passages, the model says it does not know. A ValueError:
        # no data source ledger, or an LlmError: no model nomic, or one that cannot embed.
        except (ValueError, DatabaseError) as unavailable:
            aisolate.log.warning("audit-documents unavailable: %s", _cause(unavailable))


# Embeds on the request thread so the sample runs without an indexer; a real project indexes ahead
# of time.
def _embed_missing_sections(db: Connection, aisolate: AIsolateManager) -> None:
    cursor = db.cursor()
    cursor.execute(UNINDEXED, (CONTEXT_PREPARATOR,))
    for row_id, title, section, body in cursor.fetchall():
        cursor.execute(INDEX, (to_vector(aisolate.llm("nomic").embed(f"{title}\n{section}\n{body}")), row_id))
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
