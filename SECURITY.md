# Security Policy

Studio Mobile handles signing keys, GitHub tokens, and a connection to AI assistants through MCP. Security reports are taken seriously and are welcome.

## Supported versions

Only the latest version (the `main` branch and the newest release) receives security fixes. If you are on an older build, please update before reporting, in case the issue is already fixed.

## Reporting a vulnerability

**Please do not open a public issue or pull request for a security problem.**

Report it privately through GitHub:

1. Open the **Security** tab of this repository.
2. Choose **Report a vulnerability**.
3. Describe the problem and how to reproduce it.

A good report includes:

- what is affected (feature, file, or class) and the app version
- the steps to reproduce, or a small proof of concept
- what an attacker could do with it
- your Android version and device, if it matters

**Never include real secrets in a report.** Do not attach working GitHub tokens, `.p12` keystores, key passwords, or your MCP relay secret. Use throwaway values or redact them.

### What to expect

- We aim to acknowledge a report within **7 days**.
- We will tell you whether we consider it a vulnerability and keep you updated on the fix.
- Once a fix is released, we are happy to credit you, unless you prefer to stay anonymous.
- Please give us reasonable time to fix the issue before you disclose it publicly.

## What is in scope

Areas that matter most, because they protect user secrets or run untrusted content:

| Area | Examples |
|---|---|
| **Signing keys and keystores** | Leaking or exposing a project's signing key, key passwords, or an exported `.p12`; a key being readable by other apps |
| **GitHub token** | Token stored or logged in a readable form, sent to any host other than GitHub, or exposed in crash reports and build logs |
| **MCP and the relay** | Access to the phone without the relay secret, a way to read or write files outside the opened project, the relay secret leaking, vulnerabilities in the code under `relay/` |
| **Downloads and archives** | Path traversal or unsafe extraction in ZIP or TAR handling, checksum or integrity checks that can be bypassed, tampered build tools or dependencies being accepted |
| **Build pipeline** | A project or dependency that can run code or write files outside its intended build directories |
| **App components** | Exported activities, providers, or intents that let another app read private data |
| **Workflows** | Secrets or tokens exposed by the GitHub Actions workflows in this repository |

## What is out of scope

- Issues that need a rooted or already-compromised device
- Problems in third-party components (Node.js, CPython, Dart, the NDK, Gradle, and other libraries). Please report those to the upstream project. If Studio Mobile ships a vulnerable version, tell us so we can update.
- Social engineering, phishing, or physical access to an unlocked phone
- A relay that you deployed with a weak or shared secret, or without HTTPS
- Denial of service that only affects your own device or your own project
- Reports from automated scanners without a working proof of concept

## Protecting your own secrets

Some of the safest steps are on the user's side:

- **GitHub token:** use a fine-grained token limited to the repositories you build, with only the permissions the app asks for. If a token may have leaked, revoke it on GitHub right away and enter a new one.
- **Signing keys:** export your key with **Export** and store the `.p12` and its password somewhere safe. A lost key means you can no longer update your app on Google Play. Never commit a keystore or its password to a repository.
- **MCP relay:** generate a long random secret (for example `openssl rand -hex 24`), serve the relay over HTTPS, and do not share the relay address. Anyone who has the secret can reach your phone through the relay. Rotate the secret if you suspect it leaked.
- **AI assistants:** an assistant connected through MCP can read and change your project files and start builds. Only connect assistants you trust, and review what they change.

## Safe harbor

If you act in good faith, follow this policy, avoid accessing other people's data, and do not disrupt other users, we will not pursue action against you for your research.
