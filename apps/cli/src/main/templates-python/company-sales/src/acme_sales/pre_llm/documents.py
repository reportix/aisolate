from collections.abc import Sequence

from aisolate import AIsolateManager, Connection, DatabaseError, Exchange, Passage, context_preparator

CONTEXT_PREPARATOR = "client-documents"
# At most three sections, and only close ones: every passage is prompt the model reads before it
# answers. Over the demo's documents a relevant section lies under 0.35, noise from 0.41 up.
TOP_K = 3
MAX_DISTANCE = 0.35

SEARCH = (
    "SELECT title, section, body FROM clients.documents WHERE context_preparator = %s AND"
    " embedding IS NOT NULL AND embedding <=> %s::vector <= %s ORDER BY embedding <=>"
    " %s::vector, id LIMIT %s"
)

UNINDEXED = "SELECT id, title, section, body FROM clients.documents WHERE context_preparator = %s AND embedding IS NULL"

INDEX = "UPDATE clients.documents SET embedding = %s::vector WHERE id = %s"


@context_preparator(order=1, description="Read client contracts", data_sources=["clients"])
class SalesDocumentsContextPreparator:
    """Retrieval over Acme's client contracts and board memos, read from the data source
    ``clients`` of ``aisolate.yaml``: the question is embedded with the LLM ``nomic``,
    the closest sections are added to the exchange under one evidence id. It filters nothing, by
    design: everyone the airlock admits reads every section, board memos included. Who may ask this
    airlock is its space's users and its own, so a user put on the space to ask another airlock may
    ask this one too. The user lists are the boundary here: put on the space only people who may
    read every document of every airlock. One instance serves every exchange concurrently: no
    fields, one connection per call."""

    def prepare(self, exchange: Exchange, aisolate: AIsolateManager) -> None:
        try:
            with aisolate.data_source("clients") as db:
                _embed_missing_sections(db, aisolate)
                vector = to_vector(aisolate.llm("nomic").embed(exchange.question))
                cursor = db.cursor()
                cursor.execute(SEARCH, (CONTEXT_PREPARATOR, vector, MAX_DISTANCE, vector, TOP_K))
                passages = [Passage(title, section, body) for title, section, body in cursor.fetchall()]
                exchange.add_evidence(CONTEXT_PREPARATOR, passages)
        # Fail closed for documents: no passages, the model says it does not know. A ValueError:
        # no data source clients, or an LlmError: no model nomic, or one that cannot embed.
        except (ValueError, DatabaseError) as unavailable:
            aisolate.log.warning("client-documents unavailable: %s", _cause(unavailable))


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
