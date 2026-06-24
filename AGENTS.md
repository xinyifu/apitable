# AGENTS.md

## Repository Notes

- This fork is for self-hosted deployments only.
- Push changes to `origin` (`xinyifu/apitable`) by default.
- Treat `upstream` (`apitable/apitable`) as read-only for fetch/pull sync; do not push to upstream.

## Branch Rules

- Use `develop` as the clean upstream sync baseline. Keep it close to `upstream/develop`; do not use it for normal feature work, experiments, release tags, or local validation commits.
- Use `codex/self-host-limits-permissions` as the primary self-hosted development branch. Put community limit changes, permission work, default-language changes, automation actions, local validation fixes, and other self-hosted feature work here.
- Use `codex/ghcr-selfhost-actions` as the clean publish/build branch for GitHub Actions image builds, release-oriented commits, GHCR publishing, tags, and synchronization pushes.
- Do not continue normal feature development directly on `codex/ghcr-selfhost-actions`; merge or cherry-pick reviewed work from `codex/self-host-limits-permissions` when preparing a release.
- When pushing work, push to `origin` and never to `upstream`.
