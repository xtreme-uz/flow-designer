# Flow Designer - Claude Code Guide

## What is this project?

A visual editor for payment state machines. Users create payment flows (nodes + edges) in a React canvas and save them to Git in THUB configurator format — one JSON file per table, the layout a configuration deployer reads to load flows into a payment hub database. THUB data = single source of truth; React Flow canvas is derived via dagre auto-layout.

**Stack:** Spring Boot 4.0.2 (Java 21) + React 19.1 + React Flow 12.10 + dagre + JGit 7.5 + Vite 6.3

## Project Structure

```
flow-designer/
├── LICENSE                          # MIT
├── pom.xml                          # Maven config (builds frontend too, clean plugin)
├── run-dev.sh                       # Dev startup (HTTPS, GitLab OAuth2, self-signed cert)
├── frontend/                        # React app
│   ├── package.json                 # @xyflow/react, react 19.1, @dagrejs/dagre
│   ├── vite.config.js               # Proxy /api → localhost:8080
│   └── src/
│       ├── App.jsx                  # Main canvas + state (nodes, edges, flow CRUD)
│       ├── main.jsx                 # Entry (AuthProvider + AuthGate + WorkspaceProvider)
│       ├── index.css
│       ├── services/api.js          # All REST API calls (ThubDeploymentData format)
│       ├── utils/
│       │   ├── thubConverter.js     # THUB ↔ React Flow conversion
│       │   └── layoutUtils.js       # dagre auto-layout
│       ├── contexts/
│       │   ├── AuthContext.jsx      # GitLab OAuth2 session auth (/api/me, redirect login/logout)
│       │   ├── WorkspaceContext.jsx  # Branch/workspace state (userId from AuthContext)
│       │   └── ToastContext.jsx      # Toast notifications
│       └── components/
│           ├── LoginPage.jsx/.css   # Login screen (GitLab OAuth2 redirect button)
│           ├── Header.jsx/.css      # Branch selector, flow list, save, delete, user menu
│           ├── GitPanel.jsx/.css    # Commit/push/pull, git status
│           ├── Toolbar.jsx/.css     # Draggable node palette (Initial/Status/Final)
│           ├── NodeEditor.jsx/.css  # Node config panel (statusId combobox, action, timeouts)
│           ├── EdgeEditor.jsx/.css  # Edge config panel (result type, store as result)
│           ├── MetadataEditor.jsx/.css # Flow metadata modal (description, version, audit info)
│           ├── Toast.jsx/.css       # Toast notification component
│           └── nodes/
│               ├── InitialNode.jsx  # Green, start of flow
│               ├── StatusNode.jsx   # Blue, intermediate status
│               ├── FinalNode.jsx    # Red, end of flow
│               └── NodeStyles.css
└── src/
    ├── main/
    │   ├── java/uz/xtreme/flowdesigner/
    │   │   ├── FlowDesignerApplication.java
    │   │   ├── SpaController.java           # Forwards non-API routes to React
    │   │   ├── config/
    │   │   │   ├── GitProperties.java       # @ConfigurationProperties(prefix="app.git")
    │   │   │   ├── GitConfig.java           # Enables config + scheduling
    │   │   │   ├── SecurityConfig.java      # Spring Security + OAuth2 + CSRF config
    │   │   │   ├── UserIdHeaderFilter.java  # Injects X-User-Id from OAuth2 principal
    │   │   │   └── OAuth2UserAttributes.java # Provider-agnostic user attribute lookup
    │   │   ├── controller/
    │   │   │   ├── FlowController.java      # All REST endpoints
    │   │   │   └── AuthController.java      # GET /api/me (user info from OAuth2)
    │   │   ├── service/
    │   │   │   ├── git/
    │   │   │   │   ├── GitService.java      # Interface
    │   │   │   │   ├── GitServiceImpl.java  # JGit operations + workspace mgmt
    │   │   │   │   ├── WorkspaceInfo.java   # Workspace metadata record
    │   │   │   │   └── AuditInfo.java       # Commit audit trail
    │   │   │   └── flow/
    │   │   │       ├── FlowService.java     # Interface
    │   │   │       ├── FlowServiceImpl.java # Flow CRUD using THUB shared data files
    │   │   │       ├── ThubDataService.java # Interface for THUB data file I/O
    │   │   │       ├── ThubDataServiceImpl.java # Read/write shared THUB data files
    │   │   │       └── dto/
    │   │   │           ├── FlowSummary.java     # Flow listing summary
    │   │   │           └── thub/                # THUB deployment DTOs
    │   │   │               ├── ThubDeploymentData.java  # Complete flow data
    │   │   │               ├── ThubFlowType.java        # flow_type + audit info
    │   │   │               ├── ThubFlowStatus.java      # flow_status (shared)
    │   │   │               ├── ThubFlowStatusAction.java    # flow_status_action (has flowtypeid)
    │   │   │               ├── ThubFlowStatusTransition.java # flow_status_transition (has flowtypeid)
    │   │   │               └── ThubFlowAssignment.java      # flow_assignment
    │   │   └── exception/
    │   │       ├── GlobalExceptionHandler.java      # ProblemDetail responses
    │   │       ├── GitOperationException.java       # 500
    │   │       ├── GitVersionConflictException.java # 409
    │   │       ├── GitAuthenticationException.java  # 401
    │   │       ├── WorkspaceNotFoundException.java  # 404
    │   │       ├── FlowNotFoundException.java       # 404
    │   │       ├── FlowValidationException.java     # 400
    │   │       └── FlowStorageException.java        # 500
    │   └── resources/
    │       ├── application.yml          # Main config
    │       ├── application-dev.yml      # Dev profile (HTTPS, GitLab OAuth2)
    │       └── application-test.yml     # Test config (file:// git repo)
    └── test/java/uz/xtreme/flowdesigner/
        ├── FlowDesignerApplicationTests.java
        ├── controller/FlowControllerTest.java
        └── service/
            ├── git/GitServiceImplTest.java
            └── flow/
                ├── FlowServiceImplTest.java
                └── ThubDataServiceImplTest.java
```

