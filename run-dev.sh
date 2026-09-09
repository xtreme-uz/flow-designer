#!/bin/bash

export SPRING_PROFILES_ACTIVE=dev

# The flows repository Flow Designer writes to. It may be empty: the first save
# creates GIT_DEFAULT_BRANCH on it, with the THUB files under it.
export GIT_REMOTE_URL=https://github.com/xtreme-uz/flow-config.git
export GIT_DEFAULT_BRANCH=develop
export GIT_USERNAME=<your-git-username>
export GIT_TOKEN=<your-git-personal-access-token>

# Push as the signed-in user rather than the token above. Requires a scope that
# grants repository write access (GitLab: write_repository, GitHub: repo) and a
# remote hosted by the provider set in AUTH_PROVIDER below.
export GIT_USE_USER_CREDENTIALS=false

# Which Git host people sign in through: gitlab or github. Configure the one you
# pick — the application refuses to start when its client id or secret is missing.
# Pick the host that serves GIT_REMOTE_URL above: signing in on one host cannot
# authorise a push to another.
export AUTH_PROVIDER=gitlab

# --- GitLab (AUTH_PROVIDER=gitlab) ---
# Uncomment for a self-hosted GitLab (defaults to https://gitlab.com)
# export GITLAB_BASE_URL=https://gitlab.example.com
export GITLAB_CLIENT_ID=<gitlab-application-client-id>
export GITLAB_CLIENT_SECRET=<gitlab-application-client-secret>
export GITLAB_REDIRECT_URL=https://flowdesigner.local:8443/login/oauth2/code/gitlab
# Add write_repository to push as the signed-in user
# export GITLAB_SCOPE=read_user

# --- GitHub (AUTH_PROVIDER=github) ---
# Uncomment for GitHub Enterprise Server (defaults to https://github.com)
# export GITHUB_BASE_URL=https://github.example.com
# export GITHUB_CLIENT_ID=<github-oauth-app-client-id>
# export GITHUB_CLIENT_SECRET=<github-oauth-app-client-secret>
# export GITHUB_REDIRECT_URL=https://flowdesigner.local:8443/login/oauth2/code/github
# Add repo to push as the signed-in user
# export GITHUB_SCOPE=read:user,user:email

# Who may sign in. Leave empty and any account the provider authenticates gets
# read/write access to the flows repository.
export AUTH_ALLOWED_USERNAMES=<comma-separated-provider-usernames>
export AUTH_ALLOWED_EMAIL_DOMAINS=<comma-separated-email-domains>

export KEYSTORE_FILE="$HOME/.flowdesigner/dev-keystore.p12"
export KEYSTORE_PASS="flowdesigner-dev"

RUN_DEV="[\033[1;34mRUN-DEV\033[0m]"

if ! grep -qF "flowdesigner.local" /etc/hosts; then
  printf '\n127.0.0.1  flowdesigner.local\n' | sudo tee -a /etc/hosts > /dev/null
  echo -e "$RUN_DEV flowdesigner.local added to /etc/hosts"
else
  echo -e "$RUN_DEV flowdesigner.local already exists in /etc/hosts"
fi

if [ ! -f "$KEYSTORE_FILE" ]; then
  mkdir -p "$(dirname "$KEYSTORE_FILE")"
  keytool -genkeypair \
    -alias flowdesigner \
    -keyalg RSA \
    -keysize 2048 \
    -storetype PKCS12 \
    -keystore "$KEYSTORE_FILE" \
    -storepass "$KEYSTORE_PASS" \
    -validity 3650 \
    -dname "CN=flowdesigner.local,O=Dev,C=UZ" \
    -noprompt 2>/dev/null
  echo -e "$RUN_DEV Self-signed SSL cert generated: $KEYSTORE_FILE"
else
  echo -e "$RUN_DEV SSL cert already exists: $KEYSTORE_FILE"
fi

echo -e "$RUN_DEV Starting at https://flowdesigner.local:8443/"

cd "$(dirname "$0")"
mvn spring-boot:run
