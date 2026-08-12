#!/bin/bash

export SPRING_PROFILES_ACTIVE=dev

export GIT_REMOTE_URL=<your-git-remote-url>
export GIT_DEFAULT_BRANCH=develop
export GIT_USERNAME=<your-git-username>
export GIT_TOKEN=<your-git-personal-access-token>

# Uncomment for a self-hosted GitLab (defaults to https://gitlab.com)
# export GITLAB_BASE_URL=https://gitlab.example.com
export GITLAB_CLIENT_ID=<gitlab-application-client-id>
export GITLAB_CLIENT_SECRET=<gitlab-application-client-secret>
export GITLAB_REDIRECT_URL=https://flowdesigner.local:8443/login/oauth2/code/gitlab

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