## How to Run

### Development

Configure these variables in `run-dev.sh`:
```bash
GIT_REMOTE_URL=<your-git-remote-url>
GIT_USERNAME=<your-git-username>
GIT_TOKEN=<your-git-personal-access-token>
GITLAB_CLIENT_ID=<your-application-id>
GITLAB_CLIENT_SECRET=<your-secret>
```

Then run:
```bash
./run-dev.sh
# Opens at https://flowdesigner.local:8443/ (self-signed cert, HTTPS required for OAuth2)
```

`run-dev.sh` auto-creates: `/etc/hosts` entry for `flowdesigner.local`, self-signed SSL cert in `~/.flowdesigner/dev-keystore.p12`.

### Production Build

```bash
cd flow-designer
mvn clean package    # clean removes target/ + frontend/dist/ + src/main/resources/static/
java -jar target/flow-designer-0.0.1-SNAPSHOT.jar
```

### Run Tests

```bash
# All 103 tests
cd flow-designer
mvn test

# Specific test class
mvn test -Dtest=FlowControllerTest
mvn test -Dtest=FlowServiceImplTest
mvn test -Dtest=ThubDataServiceImplTest
mvn test -Dtest=GitServiceImplTest
```

## Storage Architecture: THUB Configurator Pattern

All flow data is stored in shared THUB data files (configurator-conf pattern).
THUB data = **single source of truth**. React Flow canvas is derived via dagre auto-layout.

### Git Repository Structure
```
THUB/
├── FlowType-metadata.json           # Static configurator metadata
├── FlowType-definition.json         # Table schema
├── FlowType-data.json               # { "dataEntities": { "R_{id}": {...} } }
├── FlowStatus-metadata.json
├── FlowStatus-definition.json
├── FlowStatus-data.json             # SHARED across all flows
├── FlowStatusAction-metadata.json
├── FlowStatusAction-definition.json
├── FlowStatusAction-data.json       # Keyed: R_{flowTypeId}_{statusId}
├── FlowStatusTransition-metadata.json
├── FlowStatusTransition-definition.json
├── FlowStatusTransition-data.json   # Keyed: R_{flowTypeId}_{statusId}_{nextStatusId}
├── FlowAssignment-metadata.json
├── FlowAssignment-definition.json
└── FlowAssignment-data.json         # Keyed: R_{assignmentId}
```

