## What and why

<!-- What changes, and why. -->

## Security

Touches (tick all that apply):

- [ ] routes, controllers or `Secured`
- [ ] roles or accounts
- [ ] sessions
- [ ] SQL
- [ ] views or JavaScript
- [ ] config or deployment
- [ ] exports
- [ ] logs
- [ ] dependencies

If any is ticked:

- [ ] [`docs/security/review-checklist.md`](../docs/security/review-checklist.md) applied (the lines checked are quoted in the review)
- [ ] negative authorization tests added for every changed permission (another contest's admin, organizer, juror, inactive member, signed out)
- [ ] `testOnly security.*` passes; any new allowlist entry is per file with a reason
- [ ] [`docs/security/register.md`](../docs/security/register.md) updated if an item's status changed

## Checks

<!-- The specs run and their results (scripts/agent-check.py log paths). -->
