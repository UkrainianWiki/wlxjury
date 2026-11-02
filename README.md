https://commons.wikimedia.org/wiki/Commons:WLX_Jury_Tool 

Deployment: [single-service procedure and recovery](docs/deployment.md).

## Security Controls

- [Secure coding rules](docs/security/secure-coding.md): the rules new code follows, one per
  past issue class, with the guard specs (`testOnly security.*`).
- [Review checklist](docs/security/review-checklist.md), including the release gate.
- [Security register](docs/security/register.md): the status of every security item.
- [Implementation plan](docs/security/incident-prevention-implementation-plan.md): the
  2026 hardening items (history).
- [XSS and header review](docs/security/review.md) and the
  [local images review follow-ups](docs/security/local-images-review-followups.md).
- Authentication, authorization, CSRF and error handling in the app: [CLAUDE.md](CLAUDE.md),
  "Authentication & Authorization"; host names and secure cookies in production:
  [docs/deployment.md](docs/deployment.md).

[![Build Status](https://travis-ci.org/intracer/wlxjury.svg?branch=master)](https://travis-ci.org/intracer/wlxjury?branch=master)
[![Build status](https://ci.appveyor.com/api/projects/status/nx5wm1jyga9uv1ia?svg=true)](https://ci.appveyor.com/project/intracer/wlxjury)
[![codecov.io](http://codecov.io/github/intracer/wlxjury/coverage.svg?branch=master)](http://codecov.io/github/intracer/wlxjury?branch=master)
