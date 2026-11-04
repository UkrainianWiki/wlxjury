https://commons.wikimedia.org/wiki/Commons:WLX_Jury_Tool 

Deployment: [single-service procedure and recovery](docs/deployment.md).

Changes, by release and deploy date: [CHANGELOG.md](CHANGELOG.md). Practices:
[security](docs/security/secure-coding.md), [database](docs/db/practices.md),
[performance](docs/performance/practices.md), [testing](docs/testing/practices.md) and the
others in the [docs index](docs/README.md); open work: [roadmap](docs/plans/2026-11-02-roadmap.md).

## Security Controls

- [Secure coding rules](docs/security/secure-coding.md): the rules new code follows, one per
  past issue class, with the guard specs (`testOnly security.*`).
- [Review checklist](docs/security/review-checklist.md), including the release gate.
- [Security register](docs/security/register.md): the status of every security item.
- Past reviews and their evidence: [docs/history/](docs/history/README.md) (the 2026-11-02
  re-review; the earlier reviews are in git, listed there).
- Authentication, authorization, CSRF and error handling in the app: [CLAUDE.md](CLAUDE.md),
  "Authentication & Authorization"; host names and secure cookies in production:
  [docs/deployment.md](docs/deployment.md).

[![CI](https://github.com/UkrainianWiki/wlxjury/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/UkrainianWiki/wlxjury/actions/workflows/ci.yml)
