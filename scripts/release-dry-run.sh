#!/usr/bin/env bash
# Builds a signed Maven Central bundle exactly like a release, without uploading it, and validates
# it. Uses a throwaway OpenPGP key, so it needs no secrets. Intended for CI and for maintainers
# preparing a release.
#
#   scripts/release-dry-run.sh [version]     # default: <current version without -SNAPSHOT>
#
# The working tree's POM versions are changed; run it in a disposable checkout (CI does).
set -euo pipefail

cd "$(dirname "$0")/.."
current="$(./mvnw -B -q -N org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate \
  -Dexpression=project.version -DforceStdout)"
version="${1:-${current%-SNAPSHOT}}"

GNUPGHOME="$(mktemp -d)"
export GNUPGHOME
trap 'rm -rf "$GNUPGHOME"' EXIT
passphrase="$(head -c 24 /dev/urandom | base64)"
gpg --batch --pinentry-mode loopback --passphrase "$passphrase" \
  --quick-gen-key "Zynqo release dry run <dry-run@invalid>" ed25519 sign 1d
MAVEN_GPG_KEY="$(gpg --batch --pinentry-mode loopback --passphrase "$passphrase" --armor --export-secret-keys)"
MAVEN_GPG_PASSPHRASE="$passphrase"
export MAVEN_GPG_KEY MAVEN_GPG_PASSPHRASE

./mvnw -B -ntp -q org.codehaus.mojo:versions-maven-plugin:2.23.0:set \
  -DnewVersion="$version" -DgenerateBackupPoms=false -DprocessAllModules=true
./mvnw -B -ntp -Prelease deploy -DskipTests -DskipUpload=true

python3 scripts/validate-central-bundle.py target/central-publishing/central-bundle.zip "$version" --verify-signatures
