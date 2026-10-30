# Release process

Zynqo is published to Maven Central through the **Sonatype Central Portal** using
`org.sonatype.central:central-publishing-maven-plugin` (0.12.0). The legacy OSSRH / Nexus staging
workflow is not used.

## What was verified, and what was not

| Item | Status |
|---|---|
| Plugin coordinates, goals and parameters (`publishingServerId`, `autoPublish`, `waitUntil` ∈ {`UPLOADED`, `VALIDATED`, `PUBLISHED`}, `excludeArtifacts`, `skipUpload`, snapshot URL `https://central.sonatype.com/repository/maven-snapshots/`) | Read from the plugin descriptor of version 0.12.0 |
| `maven-gpg-plugin` 3.2.8 BC signer reading `MAVEN_GPG_KEY` / `MAVEN_GPG_PASSPHRASE` | Read from the plugin descriptor; **exercised locally** with a throwaway key |
| Bundle layout: 5 artifacts (parent POM, core, redis, autoconfigure, starter), each with sources, javadoc, `.asc`, md5/sha1/sha256/sha512; examples and tests excluded; flattened POMs with name, description, url, licenses, developers, scm, issueManagement | **Built and validated locally** (`scripts/release-dry-run.sh`); CI repeats it on every build |
| Upload, validation and publication by the Central Portal | **Not verified**: needs a verified namespace and a portal token |
| Snapshot publishing | **Not verified**: needs snapshots enabled for the namespace |
| Sonatype documentation pages | Could not be fetched from the build environment; re-check the portal's current requirements before the first release |

## One-time setup

1. **Central Portal account and namespace.** Sign in at <https://central.sonatype.com> and register
   the namespace `io.github.tanmaysinghx`. `io.github.<user>` namespaces are verified against the
   GitHub account; follow the portal's instructions (they currently involve proving control of the
   GitHub account or creating a temporary public repository).
2. **Portal user token.** Central Portal → Account → *Generate User Token*. It yields a username
   and a password.
3. **Signing key.** Create a dedicated key and publish the public half:
   ```bash
   gpg --quick-gen-key "Zynqo Release Signing <you@example.com>" ed25519 sign 2y
   gpg --armor --export-secret-keys <FINGERPRINT> > zynqo-signing.asc   # store securely, then delete
   gpg --keyserver keys.openpgp.org --send-keys <FINGERPRINT>
   gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
   ```
4. **GitHub environment** `maven-central` (Settings → Environments) with **required reviewers**
   and a deployment rule limited to tags `v*`. Add these environment secrets:

   | Secret | Value |
   |---|---|
   | `CENTRAL_TOKEN_USERNAME` | portal token username |
   | `CENTRAL_TOKEN_PASSWORD` | portal token password |
   | `MAVEN_GPG_KEY` | contents of `zynqo-signing.asc` (ASCII-armored secret key) |
   | `MAVEN_GPG_PASSPHRASE` | the key passphrase |

5. **Optional snapshots.** Enable SNAPSHOT publishing for the namespace in the portal, create an
   environment `maven-central-snapshots` with the same four secrets, and set the repository
   variable `CENTRAL_SNAPSHOTS_ENABLED=true`. Without the variable, `snapshot.yml` does nothing.
6. **Repository settings.** Make the repository public before the first release (the POM links to
   it), protect `main` (require the CI and Security checks), and enable secret scanning, push
   protection and Dependabot alerts.

Required workflow permissions are declared per job (`contents: read` by default; `contents: write`
only for creating the GitHub Release; `security-events: write` only for CodeQL and OSV uploads).

## Cutting a release

```bash
# 1. Choose the version (semantic versioning) and update the build and changelog.
./mvnw versions:set -DnewVersion=1.0.0 -DgenerateBackupPoms=false -DprocessAllModules=true
#    Set project.build.outputTimestamp in pom.xml to the release time (reproducible builds).
#    Move the [Unreleased] entries in CHANGELOG.md under "## [1.0.0] - YYYY-MM-DD".

# 2. Verify locally.
./mvnw clean verify -Pstatic-analysis
scripts/release-dry-run.sh 1.0.0       # optional: run in a scratch clone, it rewrites versions

# 3. Commit, tag, push.
git commit -am "Release 1.0.0"
git tag -s v1.0.0 -m "Zynqo 1.0.0"
git push origin main v1.0.0

# 4. Approve the 'maven-central' deployment in the Actions tab.

# 5. Start the next development version.
./mvnw versions:set -DnewVersion=1.1.0-SNAPSHOT -DgenerateBackupPoms=false -DprocessAllModules=true
git commit -am "Prepare 1.1.0-SNAPSHOT" && git push
```

## What `release.yml` does

1. **verify:** checks that the tag equals `v<project.version>`, that the version is not a SNAPSHOT,
   that `CHANGELOG.md` has a `## [<version>]` section, and that `zynqo-core:<version>` does **not**
   already exist on Maven Central (published versions are never overwritten). Then runs all quality
   gates (`verify -Pstatic-analysis`: unit and integration tests, coverage, SpotBugs).
2. **publish** (environment `maven-central`): `deploy -Prelease` builds sources and javadoc,
   flattens POMs, signs everything, uploads the bundle and waits until the portal reports
   **PUBLISHED** (`autoPublish=true`, `waitUntil=PUBLISHED`, up to 60 minutes). It then validates
   the uploaded bundle and keeps `publish.log` and the bundle as workflow artifacts for 90 days.
3. **github-release:** runs only after step 2 succeeded, and creates the GitHub Release with the
   changelog section as notes.

*PUBLISHED* means the portal accepted the deployment and released it to Maven Central. Indexing
and propagation to `repo.maven.apache.org`, mirrors and search can take additional time, from
minutes to hours.

## Recovering from a partially failed release

| Failure point | State | Recovery |
|---|---|---|
| `verify` fails | Nothing published | Fix on `main`, delete and re-create the tag (`git push --delete origin vX.Y.Z`), or release `X.Y.(Z+1)` |
| `publish` fails **before upload** (signing, build) | Nothing published | Fix the secrets or build, then re-run the failed job |
| Upload succeeded but validation **FAILED** in the portal | Deployment in state FAILED; nothing published | Read the errors in the portal (Deployments), drop the deployment, fix, re-run |
| Timeout while waiting, deployment still PUBLISHING | May publish later | Check the portal. If it becomes PUBLISHED, create the GitHub Release by hand (`gh release create vX.Y.Z --notes-file ...`); **do not** re-run `publish` |
| PUBLISHED but `github-release` failed | Artifacts on Central | Re-run only the `github-release` job |
| Wrong artifacts published | Immutable on Central | Never overwrite. Release a new patch version and mark the bad one in the changelog and release notes |

Versions on Maven Central cannot be deleted or replaced. The workflow refuses to publish a version
that already exists.
