# Flow Designer

Visual editor for payment state machines. Create, edit, and manage payment flows through a
drag-and-drop canvas, with Git-based version control — every change is a commit, no
database involved.

## Tech Stack

- **Backend:** Spring Boot 4.1.1, Java 25 (LTS), JGit, Spring Security OAuth2
- **Frontend:** React 19.2, React Flow 12.11, dagre (auto-layout), Vite 8
- **Storage:** Git repository (no database) — THUB data plus canvas layouts under `.flowdesigner/`
- **Auth:** GitLab or GitHub OAuth2, whichever the administrator selects

## Run with Docker

```bash
cp .env.example .env      # fill in the repository URL and OAuth2 credentials
docker compose up -d
```

Or without compose:

```bash
docker run -d --name flow-designer -p 8080:8080 \
  --env-file .env \
  -v flowdesigner-data:/var/lib/flowdesigner \
  ghcr.io/xtreme-uz/flow-designer:1.0.0
```

**Mount the volume.** `/var/lib/flowdesigner` holds each user's workspace clone, and a
workspace can hold work that is saved but not yet committed or pushed. Without the volume
that work goes with the container.

`GET /actuator/health` answers whether the instance is up (status only, no details) — the
image's own `HEALTHCHECK` uses it, and so can an orchestrator's probes.

The application terminates plain HTTP on 8080. Put it behind a reverse proxy that
terminates TLS and forwards `X-Forwarded-*`; the OAuth2 redirect URI must be the public
HTTPS address.

## Configuration

### The flows repository

`GIT_REMOTE_URL` points at the repository flows are written to — a repository of its own,
not this source repository. **A brand-new, completely empty one is fine:** the first save
creates `GIT_DEFAULT_BRANCH` on it and publishes the `THUB/` tree. Until then the flow list
and the branch list are legitimately empty. Nothing needs to be seeded by hand.

### Choosing the login provider

`AUTH_PROVIDER` is `gitlab` or `github`. Only the selected one is registered, and the
application refuses to start when its client id or secret is missing, naming the setting to
fix. Both support a self-hosted instance — `GITLAB_BASE_URL`, or `GITHUB_BASE_URL` for
GitHub Enterprise Server (whose API lives on the same host under `/api/v3`).

Register an OAuth application on that host with the redirect URI
`https://<your-address>/login/oauth2/code/<gitlab|github>`, and copy the client id and
secret into the environment.

Pick the provider that hosts `GIT_REMOTE_URL` if you want per-user pushes:
`GIT_USE_USER_CREDENTIALS=true` pushes with the signed-in user's OAuth2 token, which only
works when the login provider hosts the repository — a GitLab token is not accepted by
GitHub, or the other way round. It also needs a scope that grants repository write access
(GitLab `write_repository`, GitHub `repo`); the read-only default cannot push. With a
mismatch, or without that scope, leave it off and push with the `GIT_USERNAME` /
`GIT_TOKEN` service account.

### Who may sign in

OAuth2 only proves that someone holds an account on the provider — on a public host, that
is everyone. `AUTH_ALLOWED_USERNAMES` and `AUTH_ALLOWED_EMAIL_DOMAINS` narrow it to the
people who should be editing payment flows. Both empty means anyone the provider
authenticates, which the application logs as a warning at startup.

### Environment variables

| Variable                    | Default                                        | Description                                                              |
|-----------------------------|------------------------------------------------|--------------------------------------------------------------------------|
| `GIT_REMOTE_URL`            | *(required)*                                   | The flows repository                                                     |
| `GIT_DEFAULT_BRANCH`        | `main`                                         | Branch flows are read from, and the one the first commit creates         |
| `GIT_USERNAME`              |                                                | Service account for clone, pull and push                                 |
| `GIT_TOKEN`                 |                                                | Its token                                                                |
| `GIT_SSH_KEY_PATH`          |                                                | SSH private key, as an alternative to the two above                      |
| `GIT_MAIN_REPO_PATH`        | `/var/lib/flowdesigner/main` in the image      | Read-only clone of the default branch                                    |
| `GIT_WORKSPACES_PATH`       | `/var/lib/flowdesigner/workspaces` in the image| Per-user clones — put this on a volume                                   |
| `GIT_USE_USER_CREDENTIALS`  | `false`                                        | Push as the signed-in user instead of the service account                |
| `AUTH_PROVIDER`             | `gitlab`                                       | `gitlab` or `github`                                                     |
| `AUTH_ALLOWED_USERNAMES`    |                                                | Comma-separated; empty means anyone the provider authenticates           |
| `AUTH_ALLOWED_EMAIL_DOMAINS`|                                                | Comma-separated                                                          |
| `GITLAB_CLIENT_ID`          | *(when `AUTH_PROVIDER=gitlab`)*                | OAuth application id                                                     |
| `GITLAB_CLIENT_SECRET`      | *(when `AUTH_PROVIDER=gitlab`)*                | Its secret                                                               |
| `GITLAB_BASE_URL`           | `https://gitlab.com`                           | Self-hosted instance                                                     |
| `GITLAB_SCOPE`              | `read_user`                                    | Add `write_repository` to push as the user                               |
| `GITLAB_REDIRECT_URL`       | `{baseUrl}/login/oauth2/code/{registrationId}` | Override when the public address differs from what the server sees       |
| `GITHUB_CLIENT_ID`          | *(when `AUTH_PROVIDER=github`)*                | OAuth application id                                                     |
| `GITHUB_CLIENT_SECRET`      | *(when `AUTH_PROVIDER=github`)*                | Its secret                                                               |
| `GITHUB_BASE_URL`           | `https://github.com`                           | GitHub Enterprise Server                                                 |
| `GITHUB_SCOPE`              | `read:user,user:email`                         | Add `repo` to push as the user                                           |
| `GITHUB_REDIRECT_URL`       | `{baseUrl}/login/oauth2/code/{registrationId}` | Redirect URI override                                                    |
| `KEYSTORE_FILE`             |                                                | PKCS12 keystore for HTTPS (dev profile only)                             |
| `KEYSTORE_PASS`             |                                                | Its password                                                             |

