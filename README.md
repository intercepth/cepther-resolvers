# cepther-resolvers

Declarative "how to find the real download link on this page" definitions
for Cepther's File Downloads feature.

This repo holds **data only** -- a JSON manifest describing, per file-locker
host, where on its landing page the real file address lives (a direct link
matching a pattern, or a follow-up form to submit). It never holds, and will
never hold, executable code of any kind -- no scripts, no eval'd strings, no
shell commands, nothing. The app fetches `manifest.json` once a day,
validates it strictly (size-capped, every field checked against a known
shape, the whole file rejected if the top-level shape is off), and uses it
only to decide which link or form field on an already-fetched page is the
real file -- the same one-GET-per-step engine the app already ships with for
its two built-in resolvers (MediaFire, Google Drive), just told where to
look by data instead of by code shipped inside the app.

If a site needs more than "find this link" or "follow this form" -- a
script actually has to run, or the file needs decrypting after download
(like Mega.nz) -- that's a real feature built into the app itself, not
something this manifest can express. This repo is only for the common case:
a landing page that hides the real file behind one extra click or form.

## manifest.json shape

```json
{
  "schema_version": 1,
  "updated": "2026-11-14",
  "hosts": [
    {
      "host": "mediafire.com",
      "kind": "direct_link",
      "link_host_pattern": "^download\d*\.mediafire\.com$"
    },
    {
      "host": "drive.google.com",
      "kind": "form_follow",
      "id_pattern": "(?:/file/d/|[?&]id=)([A-Za-z0-9_-]{10,})",
      "first_url_template": "https://drive.google.com/uc?export=download&id={id}",
      "form_match": {"any_field": "confirm", "action_contains": "download"}
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `schema_version` | Always `1` right now. Only bumped if the shape of an entry changes in a way an older app version can't read; the app refuses a schema version it doesn't understand rather than guessing at it. |
| `updated` | Plain `YYYY-MM-DD` of your last edit. Not read by the app -- it's for humans skimming the file/PR history. |
| `hosts` | A list, one object per landing-page host. |

Every entry needs `host` (the landing-page domain, no `www.`, no scheme)
and `kind`. `kind` is one of:

### `direct_link`

> "The real file is a plain link somewhere on this page."

- `link_host_pattern` (required) -- a regex. The app scans every `<a href>`
  on the landing page and returns the first one whose *host* (not the whole
  URL) matches this pattern. Anchor your pattern (`^...$`) so it can't
  accidentally match more than you mean.

### `form_follow`

> "The real file needs one extra confirm step."

- `id_pattern` (required) -- a regex with one capture group, matched
  against the *original* URL the user pasted, to pull out the file's id.
- `first_url_template` (required) -- a URL with a literal `{id}` in it,
  filled in from `id_pattern`'s capture. The app GETs this URL.
- `form_match` (required, object) -- how to recognise the right `<form>` on
  the page that URL returns: `any_field` (a field name that must be present
  on the form) and/or `action_contains` (a substring the form's `action`
  must contain). The app then submits that form (GET, its own fields,
  nothing added or changed) and returns the resulting address.

An entry that doesn't parse, or uses a `kind` this app version doesn't
know, is **skipped**, not fatal -- the rest of the file still loads. Keep
regexes specific: a pattern that matches too much is as useless as one that
matches too little, and a loose `link_host_pattern` could in principle point
the app at the wrong link on a page that happens to have one.

## Adding a new site -- step by step

You don't need to touch Cepther's own source code for this, only this repo.

1. **Find a real share/download link for the site** you want to add (any
   normal public link someone would paste, not an account-only one --
   anything behind a login isn't something this manifest can help with).
2. **Open that link in a plain browser with its devtools open**, and watch
   what happens when you click through to the actual file:
   - If the real download is just a normal link somewhere on the landing
     page (view page source, or check the Network tab for the address the
     eventual file comes from) -- that's `direct_link`. Note the *host* that
     real download link is served from.
   - If clicking the button submits a form, or the browser is sent to a
     second "click here to confirm" page first -- that's `form_follow`.
     Note the id in the original URL, the address of that confirm page, and
     the form's field names/action on it.
3. **Write one object** for `hosts` in `manifest.json` following the shapes
   above. Keep it to exactly what's needed -- don't add fields a `kind`
   doesn't use.
4. **Test it against the real site yourself** before opening a PR: paste
   your own link into Cepther with a local build of this manifest (or just
   reason through your regex against a few real example URLs/pages by
   hand) and confirm it actually finds the real file, not just "a" link.
5. **Open a PR** adding only your new object to the `hosts` list. One site
   per PR, please -- it's easier to review and easier to revert alone if a
   site changes its page shape later and the entry needs fixing.
6. Bump `updated` to the date of your PR.

A bad PR can't break anyone's running app: the app validates every entry
before using it, an invalid one is simply skipped, and the two sites
Cepther ships with built in (MediaFire, Google Drive) never depend on this
file at all.

## What won't be accepted

- Anything that isn't plain data in the shapes above -- no scripts, no
  "just eval this", no field that ends up in a shell command.
- Sites that need a login to reach the file.
- Sites that need more than one follow-up page (`form_follow` only follows
  one confirm step).
- A new `kind` that requires more than "find a link" or "follow a form" --
  open an issue describing the site instead, so it can be considered as a
  real built-in feature if it's common enough to be worth it.
