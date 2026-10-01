from collections.abc import Sequence

from aisolate import AIsolateManager, Connection, DatabaseError, Exchange, Passage, Principal, context_preparator

CONTEXT_PREPARATOR = "contracts"
# Two contracts of five short sections: up to five passages, an employee's whole contract. A question
# in plain words lies up to 0.55 from the section that answers it ("Who reports to me?"), an unrelated
# one ("What is the capital of France?") from 0.6 up.
TOP_K = 5
MAX_DISTANCE = 0.6
# The words the platform's prompt gives the model for "me" and "my".
OWN = " (the asking user's own)"

SEARCH = (
    "SELECT title, section, body, owner_email = %s AS own FROM hr.documents WHERE context_preparator = %s"
    " AND embedding IS NOT NULL AND (%s::text IS NULL OR owner_email = %s) AND embedding <=> %s::vector <= %s"
    " ORDER BY embedding <=> %s::vector, id LIMIT %s"
)

UNINDEXED = "SELECT id, title, section, body FROM hr.documents WHERE context_preparator = %s AND embedding IS NULL"

INDEX = "UPDATE hr.documents SET embedding = %s::vector WHERE id = %s"


@context_preparator(order=1, description="Read employment contracts", data_sources=["hr"])
class ContractsContextPreparator:
    """Governed retrieval over Acme's employment contracts, read from the data source ``hr`` of
    ``aisolate.yaml``: the question is embedded with the LLM ``nomic``, the closest contract sections
    the asking user may read are added to the exchange under one evidence id, the asking user's own
    marked as theirs. Who may read what is decided here, from the principal AIsolate hands over: the
    CEO reads every contract, anyone else only the one that names their e-mail as owner. One instance
    serves every exchange concurrently: no fields, one connection per call."""

    def prepare(self, exchange: Exchange, aisolate: AIsolateManager) -> None:
        try:
            with aisolate.data_source("hr") as db:
                _embed_missing_sections(db, aisolate)
                owner = owner_of(aisolate.principal)
                vector = to_vector(aisolate.llm("nomic").embed(exchange.question))
                cursor = db.cursor()
                cursor.execute(
                    SEARCH,
                    (
                        aisolate.principal.email or "",
                        CONTEXT_PREPARATOR,
                        owner,
                        owner,
                        vector,
                        MAX_DISTANCE,
                        vector,
                        TOP_K,
                    ),
                )
                passages = [
                    Passage(title + (OWN if own else ""), section, body)
                    for title, section, body, own in cursor.fetchall()
                ]
                exchange.add_evidence(CONTEXT_PREPARATOR, passages)
        # Fail closed for documents: no passages, the model says it does not know. A ValueError:
        # no data source hr, or an LlmError: no model nomic, or one that cannot embed.
        except (ValueError, DatabaseError) as unavailable:
            aisolate.log.warning("contracts unavailable: %s", _cause(unavailable))


def owner_of(principal: Principal) -> str | None:
    """The owner whose contracts the principal reads: None for the CEO, who reads every contract;
    for anyone else their e-mail, or "" when the token carries none, which owns no contract."""
    if "ceo" in principal.roles:
        return None
    return principal.email or ""


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
