# Reddit Keyword Filter for Morphe

A Morphe patch bundle with one patch, **Hide keyword content**, which hides Reddit feed posts by keywords,
communities, or accounts. Select it together with the official Morphe Patches source.

[Add this source to Morphe](https://morphe.software/add-source?github=bryceaspringfield/morphe-reddit-keyword-filter),
or add it by hand:

```text
https://github.com/bryceaspringfield/morphe-reddit-keyword-filter
```

## Use

1. In Morphe, keep the Morphe Patches source and add this one.
2. Patch Reddit with Morphe Patches' Reddit patches and **Hide keyword content** selected together.
3. In the patched app, open Settings > Morphe > Keyword filter > Keywords to hide, and enter one rule a line:

| Rule | Hides posts |
|---|---|
| `r/name` | in the community `name` |
| `u/name` | by the account `name` |
| `"phrase"` | containing the phrase as whole words, in the title, body text, community, or author |
| any other text | containing the text anywhere in the title, body text, community, or author |

Rules ignore letter case and apply as soon as they are saved.

## What it filters

- Every paged feed: Home, Popular, Latest, a community, and custom feeds, in card and classic views.
- The older post listings: search results and profiles.

The settings section is added to Morphe Patches' Reddit settings screen, so the Keyword filter section appears
only when Morphe Patches' Reddit patches are also selected.

## Supported versions

The same Reddit versions as Morphe Patches: 2026.10.0, 2026.24.0, and 2026.40.0, with 2026.42.0, 2026.43.0, and
2026.44.0 experimental. Tested on 2026.44.0 with Morphe Patches 2.1.0-dev.7.

## License

GPLv3, from the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template).
