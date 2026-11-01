# Security

## Reporting a problem

Please report security issues privately through GitHub's **Report a vulnerability** button on the repository's Security tab, not in a public issue. I'll reply as soon as I can.

## How the app protects your data

- It runs only on your own machine. The web UI listens on `127.0.0.1:8765`, rejects other `Host` headers, and needs an `X-Tracker` header on every request that changes something.
- Gmail access is read-only (`gmail.readonly`) through Google sign-in. The app never sees your password and never sends, deletes or changes mail.
- The Gmail token is encrypted with Windows DPAPI for your Windows user. The Anthropic API key is read from an environment variable or a local config file and is never logged.
- Email text is sent only to the Anthropic API for classification.

## What is never committed

API keys, the Google client secret, Gmail tokens, the SQLite database, logs, `config.properties`, the personal `blocked-senders.txt` and private eval data all live in `%LOCALAPPDATA%\PlacementTracker` or in git-ignored paths. The test data in this repository is made up.
