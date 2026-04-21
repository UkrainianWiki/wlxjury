Security Review: XSS Prevention Mechanisms & Vulnerabilities

Vuln 1: SQL Injection — app/db/scalikejdbc/rewrite/ImageDbNew.scala

- Severity: High
- Confidence: 0.92
- Category: sql_injection
- Description: The where() method builds SQL predicates using raw Scala string interpolation for the regions parameter, which originates directly from the :region URL path segment (String type). For a single short
  region: s"i.monument_id like '${regions.head}%'". For multiple regions: regions.map(r => s"'$r'").mkString(", ") inside an IN(...) clause. Neither branch uses ScalikeJDBC's sqls"" interpolator (which             
  auto-parameterizes), nor is there any allowlist validation — only a trivial != "all" filter upstream in GalleryController.
- Exploit Scenario: An authenticated juror or organizer requests:                                                                                                                                                    
  GET /gallery/round/1/user/1/region/uk' UNION SELECT username,password,3,4 FROM users-- /page/1
- The generated SQL becomes:                                                                                                                                                                                         
  WHERE m.adm0 in ('uk' UNION SELECT username,password,3,4 FROM users--')
- This exfiltrates all usernames and password hashes from the users table in the response JSON.
- Recommendation: Replace string-interpolation SQL building in where() with ScalikeJDBC's sqls"" interpolator throughout. For the LIKE case: sqls"i.monument_id like ${regions.head + "%"}". For the IN case, use    
  ScalikeJDBC's sqls.in(column, values) helper. The where() method should return SQLSyntax instead of String.

  ---                                                                                                                                                                                                                  
Vuln 2: Hardcoded Application Secret Key — conf/application.conf:8

- Severity: High
- Confidence: 0.85
- Category: hardcoded_credentials
- Description: play.http.secret.key = "changeme" is the literal value in the production config with no environment-variable override (${?...} substitution). The systemd unit files (wlxjury-blue.service,           
  wlxjury-green.service) load EnvironmentFile=/etc/wlxjury/blue.env which contains only DB credentials and port settings — no APPLICATION_SECRET or PLAY_HTTP_SECRET_KEY is set. Play 3.x emits a warning but does not
  refuse to start. The secret is used to HMAC-sign session cookies.
- Exploit Scenario: Any attacker aware that the app uses the default secret can forge a valid Play session cookie with username set to a known admin email address (obtainable from a public contest page). The      
  Secured.userFromRequest() method trusts the session value unconditionally, granting full admin access with no credential.
- Recommendation: Change conf/application.conf to: play.http.secret.key = ${?PLAY_HTTP_SECRET_KEY} and add PLAY_HTTP_SECRET_KEY=<strong-random-64-hex-chars> to /etc/wlxjury/blue.env and green.env. Generate with:
  openssl rand -hex 32.

  ---                                                                                                                                                                                                                  
Vuln 3: Unsalted SHA-1 Password Hashing — app/db/scalikejdbc/User.scala:201

- Severity: High
- Confidence: 0.95
- Category: weak_crypto
- Description: All user passwords are stored as unsalted SHA-1: def hash(user: User, password: String): String = sha1(password) — the user argument is accepted but never used. SHA-1 is a fast general-purpose hash
  function (not a KDF), produces identical output for identical passwords (no per-user salt), and has no work factor. The login(), user-creation, and password-reset flows all call this function. The database has a  
  single password varchar(255) column with no salt column in any of the 50 Flyway migrations.
- Exploit Scenario: An attacker who obtains the users table (via the SQL injection above, a DB backup leak, or any future injection) can run hashcat -m 100 -a 0 hashes.txt rockyou.txt and crack the majority of    
  passwords in minutes on commodity hardware. Since there is no salt, a single precomputed rainbow table lookup covers all users sharing the same password.
- Recommendation: Migrate to bcrypt (e.g., jBCrypt) or Argon2 (argon2-jvm). Add a password_hash column using bcrypt with work factor 12 (≈300 ms/hash). Implement a transparent migration: on successful SHA-1 login,
  re-hash with bcrypt and store; after a grace period, invalidate accounts still on SHA-1.

  ---                                                                                                                                                                                                                  
Vuln 4 (Medium): Production Credentials Exposed via MYSQL_PWD Environment Variable — scripts/run-with-prod-dump.sh

- Severity: Medium
- Confidence: 0.95
- Category: credential_exposure
- Description: The script passes the production DB password via MYSQL_PWD environment variable to mysqldump and docker run commands. On Linux, any local user can read another process's environment while it is
  running via /proc/<pid>/environ. The script runs on the developer's workstation where a prod dump is being taken, potentially on a shared machine.
- Exploit Scenario: On a shared development server, while run-with-prod-dump.sh executes, a co-located user runs: cat /proc/$(pgrep -f mysqldump)/environ | tr '\0' '\n' | grep MYSQL_PWD and obtains the production
  database password.
- Recommendation: Pass credentials via a temporary --defaults-extra-file (with chmod 600) instead of MYSQL_PWD. The temp file can be created inline and deleted immediately after the dump: printf
  '[client]\npassword=%s\n' "$PROD_PASSWORD" > /tmp/.my$$.cnf; chmod 600 /tmp/.my$$.cnf; mysqldump --defaults-extra-file=/tmp/.my$$.cnf ...; rm /tmp/.my$$.cnf.

---

## XSS Prevention: Current State & Remediation Plan

### Mechanisms In Use

#### 1. Twirl Template Auto-Escaping
- **Status:** Active (full coverage for normal interpolations)
- **Where:** All `.scala.html` templates — framework default
- **How it works:** Every `@expr` is wrapped in `HtmlFormat.escape()`, encoding `<`, `>`, `"`, `'`, `&`. This is the primary XSS defence.
- **Gaps:** Bypassed wherever `@Html(...)` is used explicitly (see section below).

#### 2. CSRF Protection
- **Status:** Active in production
- **Where:** Play's built-in `CSRFFilter` (default filter chain); `helper.CSRF.formField` inserted in all forms: `contest_images.scala.html`, `greetingTemplate.scala.html`, `large/commentsView.scala.html`, `users.scala.html`
- **Note:** Disabled in test config (`test/resources/application.conf`) — correct for test isolation; prod is protected.

#### 3. REST API Content-Type
- **Status:** Active
- **Where:** `app/api/Api.scala` — Tapir sets `Content-Type: application/json` automatically on all REST endpoints. No HTML responses from the API layer.

#### 4. Image Proxy Content-Type
- **Status:** Active
- **Where:** `app/controllers/ImageProxyController.scala:53,61` — always responds `.as("image/jpeg")`. No MIME-sniffing risk.

#### 5. Content Security Policy Header
- **Status:** Partial — **severely weakened**
- **Where:** `conf/application.conf:96`
- **Current value:** `default-src 'self' 'unsafe-eval' 'unsafe-inline' https://maxcdn.bootstrapcdn.com ...; img-src 'self' https://*; media-src 'self' https://*`
- **Problem:** `'unsafe-inline'` and `'unsafe-eval'` together neutralize the entire defence value of CSP against XSS. Any inline `<script>` or `eval()` call is permitted.

---

### Missing Protections

#### A. `X-Content-Type-Options: nosniff`
- **Status:** Not set anywhere — not in Apache vhosts, not in Play config
- **Risk:** Without this header, old browsers may MIME-sniff a response with wrong Content-Type as HTML, enabling content-injection attacks.
- **Fix:** Add to Apache vhosts or Play's `SecurityHeadersFilter` config (see remediation plan).

#### B. `X-Frame-Options: SAMEORIGIN`
- **Status:** Not set anywhere
- **Risk:** The app can be embedded in an `<iframe>` on a third-party site, enabling clickjacking attacks against juror voting and admin actions.
- **Fix:** Add `X-Frame-Options: SAMEORIGIN` header.

#### C. `X-XSS-Protection: 1; mode=block`
- **Status:** Not set anywhere
- **Risk:** Legacy header for IE/old Chrome, but harmless to add and still useful as defence-in-depth for older browsers used by international jurors.

#### D. Session Cookie `secure` Flag
- **Status:** Not explicitly configured (Play default is `false`)
- **Where:** `conf/application.conf` — no `play.http.session.secure` key
- **Risk:** Session cookie is transmitted over HTTP connections even though Apache redirects HTTP→HTTPS. A network attacker on the same segment can steal the session cookie via HTTP.
- **Fix:** Add `play.http.session.secure = true` to `conf/application.conf`.

#### E. Strengthened Content Security Policy
- **Status:** Needs rework
- **Risk:** The current CSP with `'unsafe-inline'` provides no protection against XSS.
- **Fix:** See remediation plan below.

---

### Vulnerability: JS String Injection via Image Filename — `app/views/large/preloadImages.scala.html:4`

- **Severity:** Medium
- **Confidence:** 0.82
- **Category:** Stored XSS (JS context injection)

**Description:**

The preload template injects image URLs into a JavaScript array using single-quote string delimiters, bypassing Twirl auto-escaping with `@Html(...)`:

```scala
// app/views/large/preloadImages.scala.html:4
preload([@Html(preload.map(i => s"'${controllers.Global.resizeTo(i.image, ...)}'" ).mkString(","))]);
```

`Global.resizeTo` delegates to `legacyThumbUrl` (active when `useLegacyThumbUrl = true`, line 27), which constructs the URL from `info.url` (raw Wikimedia Commons URL from DB) with no JS-escaping or URL-encoding of the filename component:

```scala
// app/controllers/Global.scala:68-84
url.substring(lastSlash + 1)  // raw filename, no encoding
```

Wikimedia Commons filenames routinely contain apostrophes (e.g., `File:Côte d'Ivoire.jpg`, `File:O'Brien.jpg`). Such filenames are stored as-is in the `images.title`/`url` columns and pass through into the JS string literal unescaped, breaking out of the single-quote delimiter:

```javascript
// Generated output with apostrophe in filename:
preload(['https://.../thumb/.../100px-O'Brien.jpg']);
//                                       ^ breaks JS string
```

A malicious Commons contributor who can get their image included in a contest round could craft a filename like `File:x', alert(document.cookie), '` to inject arbitrary JavaScript executed when any juror views the large image page.

**Fix:**

Replace the raw URL injection with a properly JS-escaped version:

```scala
// Option A: use Play's Json.toJson to produce a valid JS string literal
@Html(preload.map(i => play.api.libs.json.Json.stringify(
  play.api.libs.json.JsString(controllers.Global.resizeTo(i.image, size))
)).mkString(","))
```

Or escape using Apache Commons Text `StringEscapeUtils.escapeEcmaScript(url)` before interpolating. The `@Html(...)` wrapper can remain since the escaping happens on the content, not the HTML context.

---

### Remediation Plan

#### Step 1: Fix JS string injection in preloadImages template (High Priority)

**File:** `app/views/large/preloadImages.scala.html`

Replace the raw URL string injection with JSON-serialized string literals:
```scala
@Html(preload.map(i =>
  play.api.libs.json.Json.stringify(
    play.api.libs.json.JsString(controllers.Global.resizeTo(i.image, size))
  )
).mkString(","))
```
Play's JSON serializer correctly escapes backslashes, quotes, and all non-ASCII characters.

#### Step 2: Add security headers via Play SecurityHeadersFilter (Medium Priority)

**File:** `conf/application.conf`

```hocon
play.filters.headers {
  contentSecurityPolicy = "default-src 'self'; script-src 'self' https://maxcdn.bootstrapcdn.com; style-src 'self' 'unsafe-inline' https://maxcdn.bootstrapcdn.com; img-src 'self' https:; media-src 'self' https:; font-src 'self' https://maxcdn.bootstrapcdn.com; frame-ancestors 'none'"
  frameOptions = "SAMEORIGIN"
  xssProtection = "1; mode=block"
  contentTypeOptions = "nosniff"
  referrerPolicy = "strict-origin-when-cross-origin"
}
```

Note: Removing `'unsafe-inline'` from `script-src` requires moving inline `<script>` blocks in templates to external `.js` files. Audit `app/views/` for inline scripts before enabling.

#### Step 3: Set session cookie secure flag (Medium Priority)

**File:** `conf/application.conf`

```hocon
play.http.session.secure = true
play.http.session.httpOnly = true  # already default true, make it explicit
```

#### Step 4: Audit `@Html(...)` usage in templates (Low Priority)

Run:
```bash
grep -r "@Html(" app/views/
```

Review each occurrence and verify:
- The content is not user-controlled, OR
- The content is HTML-escaped before being wrapped in `Html()`

Current known `@Html(...)` usages with trusted data (safe):
- `monuments.scala.html` / `monumentInfo.scala.html`: monument wiki links (data from Wikipedia — not direct user HTTP input; acceptable risk)
- `preloadImages.scala.html:4`: **fix required** (Step 1)
- Pagination helpers: typically safe (numeric/encoded URLs)

#### Step 5: Add `Strict-Transport-Security` header in Apache (Low Priority)

**File:** `conf/apache/0002-wlxjury.conf` (and slot-specific vhosts)

```apache
Header always set Strict-Transport-Security "max-age=31536000; includeSubDomains"
Header always set X-Content-Type-Options "nosniff"
Header always set X-Frame-Options "SAMEORIGIN"
Header always set Referrer-Policy "strict-origin-when-cross-origin"
```

Add to the HTTPS `<VirtualHost *:443>` block. These can be set at the Apache layer instead of (or in addition to) Play's filter.