### R_ Key Format (data.json records)
| Table | Key Format | Example |
|-------|-----------|---------|
| FlowType | `R_{flowTypeId}` | `R_payment-flow` |
| FlowStatus | `R_{statusId}` | `R_ACCEPTED` |
| FlowStatusAction | `R_{flowTypeId}_{statusId}` | `R_payment-flow_ACCEPTED` |
| FlowStatusTransition | `R_{flowTypeId}_{statusId}_{nextStatusId}` | `R_payment-flow_ACCEPTED_SRC_DEBITED` |
| FlowAssignment | `R_{assignmentId}` | `R_card-processing` |

### Data Flow
```
Load: THUB data.json files → ThubDeploymentData → thubToReactFlow() → dagre layout → React Flow canvas
Save: React Flow canvas → reactFlowToThub() → ThubDeploymentData → merge into data.json files
```

### Key Rules
- **FlowStatus is SHARED** — multiple flows can use the same statuses; deleting a flow does NOT remove statuses
- **Actions/Transitions are per-flow** — filtered by `flowtypeid` field, replaced on save
- **metadata.json + definition.json are NOT managed by Flow Designer** — they belong in the configurator-conf repository

## REST API

### Headers
- `X-User-Id` - User identifier (default: "anonymous")
- `X-Branch` - Current branch (default: "main")

### Branches
```
GET  /api/branches                       # List all remote branches
```

### Main Branch (read-only, returns ThubDeploymentData)
```
GET  /api/flows                          # List all flows (FlowSummary[])
GET  /api/flows/{name}                   # Get flow (ThubDeploymentData)
GET  /api/statuses                       # List all statuses (ThubFlowStatus[])
POST /api/flows/validate                 # Validate flow (ThubDeploymentData → ValidationResponse)
```

### Workspace Management
```
POST   /api/workspaces                   # Create/get workspace
GET    /api/workspaces                   # List user's workspaces
GET    /api/workspaces/current           # Get current workspace info
DELETE /api/workspaces                   # Delete workspace
```

### Workspace Status & Flow Operations (all use ThubDeploymentData)
```
GET    /api/workspaces/statuses          # List all statuses (ThubFlowStatus[])
GET    /api/workspaces/flows             # List workspace flows (FlowSummary[])
GET    /api/workspaces/flows/{name}      # Get flow → ThubDeploymentData
POST   /api/workspaces/flows             # Create flow → { flowTypeId, deploymentData }
PUT    /api/workspaces/flows/{name}      # Update flow → { deploymentData }
DELETE /api/workspaces/flows/{name}      # Delete flow
POST   /api/workspaces/flows/{name}/rename   # Rename flow
```

### Git Operations
```
POST /api/workspaces/commit              # Commit changes (stages THUB/ directory)
POST /api/workspaces/push                # Push to remote
POST /api/workspaces/pull                # Pull from remote
GET  /api/workspaces/status              # Git status
POST /api/workspaces/branch              # Create new branch
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `GIT_REMOTE_URL` | (empty) | Remote git repo URL |
| `GIT_MAIN_REPO_PATH` | `/tmp/app/flows-repo/main` | Local main repo clone |
| `GIT_WORKSPACES_PATH` | `/tmp/app/flows-repo/workspaces` | User workspace directory |
| `GIT_DEFAULT_BRANCH` | `main` | Default branch name |
| `GIT_USERNAME` | (empty) | Git HTTP auth username |
| `GIT_TOKEN` | (empty) | Git HTTP auth token |
| `GIT_SSH_KEY_PATH` | (empty) | Path to SSH key |
| `GITLAB_CLIENT_ID` | (empty) | GitLab OAuth2 application ID |
| `GITLAB_CLIENT_SECRET` | (empty) | GitLab OAuth2 secret |
| `GITLAB_BASE_URL` | `https://gitlab.com` | GitLab instance for OAuth2 login (self-hosted supported) |
| `GITLAB_REDIRECT_URL` | `{baseUrl}/login/oauth2/code/{registrationId}` | OAuth2 redirect URI override |
| `KEYSTORE_FILE` | (empty) | PKCS12 keystore for HTTPS (dev) |
| `KEYSTORE_PASS` | (empty) | Keystore password (dev) |

## Architecture

### THUB ↔ React Flow Conversion

