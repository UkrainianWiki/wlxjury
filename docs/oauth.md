# Sign-in with Google and Wikimedia accounts

Besides the email (or wiki account) and password, jurors can sign in with a Google account or a Wikimedia account. It is **off** until configured (below). It only signs in to an **existing** jury account, by S2's rules (invitations only): organizers still invite jurors, and a sign-in that matches no account is refused ("ask your contest organizer to invite you"); no account is ever created by it.

Port notes (what changed from the `oauth-login` branch, what is open): [plans/2026-11-03-oauth-login-port.md](plans/2026-11-03-oauth-login-port.md).

## How an account is found

After the first sign-in, an identity is linked (table `user_identity`, V87) by the provider's stable account id (`sub`): changing the email at Google or renaming the Wikimedia account doesn't matter. A deleted account is never signed in to.

The first time, it is linked to the one jury account that its provider names, **only if nobody else may hold that account** ([security/secure-coding.md](security/secure-coding.md) §14). Root accounts follow the same rules.

- **by email**, if the provider is authoritative for it: Wikimedia's confirmed email (if the consumer may read it; like Google's flag for other domains, it is a one-time confirmation, accepted because the wiki confirmed the address to the account holder and a change of address needs a new confirmation there); Google's verified email only for a Gmail address (`@gmail.com`, `@googlemail.com`) or a Google Workspace account (an `hd` claim). For any other address Google's flag says nothing about who holds the address now, so a Google sign-in neither matches nor verifies it: the person accepts the mailed invitation, links Google on their profile, and signs in with it after that. The account must have a **verified** email (an accepted invitation or link), or **no password and a live invitation or password link** to that same address: the sign-in then accepts that link as following it would (the email verified, pending invitations of that address applied, other links revoked, every session and API token ended), without setting a password, in one transaction.
- **by wiki account** (the Wikimedia username, with underscores, a `User:` prefix or a lower-case first letter, but otherwise exactly as MediaWiki has it: `FooBar` is not `Foobar`, `José` is not `Jose`): only a wiki-only placeholder (no password, no email) that no Wikimedia account was ever linked to (none now, its wiki account never verified). Otherwise a renamed Wikimedia account's old name, registered by someone else, could open it.

Otherwise the sign-in is refused with a message saying what to do (accept the invitation, sign in with the password and link from the profile, or ask the organizer). Two jury accounts that match are refused: one with the wiki account and another with the email; two with the same wiki account go to an **account chooser**: the user picks theirs; a placeholder is linked at once, any other account after its password or a link mailed to its verified address (root accounts are never listed). A sign-in by a Wikimedia account whose username is the account's wiki account marks that wiki account verified; one whose username moved away from the verified wiki account clears the verification.

A signed-in user links or unlinks accounts on their own profile (the user name in the menu), **Linked accounts**. The Link buttons carry the page's nonce, and an account with a password and an unverified email may not link. Linking a Wikimedia account verifies the user's wiki account if it is that username; if the user has none, a page asks before setting it (the name comes from the linked account, never a form), refused if another jury account has it; another, organizer-set name stays (the conflict-of-interest filter reads it): the organizer changes it. A changed name ends the account's other sessions. **Unlinking** ends every other session of the account and leaves a tombstone (`user_identity.unlinked_at`): a first sign-in never links that provider account again, by email or wiki account; only its holder's own profile does. The chooser lists an account only if a session can name it (an email, or a wiki account of its own); an email-less placeholder whose wiki account is shared is "ask your organizer". Accepting an invitation or password link, and root's "reset password", unlink every identity of the account; so does an admin's change of an invitee's email, and an admin's change of another's wiki account unlinks its Wikimedia accounts. A root link or sign-in is logged at WARN.

## Sessions

A Google or Wikimedia sign-in makes the same session as the password (S3): the account's id and `auth_version`, the sign-in time, the 1-year absolute and 1-month idle limits, kept signed in (the sign-in form's default; the buttons are links, without the checkbox). "Sign out everywhere", a password or email change and root's "End sessions" end it too. A link's callback must come back to the same, still valid session that started it.

## Security notes

- The state (against login CSRF), the PKCE verifier and the OpenID Connect nonce wait in the signed session between leaving for the provider and its callback, which always drops them; a callback without its flow, with another state, of another provider or after `oauth.flowTimeout` is refused before any code is traded.
- The starts (`GET /oauth/<provider>/login`, `/link`) are links: a navigation to the provider, so the CSP's `form-action 'self'` stays as it is (a POST form leaving for the provider would need the providers' origins in `form-action`). They change only the session's flow keys. Unlinking is a POST with the CSRF token.
- `returnTo` passes `Secured.safeLocalPath` at the start and again at the callback.
- The callback answers `no-store` and `Referrer-Policy: no-referrer` (its URL holds the code), and redirects at once. Users see message keys only; `security.account` log lines carry account ids, our own step names and the provider's error code at most (no email, username or provider text).
- At most `oauth.maxConcurrent` exchanges with the providers run at once (the callback is public); another callback is refused (`oauth.busy`).

