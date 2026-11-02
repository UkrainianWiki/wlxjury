https://commons.wikimedia.org/wiki/Commons:WLX_Jury_Tool 

Deployment: [single-service procedure and recovery](docs/deployment.md).

## Security Controls

- [Incident prevention guidelines](docs/security/incident-prevention-guidelines.md): the
  standards new code follows, and what is still open.
- [Implementation plan](docs/security/incident-prevention-implementation-plan.md): each
  hardening item and its status.
- [XSS and header review](docs/security/review.md) and the
  [local images review follow-ups](docs/security/local-images-review-followups.md).
- Authentication, authorization, CSRF and error handling in the app: [CLAUDE.md](CLAUDE.md),
  "Authentication & Authorization"; host names and secure cookies in production:
  [docs/deployment.md](docs/deployment.md).

[![Build Status](https://travis-ci.org/intracer/wlxjury.svg?branch=master)](https://travis-ci.org/intracer/wlxjury?branch=master)
[![Build status](https://ci.appveyor.com/api/projects/status/nx5wm1jyga9uv1ia?svg=true)](https://ci.appveyor.com/project/intracer/wlxjury)
[![codecov.io](http://codecov.io/github/intracer/wlxjury/coverage.svg?branch=master)](http://codecov.io/github/intracer/wlxjury?branch=master)
