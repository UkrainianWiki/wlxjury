# Login with Google and Wikimedia accounts

Besides the email (or wiki account) and password, jurors can log in with a Google account or a Wikimedia account. This only logs in to an **existing** jury account: organizers still add jurors, and a sign-in that matches no account is refused with "ask your contest organizer".

## How an account is found

The first time someone signs in with a Google or Wikimedia account, it is linked (table `user_identity`) to the one jury account that has:

- **Google:** the Google account's email, if Google says it is verified.
- **Wikimedia:** the Wikimedia username as the wiki account, in any case, with underscores or with a `User:` prefix. If there is none, the Wikimedia account's email, if the email is confirmed and the consumer may read it (see below).

After that, the link is by the provider's stable account id: changing the email at Google or renaming the Wikimedia account doesn't matter. Two jury accounts with the same wiki account are refused, never guessed between.

A juror whose Google email differs from the one the organizer entered can log in with the password and link the account themselves: their own edit page (the user name in the menu) has a **Linked accounts** section with **Link** and **Unlink**.

## Hosts

The session cookie belongs to the host the user is on, so the provider has to send the user back to that same host. The app builds the redirect URI from the request's host, but only for hosts listed in the config: AllowedHostsFilter is off, so the Host header can't be trusted otherwise. For each host and provider, register this redirect URI:

```
https://<host>/oauth/google/callback
https://<host>/oauth/wikimedia/callback
```

A login button appears only on hosts with credentials for that provider.

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
   - **Types of grants:** "User identity verification only, with access to real name and email address, no ability to read pages or act on a user's behalf." Without the email part, logins still work through the wiki account, but not through the email.
2. Wait for the consumer to be approved (this can take days). Until then only its owner can use it, which is enough for testing. A consumer proposed as "for use only by" its owner is approved at once.
3. Add the host's client:

```hocon
oauth.wikimedia.clients = [
  { host = "jury.wle.org.ua", clientId = ${?WLXJURY_WIKIMEDIA_WLE_CLIENT_ID}, clientSecret = ${?WLXJURY_WIKIMEDIA_WLE_CLIENT_SECRET} }
  { host = "jury.wlm.org.ua", clientId = ${?WLXJURY_WIKIMEDIA_WLM_CLIENT_ID}, clientSecret = ${?WLXJURY_WIKIMEDIA_WLM_CLIENT_SECRET} }
]
```

## Production

Put the `oauth` settings in `/etc/wlxjury-runtime/production.conf` and the variables in `runtime.env` (see `conf/wlxjury/*.example` and [deployment.md](deployment.md)). Secrets must stay `${?VARIABLE}` references: deployment refuses literal secrets in config files. Then restart the service. Nothing else changes; the `user_identity` table comes with migration V63.

## Local testing

Run the app with `-Doauth.scheme=http` and use `localhost:9000` as the host. Google accepts `http://localhost:9000/oauth/google/callback` as a redirect URI. For Wikimedia, propose an owner-only consumer with that callback.

## Implementation

- `controllers.OAuthController`: the login, link, callback and unlink actions. The sign-in's state, PKCE verifier and nonce wait in the signed Play session until the callback, which always drops them.
- `services.oauth`: the providers on top of the [Nimbus OAuth 2.0 SDK](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk).
  - `GoogleProvider` is OpenID Connect: discovery, plus an ID token checked against Google's keys, issuer, client and nonce.
  - `WikimediaProvider` is MediaWiki's OAuth 2.0 and reads `/oauth2/resource/profile`.
  - `IdentityLinker` finds the account.
- Logins put `user_id` in the session (`controllers.Secured`). Sessions from before that still work through `username`.
