# Changelog

Notable changes to Flow Designer. Versions follow [semantic versioning](https://semver.org).

## [1.0.0] — 2026-09-09

First release. The editor, its Git storage and its authentication are complete enough to
run against a real flows repository.

### Editor

- Drag-and-drop canvas for payment state machines: initial, status and final nodes,
  transitions with result types, per-node action configuration and timeouts
- Flow metadata: description, version, component and the audit trail
- Node positions and each flow's node list persist in `.flowdesigner/flows/{id}.json`, kept
  out of the `THUB/` tree the configuration deployer reads. dagre lays out anything not
  saved there, so flows created before layouts existed still open sensibly
- A status a flow owns but has not wired to anything survives a reload — THUB has no
  per-flow status table, so the node list is what makes it a flow's own

### Storage

- THUB configurator format: five shared data files, records keyed `R_{...}`, written with
  sorted keys so a change shows as a minimal diff
- Statuses are shared between flows; actions, transitions and assignments belong to one
  flow, matched by the record's `flowtypeid` rather than by key prefix
- Writes go to a temp file and move into place, so a failed write cannot truncate a data file
- Saving a flow with a blank status description leaves the shared one alone

### Git

- Per-user workspace clones with a lock of their own, so concurrent saves and commits on a
  workspace cannot interleave
- Commit with optimistic locking (`expectedVersion`), push and pull report rejection and
  conflict as 409 rather than failing silently
- A pull is refused while managed files are dirty; untracked strays are left alone
- Idle workspaces are cleaned up, but never one holding uncommitted or unpushed work
- An empty flows repository is a valid target: the first commit creates the configured
  default branch, and the main clone adopts it as soon as it is published
- `GIT_USE_USER_CREDENTIALS` pushes with the signed-in user's OAuth2 token instead of the
  service account, presenting it under the username each host expects

### Authentication

- OAuth2 login through GitLab or GitHub, selected with `AUTH_PROVIDER`; only the selected
  provider is registered, and a missing client id fails startup with the setting to fix
- Self-hosted GitLab and GitHub Enterprise Server are supported
- Login allowlist by username or email domain, applied before a session exists
- CSRF protection, session cookie, no tokens in browser storage

### Operations

- Docker image published to `ghcr.io/xtreme-uz/flow-designer`, running as an unprivileged
  user with the Git clones on a volume
- `GET /actuator/health` for container and orchestrator probes — status only
- CI runs the backend and frontend suites plus the image build on every pull request

[1.0.0]: https://github.com/xtreme-uz/flow-designer/releases/tag/v1.0.0