`GITLAB_API_URL` and `GITHUB_API_URL` override where user info is read from, for hosts that
serve their API from somewhere other than the default.

## Local Development

### Prerequisites

- Java 25+ (LTS)
- Maven 3.9+
- Node.js 22+ (the build downloads its own Node 24 LTS)
- Git
- An OAuth application on GitLab or GitHub (client id + secret)

### 1. Register the OAuth application (one-time)

- **GitLab:** `https://gitlab.com/-/profile/applications` (or the same path on your
  instance) — redirect URI `https://flowdesigner.local:8443/login/oauth2/code/gitlab`,
  scope `read_user`
- **GitHub:** *Settings → Developer settings → OAuth Apps* — callback URL
  `https://flowdesigner.local:8443/login/oauth2/code/github`

### 2. Configure run-dev.sh

```bash
# Set these values in run-dev.sh:
GIT_REMOTE_URL=<the flows repository>
GIT_USERNAME=<git username>
GIT_TOKEN=<git personal access token>
AUTH_PROVIDER=gitlab                 # or github
GITLAB_CLIENT_ID=<application id>    # or GITHUB_CLIENT_ID
GITLAB_CLIENT_SECRET=<secret>        # or GITHUB_CLIENT_SECRET
```

### 3. Start the backend

```bash
./run-dev.sh
```

`run-dev.sh` adds the `/etc/hosts` entry for `flowdesigner.local` and generates a
self-signed certificate in `~/.flowdesigner/dev-keystore.p12` — OAuth2 needs HTTPS.

### 4. Start the frontend (separate terminal)

```bash
cd frontend && npm install && npm run dev
```

### 5. Open in browser

- **Backend:** `https://flowdesigner.local:8443`
- **Frontend (hot-reload):** `http://localhost:5173`

> On first visit, the browser will warn about the self-signed certificate — click
> **Advanced** → **Proceed**.

### Usage

1. Sign in with the configured provider
2. Switch to a feature branch (e.g. `feature/my-flow`)
3. Click **New** to create a flow
4. Drag nodes from the toolbar onto the canvas
5. Click a node to configure its status ID and action
6. Connect nodes by dragging between handles; click an edge to set the result type
7. **Save** → **Commit** → **Push**

## Build from source

```bash
mvn clean package
java -jar target/flow-designer-1.0.0.jar
```

The frontend is bundled into the Spring Boot JAR automatically.

## Tests

```bash
mvn test                   # 183 backend (JUnit) + 39 frontend (vitest)
cd frontend && npm test    # frontend only
```

## Architecture

```
Browser (React Flow) --> Spring Boot REST API --> Local Git repo --> Remote Git
```

- Flows are stored in **THUB format** — one JSON file per table (`THUB/{Table}-data.json`),
  the layout a configuration deployer reads to load flows into a payment hub database
- **THUB data is the single source of truth**; the canvas is derived from it
- Node positions and each flow's node list live beside it in `.flowdesigner/flows/{id}.json`,
  outside the tree the deployer reads. dagre positions anything not saved there
- Each user works on isolated feature branches in a workspace clone of their own
- Auth via an OAuth2 session cookie; `UserIdHeaderFilter` injects X-User-Id from the principal

### Known limits

- **One instance.** Workspaces, their locks and the session store are per-process, so
  running two replicas against the same flows repository is not supported yet.
- Deploying flows into a payment hub is the configuration deployer's job; Flow Designer
  writes the files it reads.

## Releases

Tagging `v*` builds and publishes the image to
`ghcr.io/xtreme-uz/flow-designer` and attaches the JAR to a GitHub release.
See [CHANGELOG.md](CHANGELOG.md).

## License

MIT — see [LICENSE](LICENSE).
