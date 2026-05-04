<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/aisolate-white.png">
    <img src="docs/assets/aisolate-black.png" alt="AIsolate" width="320">
  </picture>
</p>

# AIsolate

[![License: AGPL-3.0-only](https://img.shields.io/badge/license-AGPL--3.0--only-blue.svg)](LICENSE)

AIsolate gives an organisation's own chat, agent or application a governed endpoint to a large language
model over the organisation's existing databases. Every request carries the end user's token from the
organisation's identity provider, and every tool call runs under that identity. Row and column access is
compiled into the SQL at the data boundary. The model enforces nothing.

## License

AIsolate is free software under the GNU Affero General Public License, version 3 only (`LICENSE`, SPDX
`AGPL-3.0-only`). You may run, study, change and redistribute it; if you offer a modified AIsolate to others
over a network, you must offer them its source as well.

Copyright (C) 2026 Reportix GmbH.
