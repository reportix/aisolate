"""Fails when an installed distribution of the current environment is not under a permissive license
(docs/design-decisions.md: Apache, MIT, BSD, PSF, ISC; never GPL, LGPL, AGPL, MPL). Run it with the
module's own environment: ``uv run python <path>/check_licenses.py``."""

import re
import sys
from importlib.metadata import distributions

PERMISSIVE = {
    "apache-2.0", "mit", "mit-0", "bsd-2-clause", "bsd-3-clause", "psf-2.0", "isc", "0bsd",
    "python-2.0", "unlicense",
}
CLASSIFIERS = {
    "Apache Software License": "apache-2.0",
    "MIT License": "mit",
    "MIT No Attribution License (MIT-0)": "mit-0",
    "BSD License": "bsd-3-clause",
    "Python Software Foundation License": "psf-2.0",
    "ISC License (ISCL)": "isc",
}
# Our own modules: AGPL by design, the reason the rest must be permissive.
OURS = {"aisolate", "aisolate-runtime", "aisolate-tool-server", "aisolate-airlock"}
# Distributions whose metadata gives no SPDX id; checked by hand against their repository.
KNOWN = {"protobuf": "BSD-3-Clause"}


def licenses_of(dist) -> list[str]:
    meta = dist.metadata
    if meta["Name"].lower() in KNOWN:
        return [KNOWN[meta["Name"].lower()]]
    if meta.get("License-Expression"):
        return [meta["License-Expression"]]
    found = [CLASSIFIERS.get(c.rsplit(" :: ", 1)[-1], c) for c in meta.get_all("Classifier") or [] if c.startswith("License ::")]
    if found:
        return found
    if meta.get("License"):
        return [meta["License"].splitlines()[0]]
    return ["unknown"]


def permissive(expression: str) -> bool:
    """An SPDX expression: some OR alternative whose AND terms are all permissive."""
    text = expression.lower().replace("(", " ").replace(")", " ")
    if re.search(r"\b(a?gpl|lgpl|mpl)", text):
        return False
    alternatives = re.split(r"\s+or\s+", text.strip())
    return any(all(term.strip() in PERMISSIVE for term in re.split(r"\s+and\s+", alt)) for alt in alternatives)


def main() -> int:
    problems = []
    for dist in sorted(distributions(), key=lambda d: d.metadata["Name"].lower()):
        name = dist.metadata["Name"]
        if name.lower() in OURS:
            continue
        found = licenses_of(dist)
        # Classifiers list every license a dual-licensed package offers; one permissive is enough.
        if not any(permissive(license) for license in found):
            problems.append(f"{name} {dist.version}: {' / '.join(found)}")
    for problem in problems:
        print(f"not a permissive license: {problem}", file=sys.stderr)
    print(f"licenses: {'FAIL' if problems else 'ok'}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
