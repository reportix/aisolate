import threading

from aisolate import AIsolateManager, Answer, Exchange, memory

KEEP = 3
MAX_QUESTION_LENGTH = 200


@memory(order=1, description="Remember the conversation")
class SessionMemory:
    """The last three questions of each user, kept in this process for its lifetime: enough for "and
    what about last year?" to make sense. Per user, never across users; gone when the airlock
    restarts. Each question is kept to its first 200 characters."""

    def __init__(self) -> None:
        # Tuples, swapped under the lock per user: concurrent exchanges never see a half-written
        # one. One entry per user for the life of the process, never evicted; evict idle users when
        # an airlock serves more people than fit in memory.
        self._last_questions: dict[str, tuple[str, ...]] = {}
        self._lock = threading.Lock()

    def recall(self, exchange: Exchange, aisolate: AIsolateManager) -> None:
        previous = self._last_questions.get(aisolate.principal.sub, ())
        if previous:
            exchange.add_context("The user's recent questions, oldest first: " + " | ".join(previous))

    def remember(self, exchange: Exchange, answer: Answer, aisolate: AIsolateManager) -> None:
        with self._lock:
            kept = self._last_questions.get(aisolate.principal.sub, ())
            self._last_questions[aisolate.principal.sub] = (*kept, shorten(exchange.question))[-KEEP:]


def shorten(question: str) -> str:
    return question[:MAX_QUESTION_LENGTH]
