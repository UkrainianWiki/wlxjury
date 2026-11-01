# REST API

The jury tool serves a small REST API under `/api` (Tapir, `app/api/Api.scala`). Swagger UI
and the OpenAPI document are public at `/api/docs` (`/api/docs/docs.yaml`); they hold no data.

So far the API has the contest endpoints, for root only, as the contest pages are:

| Method | Path | |
|---|---|---|
| `GET` | `/api/contests` | all contests |
| `GET` | `/api/contests/{id}` | one contest |
| `POST` | `/api/contests` | create a contest (an `id` in the body is ignored) |
| `PUT` | `/api/contests` | update the contest of the body's `id` |

## Signing in

- **A personal API token**, for command-line tools: `Authorization: Bearer wlxj_...`. Create
  one on your profile page (your name in the navbar, then "API tokens"), with a name and an
  expiry of 30 days, 90 days, a year or never. The token is shown once, right after you
  create it: copy it then. Revoke it there when you no longer need it, or if it may have
  leaked. A token acts as you, with your memberships and roles; it can't create or revoke
  tokens. Keep it out of shell history and scripts you share (an environment variable or a
  file only you can read).
- **The session cookie**, for a page of the tool calling the API from the browser. A write
  (`POST`, `PUT`, `DELETE`) then needs the `Csrf-Token` header, as the gallery's votes send it.

A request with an `Authorization` header is judged by that header alone: a bad token is
refused even if the request also carries a signed-in session cookie.

```bash
export WLXJURY_TOKEN=wlxj_...   # from your profile page
curl -H "Authorization: Bearer $WLXJURY_TOKEN" https://jury.wle.org.ua/api/contests

# PUT takes the whole contest, as GET gives it: fetch, edit, send back
curl -H "Authorization: Bearer $WLXJURY_TOKEN" https://jury.wle.org.ua/api/contests/603 > contest.json
curl -H "Authorization: Bearer $WLXJURY_TOKEN" -H "Content-Type: application/json" \
     -X PUT --data @contest.json https://jury.wle.org.ua/api/contests
```

## Errors

Errors are JSON, with a fixed code and never the request's text:

| Status | Body | When |
|---|---|---|
| 400 | `{"error": "bad_request"}` | a body or path that can't be read |
| 401 | `{"error": "unauthorized"}`, `WWW-Authenticate: Bearer` | no credentials, or a token that is unknown, revoked, expired or malformed |
| 403 | `{"error": "forbidden", "reference": "1a2b3c4d"}` | signed in, but not allowed; the reference finds the log line |
| 403 | `{"error": "csrf", "reference": "…"}` | a session's write without a valid `Csrf-Token` header |
| 404 | `{"error": "not_found"}` | no such contest, or no such path under `/api` |
| 500 | `{"error": "server_error", "reference": "…"}` | a server error, logged with the reference |

## For operators

- Tokens are stored as their SHA-256 only (`api_token`, migration V75), with a prefix
  (`wlxj_` and 6 characters) that names them in the profile list and in log lines.
- A refused token is logged at WARN as `api_token_refused reason=… tokenId=…` (or the prefix
  for an unknown one), never its value. A denial is logged on `security.authz` with its
  reference and `auth=session` or `auth=token tokenId=…`.
- Revoking a token for a user (root) is not in the UI yet: set `revoked_at` on their row,
  e.g. `UPDATE api_token SET revoked_at = NOW(3) WHERE user_id = … AND revoked_at IS NULL`.
