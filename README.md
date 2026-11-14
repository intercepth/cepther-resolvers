# cepther-resolvers

Declarative "how to find the real download link on this page" definitions
for Cepther's File Downloads feature.

This repo holds **data only** -- a JSON manifest describing, per file-locker
host, where on its landing page the real file address lives (a direct link
matching a pattern, or a follow-up form to submit). It never holds, and will
never hold, executable code. The app fetches `manifest.json` on its own
schedule, validates it strictly (size-capped, every field checked against a
known shape, the whole file rejected if anything is off), and uses it only
to decide which link or form field on an already-fetched page is the real
file -- the same one-GET-per-step engine the app already ships with for its
built-in resolvers, just told where to look by data instead of by code
shipped in the app itself.

## manifest.json

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

- `schema_version` -- bumped only if the shape of entries changes in a way
  older app versions can't read; the app refuses a schema version it
  doesn't understand rather than guessing.
- `hosts` -- one entry per landing-page host. `kind` selects which of the
  app's two built-in lookup strategies to run:
  - `direct_link`: scan the page's own `<a href>` tags for one whose host
    matches `link_host_pattern`.
  - `form_follow`: GET `first_url_template` (with `{id}` filled from
    `id_pattern` matched against the original URL), then find a `<form>`
    matching `form_match` and follow its action + fields.

Entries here are for hosts whose real file is served as a plain link or a
plain form submission once the landing page is read -- the same two shapes
Cepther's own built-in MediaFire and Google Drive support already use.
Anything that needs more than that (a real script run, a second kind of
follow-up, on-page decryption) stays as a feature built into the app itself,
not something this manifest can express.

## Contributing an entry

Open a PR adding one object to `hosts` in `manifest.json`. Keep entries
minimal and specific to one host. The app validates strictly and ignores
(not crashes on) any entry it doesn't understand, so a bad PR can't break
anyone's running app -- at worst that one host's entry is skipped until
it's fixed.
