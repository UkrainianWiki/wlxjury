<a id="a-accounts"></a>
# Accounts and roles

<a id="a-one-account"></a>
## One account per person

A person has one account, found by its email address, whatever the number of contests. The
account can be a member of many contests, with a role in each. A person who is a juror in one
contest and an organizer in another signs in once, and switches contest with the contest menu at
the top of the page.

Accounts are made by invitation only. The tool has no sign-up page: the sign-in page says that
accounts are created by contest admins.

<a id="a-roles"></a>
## Roles

A role belongs to a membership: one account, one contest.

| Role | What it can do |
|---|---|
| **Juror** | Votes in the rounds it is a juror of, and sees only its own files there (a monument's files too, from a [monument's page](monuments-authors.md#o-monuments)). Doesn't open the contest's files page. |
| **Organizer** | Reads the statistics, the results of all jurors, the contest's files and the conflicts page, and may comment on the files of an open round. Changes nothing else. |
| **Admin** | Everything an organizer does, and manages the contest: its people, rounds and files. |
| **Board**, **Audit committee**, **Staff** | No access to the contest's pages. These roles record that a person belongs to the contest, so that their own photos count as [conflicts of interest](conflicts.md#o-conflicts). |
| **No** | A member with no role: no access. |
| **Root** | Not a role of a contest: a property of the account (see [What only root can do](root.md#a-root)). Root works in every contest without being a member. |

A person with no access role who opens a page gets the **Access denied** page, as [Common problems](troubleshooting.md#j-access-denied)
describes.

<a id="o-who-can-do-what"></a>
## Who can do what

The tool has these roles in a contest: **Juror**, **Organizer** and **Admin**. A person can have
a different role in each contest. Roles are explained [above](#a-roles).

| | Juror | Organizer | Admin |
|---|---|---|---|
| Vote | on their own files | no | no |
| Read the statistics, the results of all jurors, the file list and the CSV | no | yes | yes |
| See the contest's files | no (their own files, in their rounds) | yes | yes |
| See the conflicts page | no | yes | yes |
| See the files' authors and the authors list | no | yes | yes |
| Comment on a file of an open round | their own files | any file of the round | any file of the round |
| Read the [comments report](comments.md#o-comments-report) | of their own files | yes | yes |
| Download the comments CSV, show one person's comments | no | yes | yes |
| Set up rounds and files, start and stop rounds, pause jurors, disqualify files, manage people | no | no | yes |

**Root**, the tool's own administrators, can do what an admin can, in every contest.

If a button or page is missing for you, your role in the current contest probably doesn't allow
it. The contest menu at the top of the page shows which contest you are in.

<a id="a-current-contest"></a>
## The current contest

Each request runs in one contest, the **current contest**. It comes from the contest menu, and
when someone follows a link to a page of another contest they belong to, the tool switches to
that contest. Admins manage only the contests in which they are admins.

The contest menu groups a person's contests by **series** (see [Series](contest.md#a-series)): under
"Series: Wiki Loves Monuments in Nepal" this year's and last year's contest of that series, the
newest first; the older contests of series under **Earlier years**; the contests in no series
under **Other contests**. Someone with only one contest has no menu. A **retired** contest isn't
in the menu, and its members can't open it.
