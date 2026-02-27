# Security Policy

## About This Project

Corey's Attractor is a personal learning project — a Kotlin/JVM DOT-based AI pipeline runner. It is not production software and is not intended for use in critical systems. That said, responsible disclosure of security issues is appreciated and will be taken seriously.

## Supported Versions

There are no versioned releases at this time. Only the latest commit on the `main` branch is considered supported.

| Version      | Supported |
|--------------|-----------|
| Latest main  | Yes       |
| Any tag/release | No (none exist yet) |

## Reporting a Vulnerability

**Do not open a public GitHub issue to report a security vulnerability.**

Please use GitHub's private vulnerability reporting feature:

1. Navigate to the repository on GitHub.
2. Click the **Security** tab.
3. Click **Report a vulnerability**.
4. Fill out the form with as much detail as possible.

This keeps the report confidential until a fix is in place.

## What to Include in a Report

A useful vulnerability report includes:

- A clear description of the vulnerability and the component it affects.
- Step-by-step instructions to reproduce the issue.
- The potential impact (e.g., data exposure, arbitrary code execution, denial of service).
- Any relevant environment details (OS, JVM version, Kotlin version, etc.).
- If applicable, a suggested fix or mitigation.

The more detail provided, the easier it is to understand and address the issue.

## Response Timeline

- **Acknowledgment**: Within 7 days of receiving the report.
- **Status update**: Within 30 days, with an assessment of severity and a plan for remediation.
- **Fix for critical vulnerabilities**: Best effort within 90 days. Given that this is a personal project maintained by a single developer, timelines may vary for lower-severity issues.

## Disclosure

Once a fix is available, coordinated public disclosure is preferred. Please allow time for a fix to be merged to `main` before disclosing the issue publicly.

## A Note on Scope

Because this is a personal learning project and not production software:

- There are no SLAs or guarantees of any kind.
- The maintainer will make a good-faith effort to address valid security reports.
- Issues that only apply to development or local environments may be treated as low priority.

Thank you for helping keep this project and its users safe.
