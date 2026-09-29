"""The sentences the model and the browser read byte for byte, shared with the Java airlock.

``prompts.json`` is a copy of ``conformance/cases/prompts/airlock.json``; ``test_prompts`` keeps them equal.
"""

import json
from importlib.resources import files

_P = json.loads(files(__package__).joinpath("prompts.json").read_text(encoding="utf-8"))

I_DONT_KNOW: str = _P["I_DONT_KNOW"]
COULD_NOT_REACH_DATA: str = _P["COULD_NOT_REACH_DATA"]
PLATFORM_PREAMBLE: str = _P["PLATFORM_PREAMBLE"]
USE_TOOLS_NUDGE: str = _P["USE_TOOLS_NUDGE"]
ANSWER_NOW: str = _P["ANSWER_NOW"]
REPEATED_CALL: str = _P["REPEATED_CALL"]
ANSWER_FORMAT_SCHEMA: str = _P["ANSWER_FORMAT_SCHEMA"]
ANSWER_FORMAT_INSTRUCTION: str = _P["ANSWER_FORMAT_INSTRUCTION"]
ANSWER_IN_FORMAT: str = _P["ANSWER_IN_FORMAT"]
RETRY: str = _P["RETRY"]
ERROR_COULD_NOT_COMPLETE: str = _P["ERROR_COULD_NOT_COMPLETE"]
ERROR_NO_MODEL_ENDPOINT: str = _P["ERROR_NO_MODEL_ENDPOINT"]
ERROR_MODEL_UNAVAILABLE: str = _P["ERROR_MODEL_UNAVAILABLE"]
