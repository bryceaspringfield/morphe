# Reddit Keyword Filter for Morphe

A Morphe patch bundle with one patch, **Hide keyword content**. It hides Reddit posts and comments by keyword,
community, account, flair, post type, score, and number of comments. It can also hide the Inbox tab, sidebar rows,
and the sidebar button's badge. Select it together with the official Morphe Patches source.

[Add this source to Morphe](https://morphe.software/add-source?github=bryceaspringfield/morphe-reddit-keyword-filter),
or add it by hand:

```text
https://github.com/bryceaspringfield/morphe-reddit-keyword-filter
```

## Use

1. In Morphe, keep the Morphe Patches source and add this one.
2. Patch Reddit with Morphe Patches' Reddit patches and **Hide keyword content** selected together.
3. In the patched app, open Settings > Morphe. The settings are in four sections named Keyword filter.

Settings apply as soon as they are saved.

### Keyword filter: posts

| Setting | Effect |
|---|---|
| Keywords to hide | Rules, one a line (table below) |
| Keywords apply to | The fields keyword rules read: post title, post text, flair, poster, community, and link domain (title and text by default) |
| Text-only, photo, video, repost (crosspost), and 18+ posts | Off, On (hide these posts), or Only (show only these posts), for each type |
| Score (upvotes) | A lower bound, an upper bound, or both; posts outside the range are hidden |
| Number of comments | The same, for the number of comments |
| Hide communities by topic | Hides communities on a fixed list for each topic (Zionism, Nazism and white supremacy, fascism, and incels), plus your own list |
| Where post filters apply | Home, Popular, News, community pages, search results, profiles, and other feeds (custom feeds and Latest) |

| Rule | Hides posts |
|---|---|
| `r/name` | in the community `name` |
| `u/name` | by the account `name` |
| `flair:Meme` | with the flair Meme |
| `!flair:Meme` | without the flair Meme |
| `"phrase"` | containing the phrase as whole words |
| any other text | containing the text anywhere |

Start a rule with communities and a colon to limit it to them, for example `r/pics: cat` or
`r/news, r/worldnews: !flair:Politics`. A rule without communities applies everywhere. Rules ignore letter case.
A translated post is checked in both its original language and the translation, including a translation shown after
the feed loads.

### Keyword filter: comments

| Setting | Effect |
|---|---|
| Comment keywords to hide | Rules for comment text and authors (the same forms as for posts); replies under a hidden comment are hidden too |
| Comment score (upvotes) | A lower bound, an upper bound, or both |

### Keyword filter: navigation

| Setting | Effect |
|---|---|
| Hide the Inbox tab | Removes the Inbox tab from the bottom bar |
| Hide sidebar rows | Popular, News, Latest, Discover communities, Start a community, Custom feeds, and Recently visited |
| Hide the sidebar button's badge | Hides the orange dot on the sidebar (menu) button |

## Supported versions

The same Reddit versions as Morphe Patches: 2026.10.0, 2026.24.0, and 2026.40.0, with 2026.42.0, 2026.43.0, and
2026.44.0 experimental. Tested on 2026.44.0 with Morphe Patches 2.1.0-dev.7.

## License

GPLv3, from the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template).
