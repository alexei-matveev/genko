# Genko

Genko is a simple command-line tool for interactive chat with language
models or other servers supporting OpenAI's protocoll. It maintains
conversation context and supports multi-turn dialogue.

Foremost it is a project to learn the limits of Copilot for Clojure.

## Usage

There is no default for `OPENAI_API_BASE_URL`, so you need to set
both environment variables:

    $ OPENAI_BASE_URL=https://api.example.com/v1
    $ OPENAI_API_KEY=...
    $ lein run

Build executable in `./bin/genko`:

    $ just build


## Other CLI interfaces

See also Aider, Codex and Claude Code CLIs and other interfaces for
inspiration:

* https://aider.chat/
* https://github.com/openai/codex
* https://docs.anthropic.com/en/docs/claude-code/sdk
* https://llm.datasette.io with
  [Tools](https://simonwillison.net/2025/May/27/llm-tools/)
* https://github.com/baalimago/clai


## Backlog

* [ ] CORS and/or Auth?

## License

Copyright © 2025 Alexei Matveev

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
