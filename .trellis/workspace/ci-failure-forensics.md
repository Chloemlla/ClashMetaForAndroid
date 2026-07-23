# CI failure forensics (parent + gh)

## Latest main failure

| Field | Value |
|-------|-------|
| run | 30042272415 |
| title | chore(docs): refresh update_notes.json for HEAD identity |
| job | Verify → JVM unit tests |
| root cause | AAPT: `update_notes_title` invalid string — unescaped apostrophe in `What's New` (`Invalid unicode escape sequence`) |
| fixed | yes (string rewritten to `What is New`) |

## Historical failures (feature branch, superseded)

| run | cause | still open on main? |
|-----|-------|---------------------|
| 29938391226 | compile MainApplication LumenCrash installSafely | no |
| 29937463359 | cold-start LumenCrash | no |
| older lumen-crash version resolver | script JSON parse | no (resolver fixed) |

## Open PR

None (`gh pr list --state open` empty). Merged PRs #1–#6 only.
