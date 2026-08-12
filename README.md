# Flow Designer

Visual editor for payment state machines. Create, edit, and manage payment flows through a drag-and-drop canvas, with Git-based version control — every change is a commit, no database involved.

## Tech Stack

- **Backend:** Spring Boot 4.0.2, Java 21, JGit, Spring Security OAuth2
- **Frontend:** React 19.1, React Flow 12.10, dagre (auto-layout), Vite
- **Storage:** Git repository (no database)
- **Auth:** GitLab OAuth2 (gitlab.com or a self-hosted instance)

## Local Development

### Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 18+
- Git
- GitLab OAuth App credentials (Client ID + Secret)

### 1. Create a GitLab OAuth App (one-time)

Go to `https://gitlab.com/-/profile/applications` (or `/-/profile/applications` on your
self-hosted instance) and create an application:

- **Name:** Flow Designer Dev
- **Redirect URI:** `https://flowdesigner.local:8443/login/oauth2/code/gitlab`
- **Scopes:** `read_user`

Copy the generated **Application ID** and **Secret** into `run-dev.sh`.

### 2. Configure run-dev.sh

```bash
# Set these values in run-dev.sh:
GIT_REMOTE_URL=<your-git-remote-url>
GIT_USERNAME=<your-git-username>
GIT_TOKEN=<your-git-personal-access-token>
GITLAB_CLIENT_ID=<your-application-id>
GITLAB_CLIENT_SECRET=<your-secret>
```

### 3. Start the backend

```bash
./run-dev.sh
```

On first run, the script automatically:
- Adds `flowdesigner.local → 127.0.0.1` to `/etc/hosts` (requires sudo)
- Generates a self-signed SSL certificate at `~/.flowdesigner/dev-keystore.p12`

### 4. Start the frontend (separate terminal)

```bash
cd frontend && npm install && npm run dev
```

### 5. Open in browser

- **Backend:** `https://flowdesigner.local:8443`
- **Frontend (hot-reload):** `http://localhost:5173`

> On first visit, the browser will warn about the self-signed certificate — click **Advanced** → **Proceed**.

### Usage

1. Click **Sign in with GitLab**
2. Authenticate on GitLab
3. Switch to a feature branch (e.g. `feature/my-flow`)
4. Click **New** to create a flow
5. Drag nodes from the toolbar onto the canvas
6. Click a node to configure its status ID and action
7. Connect nodes by dragging between handles; click an edge to set the result type
8. **Save** → **Commit** → **Push**

## Production Build

```bash
mvn clean package
java -jar target/flow-designer-0.0.1-SNAPSHOT.jar
```

The frontend is bundled into the Spring Boot JAR automatically.

## Tests

```bash
mvn test   # 103 tests
```

## Architecture

```
Browser (React Flow) --> Spring Boot REST API --> Local Git repo --> Remote Git
```

- Flows are stored in **THUB format** — one JSON file per table (`THUB/{Table}-data.json`),
  the layout a configuration deployer reads to load flows into a payment hub database
- **THUB data is the single source of truth** — no separate React Flow JSON stored
- Node positions are computed by dagre auto-layout on every load
- Each user works on isolated feature branches; merging requires a PR
- Auth via GitLab OAuth2 session cookie; `UserIdHeaderFilter` injects X-User-Id from principal

## Environment Variables

| Variable               | Default                                        | Description                         |
|------------------------|------------------------------------------------|-------------------------------------|
| `GIT_REMOTE_URL`       | *(required)*                                   | Remote Git repository URL           |
| `GIT_DEFAULT_BRANCH`   | `main`                                         | Default branch name                 |
| `GIT_USERNAME`         |                                                | Git HTTP auth username              |
| `GIT_TOKEN`            |                                                | Git HTTP auth token                 |
| `GIT_SSH_KEY_PATH`     |                                                | Path to SSH private key             |
| `GITLAB_BASE_URL`      | `https://gitlab.com`                           | GitLab instance for OAuth2 login    |
| `GITLAB_CLIENT_ID`     | *(required)*                                   | GitLab OAuth App Client ID          |
| `GITLAB_CLIENT_SECRET` | *(required)*                                   | GitLab OAuth App Client Secret      |
| `GITLAB_REDIRECT_URL`  | `{baseUrl}/login/oauth2/code/{registrationId}` | OAuth2 redirect URI override        |
| `KEYSTORE_FILE`        |                                                | SSL keystore path (dev profile)     |
| `KEYSTORE_PASS`        |                                                | SSL keystore password (dev profile) |

## Using a Different Git Host

Repository access (clone, commit, push) goes through JGit over HTTPS or SSH, so any Git
host works out of the box — point `GIT_REMOTE_URL` at it and supply either
`GIT_USERNAME`/`GIT_TOKEN` or `GIT_SSH_KEY_PATH`.

Only *login* is provider-specific. GitLab is configured by default; GitHub, Bitbucket and
others use the same OAuth2 authorization-code flow, so enabling one means adding a
registration in `application.yml` (see the commented example there). No Java changes are
needed — `OAuth2UserAttributes` resolves username, display name, avatar and email through
the aliases each provider uses.

## License

MIT — see [LICENSE](LICENSE).
