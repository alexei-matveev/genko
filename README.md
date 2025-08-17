# Genko

Genko is a simple command-line tool for interactive chat with language
models or other servers supporting OpenAI's protocoll. It maintains
conversation context and supports multi-turn dialogue.

Genko can also run as a local server that implements basic
OpenAI-compatible endpoints (`/v1/chat/completions` and
`/v1/models`). This allows you to interact with Genko using HTTP
requests or tools like Curl, and makes it possible to use Genko as a
backend for compatible clients, including itself.

Foremost it is a project to learn the limits of Copilot for Clojure.

## Usage

You may want to set `OPENAI_BASE_URL` for the upstream LLM:

    $ OPENAI_BASE_URL=https://api.example.com/v1
    $ OPENAI_API_KEY=sk-...
    $ lein run

Build executable in `./bin/genko`:

    $ just build

You can start the server with:

    $ genko --server

By default, the server listens on port 3000.  You can interact with
the server from the CLI by specifying the base URL:

    $ genko --base-url=http://localhost:3000/v1

Or directly with Curl:

    $ curl -s http://localhost:3000/v1/models | jq
    $ curl -sXPOST http://localhost:3000/v1/chat/completions -d '{"messages":[{"role":"user","content":"are you human?"}]}' | jq

For streaming responses (SSE):

    $ curl -vNXPOST http://localhost:3000/v1/chat/completions -d '{"messages":[{"role":"user","content":"tell me a story!"}],"stream":true}'


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

* [ ] Mitigate Remote Code Execution in `(sci/eval-string "untrusted
      text from llm")`?
* [ ] CORS and/or Auth for the case of running the server at
      `http://localhost:3000` and accessing it from the local browser?

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
