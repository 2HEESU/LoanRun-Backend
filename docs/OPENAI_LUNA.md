# Explanation model: GPT-6 Luna

The selected model is `gpt-6-luna`. Only this exact model receives `reasoning.effort=none` so the short
explanation task can use the existing 500-token output budget without spending it on internal reasoning.
Other explicitly configured models retain the previous request shape.

The endpoint remains POST `/v1/responses`, with `store=false`, strict JSON output and server-controlled
financial values/strategy. Only validated aggregate facts are sent. Output validation and deterministic
fallback are preserved. No keys, prompts or model text are logged. Default `OPENAI_ENABLED=false` remains.

Keep real credentials in the ignored local `.env` or the execution environment. This configuration does not
automatically load `.env`, start the app, migrate a database, or authorize a paid API request. The local
file and environment loading instructions are separate from live validation.

Run `gradlew.bat test --tests com.loanrun.ExplanationClientTest --tests com.loanrun.ExplanationConfigTest`
for intercepted-transport tests. Real credentials are unnecessary and no external request is made.

Official support/settings: [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna).
