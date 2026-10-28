# Security Policy

## Reporting a vulnerability

Please report security issues privately through GitHub's
[private vulnerability reporting](../../security/advisories/new) (the
**Security** tab → **Report a vulnerability**), not in a public issue.

Only the latest release is supported; fixes ship in a new release.

## Scope

The app talks to the vehicle only through an OBD-II adapter, sending its own
ELM327 setup commands and the requests listed in the active PID file; it doesn't
filter those requests, so only import PID files you trust. Of particular interest
are anything that lets another app drive the adapter connection or read the
captured debug logs, and parsing bugs in imported PID or dashboard files that
crash or hang the app.
