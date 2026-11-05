# WLX Jury Tool: a guide for administrators

This guide is for the admins of a contest in the WLX Jury Tool, and for root, the tool's own
administrators. It explains accounts and roles, how people are invited and sign in, and the
settings of a contest. Rounds and results are in the [organizer guide](organizer-guide.md). The
people you add use the [juror guide](juror-guide.md).

Labels in **bold** are the ones the tool shows in English. Whoever installs and runs the tool
(servers, mail, sign-in providers, backups) finds that in the operators' [deployment
guide](../deployment.md); this guide doesn't repeat it.

<a id="a-accounts"></a>
## Accounts and roles

<a id="a-one-account"></a>
### One account per person

A person has one account, found by its email address, whatever the number of contests. The
account can be a member of many contests, with a role in each. A person who is a juror in one
contest and an organizer in another signs in once, and switches contest with the contest menu at
the top of the page.

Accounts are made by invitation only. The tool has no sign-up page: the sign-in page says that
accounts are created by contest organizers.

<a id="a-roles"></a>
### Roles

A role belongs to a membership: one account, one contest.

| Role | What it can do |
|---|---|
| **Jury** | Votes in the rounds it is a juror of. |
| **Organizer** | Reads the statistics, the results of all jurors, the contest's files and the conflicts page. Changes nothing. |
| **Admin** | Everything an organizer does, and manages the contest: its people, rounds and files. |
| **Board**, **Audit committee**, **Staff** | No access to the contest's pages. These roles record that a person belongs to the contest, so that their own photos count as [conflicts of interest](organizer-guide.md#o-conflicts). |
| **No** | A member with no role: no access. |
| **Root** | Not a role of a contest: a property of the account (see [What only root can do](#a-root)). Root works in every contest without being a member. |

A person with no access role who opens a page gets the **Access denied** page, as the juror guide
describes.

<a id="a-current-contest"></a>
### The current contest

Each request runs in one contest, the **current contest**. It comes from the contest menu, and
when someone follows a link to a page of another contest they belong to, the tool switches to
that contest. Admins manage only the contests in which they are admins.

<a id="a-users"></a>
## People

<a id="a-users-page"></a>
### The users page

Open **Users** in the top bar. The page lists the members of the current contest: **Full name**,
**Wiki account**, **Email**, **Role**, and a pencil to edit. Next to a wiki account there may be
two buttons that write to the person on Commons, a message on their talk page and an email.

A badge **unverified** after a wiki account says that nobody has proved that the account
belongs to this person: anyone could have typed it. A badge **unverified** or **password
expired** after an email says that the person hasn't accepted an invitation yet, or that the old
password expired unused. The envelope button next to it sends an invitation again.

A member without a wiki account whose files could be conflicts of interest shows **no wiki
account, can't be checked for conflicts of interest**. Add their wiki account in their edit
page.

<img src="images/admin-users.png" alt="The top of the users page, with the members' badges" width="1280">

<a id="a-add-person"></a>
### Adding a person

At the bottom of the table, fill in the row: **Full name**, **Wiki account**, **Email**, the
**Role**, and press the button with the plus. A person needs an email or a wiki account.

- A **new** email gets a new account and an invitation by email, see [Invitations](#a-invitations).
- An email whose account **exists** and whose owner has proved it (they accepted an invitation
  or a mailed link) joins your contest with the role at once. Nothing is changed in the account
  itself.
- An account that hasn't proved its email, for example one made unverified by a shared link,
  gets an invitation instead, and the role applies when they accept it. A wiki account that
  belongs to such an account is not added: add the person by email.
- A person added with a wiki account only has no email. They can't sign in with a password; they
  can sign in with Wikimedia, see [Sign-in options](#a-sign-in).

The page tells you how many accounts were created, how many people joined and how many were
members already, and lists any invitations that could not be sent.

<a id="a-mass-import"></a>
### Adding many people

**Mass import** takes a list: entries separated by commas or new lines, each a Wikimedia
account, an email, or a name with an email in angle brackets:

```
Wiki Account 1, Wiki Account 2
mail1@example.org, mail2@example.org
Name1 Surname1 <mail1@example.org>
```

Choose the role for all of them. They are added as in [Adding a person](#a-add-person).

<a id="a-edit-person"></a>
### Changing a person

The pencil opens the page **Edit user**. An admin can change, for members of the current contest:

- the **Role** in this contest;
- the **Full name** and the **Wiki account** (a valid wiki name that no other account has; it
  is marked unverified until the person confirms it by linking their Wikimedia account);
- the **Email**, only while the person is still an invitee who hasn't accepted. They are invited
  again at the new address. For a person who has a password, only the person can change their
  email, on their own profile, or root.

An account that also works in other contests can't be changed by an admin of one of them: the
admin may change the person's role in their own contest only, unless they administer all of its
contests. Nobody sets another person's password: see [Passwords](#a-passwords).

<a id="a-invitations"></a>
## Invitations

<a id="a-invitation-email"></a>
### The invitation email

A new member gets an email, **Invitation to the … jury**, with a link that is valid for a few
days. The contest's **greeting message** can replace its standard text, see [The
greeting](#a-greeting). The person opens the link, chooses a password on the page **Set your
password**, and is signed in. The email address is their login. The role applies once the
invitation is accepted. See [Accepting your invitation](juror-guide.md#accepting-your-invitation)
in the juror guide.

<a id="a-pending"></a>
### The invitations list

Under the users table, **Invitations** lists each person's latest invitation into the contest,
if it is still live or is from the last 30 days: the **Email**, the **Role**, how it was **Sent**
(**By email** or **Link to share**) and its **Status** (**active**, **used**, **expired** or
**revoked**, with the time). So you can see whether the person got it. For a live one:

- **Send the invitation again** mails a new link;
- **Create a link to share** makes a link to pass on yourself, see [Sharing a
  link](#a-share-link), where you may;
- **Cancel** cancels it: the link stops working.

A person whose link expired asks you to send it again.

<img src="images/admin-invitations.png" alt="The list of invitations, with a link to share and its status" width="1280">

<a id="a-share-link"></a>
### Sharing a link instead of mailing it

When an email doesn't arrive, or you prefer to pass the link on yourself, you can create a link
to set a password and give it to the person directly, for example in a message.

**Where.** For an invitation not yet accepted, press **Create a link to share** on the users page
(in the list **Invitations**, or next to the person). For a reset of a password,
open the person's edit page and press **Create a link to share**.

**Who can create one.**

- **Root**, for any account.
- **A contest admin**, in their contest: for a member or a person invited into it, and for a
  reset only for an active member. The account must not be root or the admin themselves, must
  have only the roles **Jury** or **Organizer**, and every contest it belongs to, has worked in
  or is invited into must be a contest the admin administers.
- Not for a person who has no email and signs in with Wikimedia, where Wikimedia sign-in is
  offered: the page says they sign in with their wiki account. A person with neither an email nor
  a wiki account can't get a link either.

**How it works.**

- The link is **single-use**: it works once, for the account it was made for. It expires like a
  mailed one, and the page shows **Expires**.
- It is **shown once**, on the page **A link to set the password**, with a **Copy** button.
  Reloading or resubmitting that page creates a new link and the one you copied stops working. If
  you lose a link, create another.
- Creating a link makes the account's earlier unused links of the same kind stop working, the
  mailed ones too. So an account has one active link at a time.
- The status of each link is shown as **active**, **used**, **expired** or **revoked**, with
  whether it was sent **By email** or is a **Link to share**: in the invitations list, and on
  the person's edit page.
- Mailing the invitation or the reset stays available.

**What using the link does.** The person chooses a password and is signed in. For a reset, it
signs out every other session of the account, and the old password works until the link is used.
A shared link doesn't prove that the person owns the email, because anyone who has it could use
it. So using it **marks the email and the wiki account unverified**: if another contest then adds
the person, they get an invitation by email first, and a Google sign-in by that address doesn't
link until they accept a mailed link. Accepting a mailed link verifies the address again.

Treat the link like a password: anyone who has it can set the account's password until it is
used. Pass it on to the person only, and not in a public place.

<img src="images/admin-share-link.png" alt="The page that shows a link to share once, with its Copy button" width="1280">

<a id="a-greeting"></a>
### The greeting

**Edit greeting message** on the users page opens the contest's greeting: the text a new member
receives. Tick **Send greeting to new users** to use it for new members, except that board,
audit committee and staff members don't get it. The page lists the variables you can put in the
text, and shows a preview for each member. Without a greeting the tool sends its standard
invitation.

<a id="a-sign-in"></a>
## Signing in

<a id="a-password"></a>
### Password

A person signs in with their email address (or, if you set one, their wiki user name) and their
password. **Keep me signed in** is ticked by default. Passwords that haven't been used for a long
time expire: the person then needs an invitation or a link to set a new one. Their
[juror guide](juror-guide.md#signing-in-with-your-password) describes the page.

<a id="a-passwords"></a>
### Passwords: nobody sets another person's

An admin never sets someone else's password, and can't see it. A person sets their own, with a
link that only the owner of the email address receives, or that you pass on
([Sharing a link](#a-share-link)). The old password keeps working until the new one is set.

Root can mail a link to set a new password from a person's edit page (**Reset password**). An
admin invites an unverified member again instead, which does the same. Root and contest admins
can also create a link to share for a reset, under the conditions in [Sharing a
link](#a-share-link).

<a id="a-wikimedia-google"></a>
### Wikimedia and Google

If the tool's operators set them up, the sign-in page has **Sign in with Wikimedia** and **Sign
in with Google**. They sign a person in to the account they already have, and never create one.
The tool finds the account by the wiki account (for Wikimedia) or the verified email (for
Google, for Gmail and Google Workspace addresses only). A person can also **Link** the provider on
their own profile under **Linked accounts**, and **Unlink** it, which signs out the account's
other sessions.

When the same wiki account is on several jury accounts, the person chooses their account on the
page **Choose your jury account**, with its password or a link mailed to it. See [the juror
guide](juror-guide.md#when-one-wiki-name-has-several-jury-accounts). If you change a person's
email or wiki account, the tool unlinks their Google and Wikimedia accounts, as the account may
be changing hands.

Operators set the providers up in the [OAuth reference](../oauth.md).

<a id="a-sessions"></a>
### Sessions

A signed-in session ends after a period without use, and at the latest a fixed time after
sign-in; the operators set both (30 days idle and 365 days at most, unless changed). A person can
end all their sessions on their profile: **Sign out everywhere** under **Sessions**. Changing a
password does the same, except in the browser it was changed in. Root can end the sessions of any
account with **End sessions** on its edit page.

<a id="a-api-tokens"></a>
### API tokens

Only root sees the API tokens section on their profile, for command-line tools: almost nothing
can be done through the API yet, and only by root, so nobody else can create a token for now. A
token acts with its owner's roles, so it must be kept secret. Ending sessions doesn't revoke
tokens: the owner revokes them on the profile, and root on any account's tokens page.

<a id="a-contest"></a>
## The contest

<a id="a-contest-settings"></a>
### Contest settings

A contest has a name, a country, a year, the Commons category or page its files come from (**Images**),
a **monument id template** for monuments contests, a greeting, and its rounds. Root creates and
changes the contests on the **Campaigns** page. Admins of a contest set up its
people ([People](#a-users)), its greeting, its [files](organizer-guide.md#o-import-files) and its
[rounds](organizer-guide.md#o-rounds-list).

<a id="a-more-than-one-admin"></a>
### Several admins

Make each co-organizer an **Admin** in the users page. An admin of the contest can do all that
this guide says for it. It can't change contests it isn't an admin of, nor root accounts.

<a id="a-root"></a>
## What only root can do

- Create contests, and import them from a Commons list, on **Campaigns**, and change a contest's
  settings there.
- See **Users** across all contests, and make an account root, only if its owner has verified
  its email.
- Edit an account that is in contests the admin doesn't administer; change the email of an account
  that has a password, through a link mailed to the new address.
- Send **Reset password** links and use **End sessions** on any account, and see and revoke
  any account's API tokens.
- Run **Check every round's images**, on the rounds list: it checks that every round's list of
  files matches its votes, and repairs any drift.
- Work in any contest without being a member of it.

The first root account is made when the tool is first set up; that is in the [deployment
guide](../deployment.md).
