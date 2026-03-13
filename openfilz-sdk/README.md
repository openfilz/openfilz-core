# OpenFilz SDK

Multi-language SDK generation for OpenFilz REST and GraphQL APIs.

All SDKs are auto-generated from the OpenAPI specification produced by `openfilz-api`, using the [OpenAPI Generator](https://openapi-generator.tech/) Maven plugin. Each SDK also bundles the GraphQL schema files so consumers can use any GraphQL client library.

## Available SDKs

| Module | Language | Package Registry | Package Name |
|--------|----------|------------------|--------------|
| `openfilz-sdk-java` | Java (blocking) | [Maven Central](https://central.sonatype.com/) | `org.openfilz:openfilz-sdk-java` |
| `openfilz-sdk-java-reactive` | Java (reactive) | [Maven Central](https://central.sonatype.com/) | `org.openfilz:openfilz-sdk-java-reactive` |
| `openfilz-sdk-typescript` | TypeScript | [npm](https://www.npmjs.com/) | `@openfilz-sdk/typescript` |
| `openfilz-sdk-python` | Python | [PyPI](https://pypi.org/) | `openfilz-sdk-python` |
| `openfilz-sdk-csharp` | C# / .NET 8 | [NuGet](https://www.nuget.org/) | `OpenFilz.Sdk` |

See each module's own `README.md` for installation and usage examples.

---

## Architecture

```
openfilz-api  ──(OpenAPI spec JSON)──►  openfilz-sdk-*  ──(codegen)──►  generated client code
                                            │
                                            └── GraphQL schemas copied from openfilz-api/src/main/resources/graphql
```

All five SDK modules share the same build pipeline:

1. **`initialize`** — `maven-dependency-plugin` copies the OpenAPI spec artifact (`openfilz-api:openapi:json`) into `target/openapi/openfilz-api.json`
2. **`generate-sources`** — `openapi-generator-maven-plugin` generates language-specific client code into `target/generated-sources/` (Java SDKs) or `target/generated-sdk/<lang>/` (TypeScript, Python, C#)
3. **`process-sources`** — `maven-resources-plugin` copies GraphQL `.graphqls` schemas into the generated SDK output
4. **`compile` → `package`** — Standard compilation (Java) or native toolchain packaging (npm/pip/dotnet)
5. **`deploy`** — Publishes to the respective package registry

The parent `openfilz-sdk/pom.xml` normalizes the OpenAPI spec path to a `file:///` URI using `build-helper-maven-plugin`, which fixes the Windows backslash issue with swagger-parser.

### Known Post-Generation Fixes

- **C# multi-file upload bug**: The OpenAPI Generator produces incorrect code that passes `List<Stream>` to `StreamContent()` instead of iterating over each file. The `fix-multifile-upload.sh` script (called via `exec-maven-plugin` in `process-sources`) patches this automatically using `sed`.

---

## Building

### Prerequisites

- **Java 17+** and **Maven 3.x** (all SDKs)
- **Node.js 18+** and **npm** (TypeScript SDK)
- **Python 3.8+** with `build` and `twine` packages (Python SDK)
- **.NET 8 SDK** (`dotnet` CLI) (C# SDK)

### Build All SDKs (No Publishing)

```bash
# From the repository root — builds openfilz-api first, then all SDKs
mvn clean install -pl openfilz-sdk/openfilz-sdk-java,openfilz-sdk/openfilz-sdk-java-reactive,openfilz-sdk/openfilz-sdk-typescript,openfilz-sdk/openfilz-sdk-python,openfilz-sdk/openfilz-sdk-csharp -am
```

### Build a Single SDK

```bash
# Example: TypeScript only
mvn clean install -pl openfilz-sdk/openfilz-sdk-typescript -am
```

---

## Publishing

Two Maven profiles control publishing:

| Profile | Purpose | Applies to |
|---------|---------|------------|
| `publishSdk` | Publishes TypeScript (npm), Python (PyPI), C# (NuGet) | Non-Java SDK modules |
| `publishMaven` | Publishes JARs to Maven Central with GPG signing | Java SDK modules |

### Environment Variables

| Variable | Required by | Description |
|----------|-------------|-------------|
| `NPM_TOKEN` | TypeScript | npm authentication token ([npmjs.com/settings/tokens](https://www.npmjs.com/settings/~/tokens)) |
| `PYPI_TOKEN` / `TWINE_USERNAME` + `TWINE_PASSWORD` | Python | PyPI credentials ([pypi.org/manage/account](https://pypi.org/manage/account/)) |
| `NUGET_API_KEY` | C# | NuGet API key ([nuget.org/account/apikeys](https://www.nuget.org/account/apikeys)) |
| `MAVEN_USERNAME` | Java | Maven Central (Sonatype) username |
| `MAVEN_PASSWORD` | Java | Maven Central (Sonatype) password/token |
| `MAVEN_GPG_PASSPHRASE` | Java | GPG key passphrase for artifact signing |
| `GIT_PAT` | Release only | GitHub PAT for tagging (used by `maven-release-plugin`) |

### Maven `settings.xml`

For Java SDK publishing (both CI and local), Maven needs a `~/.m2/settings.xml` with the following servers:

```xml
<settings>
    <servers>
        <!-- Maven Central credentials — used by central-publishing-maven-plugin -->
        <server>
            <id>central</id>
            <username>${env.MAVEN_USERNAME}</username>
            <password>${env.MAVEN_PASSWORD}</password>
        </server>
        <!-- GPG passphrase — used by maven-gpg-plugin (non-interactive signing) -->
        <server>
            <id>gpg.passphrase</id>
            <passphrase>${env.MAVEN_GPG_PASSPHRASE}</passphrase>
        </server>
        <!-- GitHub SCM access — used by maven-release-plugin to push tags/commits -->
        <server>
            <id>git</id>
            <username>x-access-token</username>
            <password>${env.GIT_PAT}</password>
        </server>
    </servers>
</settings>
```

You also need a GPG key imported locally. To check:

```bash
gpg --list-secret-keys --keyid-format long
```

If you have no key, generate one with `gpg --full-generate-key` (RSA 4096, no expiration recommended for CI). Publish your public key to a keyserver so Maven Central can verify signatures:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

### Publish All SDKs (Single Command)

```bash
mvn deploy \
  -PpublishSdk,publishMaven \
  -pl openfilz-sdk/openfilz-sdk-typescript,openfilz-sdk/openfilz-sdk-python,openfilz-sdk/openfilz-sdk-csharp,openfilz-sdk/openfilz-sdk-java,openfilz-sdk/openfilz-sdk-java-reactive \
  -am --fail-at-end
```

- `--fail-at-end` ensures all SDKs are attempted even if one fails
- `publishSdk` activates npm/PyPI/NuGet publishing and skips tests
- `publishMaven` activates Maven Central publishing with GPG signing, source JARs, and Javadocs
- Non-Java SDKs set `maven.deploy.skip=true` in their own `publishSdk` profile to prevent the Maven deploy plugin from running (they use native toolchains instead)

### Publish Only Non-Java SDKs

```bash
mvn deploy \
  -PpublishSdk \
  -pl openfilz-sdk/openfilz-sdk-typescript,openfilz-sdk/openfilz-sdk-python,openfilz-sdk/openfilz-sdk-csharp \
  -am --fail-at-end
```

### Publish Only Java SDKs

```bash
mvn deploy \
  -PpublishMaven \
  -pl openfilz-sdk/openfilz-sdk-java,openfilz-sdk/openfilz-sdk-java-reactive \
  -am
```

### Publish a Single SDK

```bash
# TypeScript only
mvn deploy -PpublishSdk -pl openfilz-sdk/openfilz-sdk-typescript -am

# Python only
mvn deploy -PpublishSdk -pl openfilz-sdk/openfilz-sdk-python -am

# C# only
mvn deploy -PpublishSdk -pl openfilz-sdk/openfilz-sdk-csharp -am

# Java blocking only
mvn deploy -PpublishMaven -pl openfilz-sdk/openfilz-sdk-java -am

# Java reactive only
mvn deploy -PpublishMaven -pl openfilz-sdk/openfilz-sdk-java-reactive -am
```

---

## Version Handling

| SDK | Maven Version | Published Version | Notes |
|-----|--------------|-------------------|-------|
| Java / Java Reactive | `1.1.5-SNAPSHOT` | `1.1.5-SNAPSHOT` | Maven Central allows SNAPSHOT repos |
| TypeScript | `1.1.5-SNAPSHOT` | `1.1.5-SNAPSHOT` | Published with `--tag snapshot` (npm dist-tag) |
| Python | `1.1.5-SNAPSHOT` | `1.1.5.dev0` | Converted via `build-helper-maven-plugin` (PEP 440) |
| C# | `1.1.5-SNAPSHOT` | `1.1.5-SNAPSHOT` | NuGet accepts SemVer pre-release tags |

For release versions (non-SNAPSHOT), use `-Dnpm.publish.tag=latest` to tag the npm package as `latest`:

```bash
mvn deploy -PpublishSdk -Dnpm.publish.tag=latest -pl openfilz-sdk/openfilz-sdk-typescript -am
```

### Registry Immutability

All package registries enforce version immutability — **you cannot republish the same version**:

- **npm**: Returns `E403 — cannot publish over previously published versions`. Use `npm unpublish <pkg>@<version>` to remove (24h cooldown before re-publish).
- **PyPI**: Returns `400 Bad Request`. Cannot delete and re-upload — the version number is permanently consumed. The `--skip-existing` flag in the `twine upload` command makes this a no-op instead of a failure.
- **NuGet**: Returns `409 Conflict`. Can unlist but not delete; version number is permanently consumed.
- **Maven Central**: Released (non-SNAPSHOT) versions are immutable. SNAPSHOT versions can be overwritten.

---

## CI Integration

The `release-backend.yml` GitHub Actions workflow handles the full release lifecycle:

1. **Version bump** — Removes `-SNAPSHOT`, calculates release + next dev versions based on PR labels (`release:patch`, `release:minor`, `release:major`)
2. **`mvn release:prepare`** — Updates pom versions, creates Git tag
3. **`mvn release:perform`** — Builds and deploys with `-PpublishMaven,publishSdk,kube`
4. **Changelog** — Generates `CHANGELOG.md` from Git history
5. **GitHub Release** — Creates release with attached JAR artifacts
6. **Branch sync** — Merges `main` → `develop`

### Required GitHub Secrets

| Secret | Description |
|--------|-------------|
| `RELEASE_CLIENT_ID` | GitHub App ID for automated commits |
| `RELEASE_PRIVATE_KEY` | GitHub App private key |
| `OSSRH_GPG_SECRET_KEY` | GPG private key (armor-exported) |
| `OSSRH_GPG_SECRET_KEY_PASSWORD` | GPG key passphrase |
| `MAVEN_USERNAME` | Maven Central username |
| `MAVEN_PASSWORD` | Maven Central password/token |
| `NPM_TOKEN` | npm authentication token |
| `NUGET_API_KEY` | NuGet API key |
| `PYPI_TOKEN` | PyPI authentication token |
