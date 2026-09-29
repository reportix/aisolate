"""JSON written as the Java airlock's Jackson writes it: compact, keys in insertion order, non-ASCII
unescaped, control characters as upper-case ``\\u00XX``, a decimal as ``BigDecimal.toString``.

Every line the two runtimes must agree on byte for byte (the audit line, ``rows_hash``, the SSE
frames, what the model is shown) goes through :func:`dumps`.
"""

import decimal
import json
import math
import re

# json.dumps writes \u001f; Jackson writes \u001F. An escaped backslash before it is not an escape.
_LOWER_HEX = re.compile(r"(?<!\\)((?:\\\\)*)\\u00([0-9a-f]{2})")


def _string(text: str) -> str:
    return _LOWER_HEX.sub(lambda m: m.group(1) + "\\u00" + m.group(2).upper(),
                          json.dumps(text, ensure_ascii=False))


def _double(x: float) -> str:
    if math.isnan(x) or math.isinf(x):
        return '"NaN"' if math.isnan(x) else ('"Infinity"' if x > 0 else '"-Infinity"')
    if x == 0 or 1e-3 <= abs(x) < 1e7:
        return repr(x)
    # Double.toString: the same shortest digits as repr, written d.dddE<exp> outside [1e-3, 1e7).
    sign, digits, exponent = decimal.Decimal(repr(x)).normalize().as_tuple()
    if len(digits) == 1:  # Java then takes the closest two-digit decimal: 5e-324 is 4.9E-324
        sign, digits, exponent = decimal.Decimal(f"{decimal.Decimal(x):.1e}").normalize().as_tuple()
    mantissa = "".join(map(str, digits))
    return ("-" if sign else "") + mantissa[0] + "." + (mantissa[1:] or "0") + "E" + str(len(digits) + exponent - 1)


def dumps(value, sort_keys: bool = False) -> str:
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, str):
        return _string(value)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, decimal.Decimal):
        return str(value.copy_abs() if value.is_zero() else value)  # BigDecimal has no negative zero
    if isinstance(value, float):
        return _double(value)
    if isinstance(value, dict):
        items = sorted(value.items()) if sort_keys else value.items()
        return "{" + ",".join(_string(str(k)) + ":" + dumps(v, sort_keys) for k, v in items) + "}"
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(dumps(v, sort_keys) for v in value) + "]"
    raise TypeError(f"not JSON: {type(value).__name__}")


def loads(text: str):
    """As the airlock reads a tool result: exact decimals, never floats."""
    return json.loads(text, parse_float=decimal.Decimal)