| React Flow | THUB Data |
|------------|-----------|
| Node (initialNode/statusNode/finalNode) | FlowStatus + FlowStatusAction |
| Edge | FlowStatusTransition |
| Flow canvas | FlowType (initial/final status IDs) |
| Node action config | FlowStatusAction (module, action, timeouts) |
| Edge label | actionresulttypeids (comma-separated, multi-select) |
| Node positions | Computed by dagre (not stored) |

### Frontend Conversion (thubConverter.js)
- `thubToReactFlow(deploymentData)` → `{ nodes, edges }` with dagre positions
- `reactFlowToThub(nodes, edges, flowTypeId, existingFlowType)` → `ThubDeploymentData`

### Backend Services
- **ThubDataService** — reads/writes shared THUB data.json files (5 tables × 3 files)
- **FlowService** — business logic: list, get, save, delete, rename, validate flows
- **GitService** — JGit operations, workspace management, branch operations

### Server-Side Git
- Main branch: read-only, auto-pulled
- Feature branches: per-user workspace isolation
- Each workspace = separate local clone under `workspaces/{userId}/{branch}/`
- Concurrency: per-workspace ReentrantLock
- Scheduled main repo refresh every 5 min
- Post-push main repo refresh

### Auth
- **GitLab OAuth2** via Spring Security (`GITLAB_BASE_URL`, defaults to `gitlab.com`)
- Session cookie (JSESSIONID) — no localStorage
- `GET /api/me` → `{ username, name, avatarUrl, email }` from OAuth2 principal
- `UserIdHeaderFilter` injects X-User-Id header from OAuth2 principal (FlowController unchanged)
- CSRF protection via XSRF-TOKEN cookie (CookieCsrfTokenRepository)
- Dev profile: HTTPS via self-signed cert (`run-dev.sh`)

## Key Patterns

### Backend
- **DTOs**: Java records with `@JsonProperty` for lowercase THUB column names
- **ThubFlowType**: includes audit fields (createdBy, createdAt, lastModifiedBy, lastModifiedAt, version, component, categorization)
- **ThubFlowStatusAction/Transition**: include `flowtypeid` for filtering in shared data files
- **FlowServiceImpl.removeByFlowTypeId()**: removes map entries by `R_{flowTypeId}_` prefix
- **FlowServiceImpl.saveFlow()**: merge-writes to all 5 data files (statuses additive, others replace by flowTypeId)
- **ThubDataServiceImpl.writeDataFile()**: uses `TreeMap` for sorted JSON keys → minimal git diffs (unchanged records stay in place)
- **Validation**: ThubDeploymentData validated (initial/final status exist, transitions reference valid statuses)
- **maven-clean-plugin**: `mvn clean` also removes `frontend/dist/` and `src/main/resources/static/`

### Frontend
- **State**: `currentDeploymentData` (raw THUB data) + `nodes`/`edges` (React Flow canvas)
- **currentMetadata**: derived from `currentDeploymentData.flowType` for Header/MetadataEditor
- **editMetadata()**: merges partial flowType fields back into currentDeploymentData
- **JSON property naming**: Backend uses `@JsonProperty("flowstatusid")`, frontend handles both camelCase and lowercase

## Result Types (Edge Labels)
| Type | Description |
|------|-------------|
| `success` | Action completed successfully |
| `business-error` | Business validation failed |
| `technical-error` | Technical failure |
| `technical-expiration` | Timeout expired |
| `unpredictable-status` | Unknown status |
| `special-status` | Special handling |

## Known Issues / Remaining Work

### Infrastructure (Future)
- [ ] Production Git repository for flows
- [ ] THUB deployment API on the payment hub side
- [ ] Flow Deployer service (configuration deployer integration)
- [x] GitLab OAuth2 authentication
- [ ] Additional OAuth2 providers (GitHub, Bitbucket) — see `OAuth2UserAttributes`

## Conventions
- Flow names: start with letter, letters/numbers/underscores/hyphens (e.g., `payment-flow`, `debit-credit-reversal`)
- Branch names: `feature/TASK-123-description` pattern
- Commit messages: descriptive, include flow name when relevant
- Backend tests: JUnit 5 + Mockito, test files mirror source structure
- No database - all persistence is Git-based
- Git commit staging: `THUB/` directory (not `flows/`)
