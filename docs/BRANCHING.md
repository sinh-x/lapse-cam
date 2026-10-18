# Branching strategy

Use the **main → develop → feature** branch structure, with **all feature work merged into `main`**, not `develop`. This is not the Git Flow model where features merge into `develop` first.

| Branch | Role | Merge target / update source |
| --- | --- | --- |
| `main` | Authoritative integration branch for completed work. | Every feature pull request targets `main`. |
| `develop` | Long-lived development baseline synchronized from `main`. | Fast-forward from `main` after integration; no feature merges or direct work here. |
| `feature/<ticket-or-no-ticket>-<short-description>` | Short-lived branch for feature, fix, or documentation work. | Branch from up-to-date `develop`; merge into `main`. |

`main` is the source of truth, not a promise of production readiness. The Android prototype's outstanding checks and known issues remain documented in [the README](../README.md) and [follow-up work](FOLLOW_UP.md).

## Start work

Synchronize `develop` from `main` before creating a feature branch:

```sh
git switch main
# If a remote is configured, first fetch and fast-forward main from its upstream.
git switch develop
git merge --ff-only main
git switch -c feature/TTL-001-android-compatibility
```

Keep feature branches focused. Commit and validate the change, then open a pull request with **base/target `main`**, never `develop`. Incorporate newer `main` changes into the feature branch as needed and rerun relevant checks.

## Integrate and synchronize

When using local Git instead of a pull request, merge the validated feature into `main` while preserving its history:

```sh
git switch main
git merge --no-ff feature/TTL-001-android-compatibility
# Synchronize the development baseline only after integration into main.
git switch develop
git merge --ff-only main
git switch main
```

If `--ff-only` fails, stop and inspect the divergence; do not force-reset `develop` or discard commits. After confirming integration and any required publication, the feature branch may be deleted with `git branch -d <branch>`.

## Current repository setup

- `feature/no-ticket-android-timelapse` has been merged into `main` with a merge commit and retained for reference.
- The existing `develop` branch, previously at initialization, is synchronized to `main` after this documentation update.
- No remote is configured, so this setup is local only. Once a remote is configured, publish `main` and `develop`, keep local branches up to date, and select `main` as the target for every feature pull request.
- Files under `docs/sessions/` are historical evidence; their recorded branch states describe the original sessions, not current branch locations.