## Hosts

The session cookie belongs to the host the user is on, so the provider has to send the user back to that same host. The app builds the redirect URI from the request's host, but only for a host listed for that provider here (exactly, lower-cased); the host must also be in `wlxjury.allowedHosts` (`AllowedHostsFilter`). For each host and provider, register this redirect URI:

```
https://<host>/oauth/google/callback
https://<host>/oauth/wikimedia/callback
```

A sign-in button appears only on hosts with credentials for that provider.

## Google

1. In the [Google Cloud console](https://console.cloud.google.com/), create or pick a project. Under **Google Auth Platform**, set up branding (app name, support email). Scopes: only `openid`, `email` and `profile`, which need no Google review.
2. **Clients → Create client → Web application.** Add the Google redirect URI of every host to **Authorized redirect URIs**. One client serves all hosts.
3. Set the client ID and secret as environment variables, and list the hosts:

```hocon
oauth.google {
  clientId = ${?WLXJURY_GOOGLE_CLIENT_ID}
  clientSecret = ${?WLXJURY_GOOGLE_CLIENT_SECRET}
  hosts = ["jury.wle.org.ua", "jury.wlm.org.ua"]
}
```

## Wikimedia

A Wikimedia OAuth consumer has a single callback URL, so each host needs its own consumer.

1. Propose an OAuth 2.0 consumer at [Special:OAuthConsumerRegistration/propose/oauth2](https://meta.wikimedia.org/wiki/Special:OAuthConsumerRegistration/propose/oauth2) on Meta-Wiki:
   - **OAuth "callback" URL:** the host's Wikimedia redirect URI. Leave "Allow consumer to specify a callback" unchecked.
   - **Client is confidential:** checked.
   - **Types of grants:** "User identity verification only, with access to real name and email address, no ability to read pages or act on a user's behalf." Without the email part, sign-ins still work through a verified wiki account, but not through the email.
2. Wait for the consumer to be approved (this can take days). Until then only its owner can use it, which is enough for testing. A consumer proposed as "for use only by" its owner is approved at once.
3. Add the host's client:

```hocon
oauth.wikimedia.clients = [
  { host = "jury.wle.org.ua", clientId = ${?WLXJURY_WIKIMEDIA_WLE_CLIENT_ID}, clientSecret = ${?WLXJURY_WIKIMEDIA_WLE_CLIENT_SECRET} }
  { host = "jury.wlm.org.ua", clientId = ${?WLXJURY_WIKIMEDIA_WLM_CLIENT_ID}, clientSecret = ${?WLXJURY_WIKIMEDIA_WLM_CLIENT_SECRET} }
]
```

## Production

Put the `oauth` settings in `/etc/wlxjury-runtime/production.conf` and the variables in `runtime.env` (see `conf/wlxjury/*.example` and [deployment.md](deployment.md)). Secrets must stay `${?VARIABLE}` references: deployment refuses literal secrets in config files. Then restart the service. The `user_identity` table comes with migration V87. The deployed template lives in `wlxjury-infra` (`production.conf.j2`): its `oauth` block and variables are a pre-deploy item there.

## Local testing

Run the app with `-Doauth.scheme=http`, use `localhost:9000` as the host and list it for the provider. Google accepts `http://localhost:9000/oauth/google/callback` as a redirect URI. For Wikimedia, propose an owner-only consumer with that callback. The specs never call the providers: `OAuthControllerSpec` and `OAuthRouterSpec` use fakes, `ProvidersSpec` runs the real providers against a local fake server.

## Implementation

- `controllers.OAuthController`: the login, link, callback and unlink actions; `controllers.LoginResults`: the session a sign-in makes, shared with `LoginController`.
- `services.oauth`: the providers on top of the [Nimbus OAuth 2.0 SDK](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk) (no pac4j, which S10 removed).
  - `GoogleProvider` is OpenID Connect: discovery (on the first sign-in), plus an ID token checked against Google's keys, issuer, client and nonce.
  - `WikimediaProvider` is MediaWiki's OAuth 2.0 and reads `/oauth2/resource/profile`.
  - `IdentityLinker` finds the account, by the rules above.
  - `OAuthSettings` reads the `oauth` block; `OAuthProviders` holds the providers and the exchange turns.
