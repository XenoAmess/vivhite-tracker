# Dependabot Optimization Notes — `XenoAmess/vivhite-tracker`

**Date:** 2026-07-23
**Scope:** Greenfield dependabot setup. The repo had no `dependabot.yml`, no
`auto-merge.yml`, no `MYTOKEN`, no branch protection, and `allow_auto_merge` was
`false`. This is the first run; it sets everything up correctly in one pass.

## What changed

### Files (commit `adfe068`)

| File | Change |
|---|---|
| `.github/dependabot.yml` | **new** — maven + github-actions, weekly Mon 04:00 `Asia/Shanghai`, no `groups`, `open-pull-requests-limit` 10 / 5, labels + commit prefixes |
| `.github/workflows/auto-merge.yml` | **new** — approve + `gh pr merge --auto --rebase` on patch/minor for both ecosystems and on major for `github_actions/*`; maven majors left for human review |
| `.github/workflows/android-ci.yml` | **edit** — `push: branches: [main, master, develop]` → `push: branches: [master]` to avoid running CI on every feature-branch push (Pitfall 4) |

### GitHub-side config (not in commit)

| Setting | Before | After |
|---|---|---|
| `allow_auto_merge` (repo) | `false` | **`true`** |
| Branch protection on `master` | absent (404) | **`strict: true`, `build` required, `enforce_admins: false`, `required_linear_history: true`, no review requirement** |
| Labels | only the 9 default issue labels | **+ `dependencies`, `android`, `github-actions`** |
| `MYTOKEN` secret (dependabot namespace) | absent | **set** — the admin OAuth token (`gho_*`, scopes include `workflow`), stored via `gh secret set MYTOKEN --app dependabot` |

## Decisions and why

1. **Maven majors → human review, github-actions majors → auto-merge.**
   Android major bumps (AGP, Kotlin, AndroidX, kotlinx-coroutines) often need
   code changes; an auto-merged major can break master silently. GitHub Actions
   majors are usually just runtime bumps, safe to merge. The auto-merge
   workflow's `if` chain reflects this.

2. **`MYTOKEN` = admin OAuth token in the *dependabot* namespace.**
   `GITHUB_TOKEN` cannot enable auto-merge on PRs that modify
   `.github/workflows/*.yml` (Pitfall 5), and `gh auth refresh -s workflow`
   hangs from a non-interactive shell. The shortcut `gh auth token` →
   `--app dependabot` works because XenoAmess is admin and the token already
   has `workflow` scope. Setting it under `--app dependabot` (not the default
   actions namespace) is required — without it, the workflow run sees an empty
   value and silently fails (Pitfall 16).

3. **Branch protection: strict + linear + admins bypass.**
   `strict: true` forces Dependabot's rebase before merge. `required_linear_history:
   true` keeps history clean (auto-merge uses `--rebase` anyway). `enforce_admins:
   false` lets the admin hot-fix in an emergency without bypassing GitHub's UI
   first. `required_pull_request_reviews: null` because auto-merge should not
   wait for a human review — it already waits for CI.

4. **No `groups:` block in `dependabot.yml`.**
   Grouping collapses N unrelated bumps into one PR, which is unreviewable
   when it fails CI and unreviewable when it succeeds. One PR per dependency
   per cycle keeps each diff small. `commit-message.prefix` + `labels` keep
   the higher PR count scannable.

5. **`open-pull-requests-limit`: 10 for maven, 5 for github-actions.**
   Maven has more deps that drift independently; 10 lets Dependabot batch a
   full weekly cycle's worth without throttling. GitHub Actions has fewer
   deps, so 5 is enough.

6. **Schedule: Monday 04:00 `Asia/Shanghai`.**
   Weekly rather than daily — produces one batched bump per ecosystem per
   week, not a constant drip. Monday 04:00 CST is early morning, before work
   hours; the user is in `Asia/Shanghai`.

## Pitfalls guarded against

| Pitfall | How |
|---|---|
| 1 (major never merges) | Auto-merge policy includes `semver-major` for github_actions |
| 2 (auto-merge skips CI) | `android-ci.yml` runs on `pull_request:` |
| 3 (check name mismatch) | No matrix, so the check is named `build` exactly — matches the protection entry |
| 4 (CI runs twice per PR) | `push` scoped to `master` only |
| 5 (GITHUB_TOKEN can't enable auto-merge) | `MYTOKEN` is a PAT-class OAuth token with `workflow` scope, used for approve + merge steps |
| 6 (allow_auto_merge off) | Now `true` |
| 6b (no branch protection) | Protection configured with required status check |
| 8 (login mismatch) | `if:` line matches both `app/dependabot` and `dependabot[bot]` |
| 16 (wrong secret namespace) | Secret set via `--app dependabot`, verified with `gh secret list --app dependabot` |
| 17 (missing labels) | All 3 labels created *before* pushing `dependabot.yml`, so the first weekly cycle does not drop PRs |

## What still needs to be verified once a dependabot PR arrives

These could not be verified at setup time because the repo had no dependabot
PRs. Re-run them after the first weekly cycle (Monday ~04:00 CST):

```bash
# After first dependabot PR opens:
gh pr view <N> --json statusCheckRollup

# Confirm MYTOKEN is being read (not empty):
gh run view <id> --log-failed | grep -F 'GH_TOKEN'

# Confirm the Enable auto-merge step is success, not skipped:
gh run view <id> --json steps --jq '.[] | select(.name == "Enable auto-merge") | .conclusion'
```

Expected: `GH_TOKEN: ***` (masked, not empty); `Enable auto-merge` conclusion =
`success`.

If the auto-merge step is `skipped`, that's Pitfall 6 (allow_auto_merge off) —
already guarded against here, so this would indicate the repo flag was reset
by an admin action after this setup.

If the auto-merge step is `failure` with the
`OAuth App cannot create or update workflow` GraphQL error, the token in
`MYTOKEN` lacks `workflow` scope — re-run
`gh secret set MYTOKEN --app dependabot --body "$(gh auth token)"`.

## First-run observability

After this commit, GitHub auto-generated a `Dependabot Updates` workflow from
the new `dependabot.yml`. The next weekly run (next Monday 04:00 CST, or
sooner if a fresh bump is published) will start opening PRs. Watch:

- Actions tab → `Dependabot Updates` (the meta-workflow that opens PRs)
- Actions tab → `Dependabot auto-merge` (the workflow that approves and merges)
- PRs tab → labeled `dependencies` + `android` (maven) or `dependencies` + `github-actions` (gha)