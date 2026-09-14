# Security Policy

## Supported versions

Trove is maintained by one person, so only the latest release gets security fixes. Fixes land on
`develop` first and reach a release when `develop` is promoted to `master`.

| Version | Supported |
| --- | --- |
| Latest release (`master`, and the matching container image) | Yes |
| Older releases | No: update to the latest |
| `develop` and `develop-*` images | Fixed as soon as possible, but not a release |

To update a native install, run `./deploy.sh` in `/opt/trove` or use **Update now** in the app. For
Docker, pull the latest image.

## Reporting a vulnerability

Please **don't open a public issue** for a security problem.

Report it privately through GitHub instead:
[**Report a vulnerability**](https://github.com/X2-Consult/trove/security/advisories/new)
(the Security tab, then "Report a vulnerability"). Only the maintainer can see it.

Helpful things to include:

- what an attacker can do, and what they need first (an account, a particular permission, a Kobo or
  OPDS token, network access);
- the steps or a request that shows it;
- the Trove version (shown in the app's sidebar) and whether you run it natively or in Docker.

## What happens next

- You'll get a reply within **7 days**.
- If it's confirmed, the fix is worked on privately and released as soon as it's ready; you'll be told
  when, and credited in the advisory unless you'd rather not be.
- If it isn't treated as a vulnerability, you'll be told why.

## Scope

In scope: the Trove server and web app in this repository, including its Kobo, KOReader, OPDS and
Komga-compatible APIs, and the install, deploy and update scripts.

Out of scope: problems that need an administrator account to exploit against the same install, a
setup that ignores the install notes (for example, exposing the database to the internet), and
issues in BookLore or Grimmory that don't affect Trove (report those to those projects).
