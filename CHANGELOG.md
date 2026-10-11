## Unreleased
### No issue

**[maven-release-plugin] prepare for next development iteration**


[bf20041e1a9d5f9](https://github.com/openfilz/openfilz-core/commit/bf20041e1a9d5f9) maven-release-bot[bot] *2026-10-11 11:41:31*


## v1.3.43
### No issue

**[maven-release-plugin] prepare release v1.3.43**


[2811b6c34165df7](https://github.com/openfilz/openfilz-core/commit/2811b6c34165df7) maven-release-bot[bot] *2026-10-11 11:41:29*

**Merge pull request #321 from openfilz/develop**

 * Develop

[5d080e8a96d59dd](https://github.com/openfilz/openfilz-core/commit/5d080e8a96d59dd) Yann Demel *2026-10-11 11:38:05*

**Merge pull request #320 from openfilz/fix/central-no-exec-jar**

 * build: stop deploying the 400 MB exec jar to Maven Central

[9c4c397edfd40f3](https://github.com/openfilz/openfilz-core/commit/9c4c397edfd40f3) Yann Demel *2026-10-11 10:51:34*

**build: stop deploying the 400 MB exec jar to Maven Central**

 * The Spring Boot fat jar (openfilz-api-*-exec.jar, ~394 MB) was ~95% of every
 * Central release (~411 MB each) and pushed the Openfilz organisation far over
 * Sonatype&#x27;s monthly publishing limits (80 MB / 1 000 files / 7 releases). It is
 * very likely also why the 1.3.41 and 1.3.42 release bundles failed validation
 * (&quot;Bundle has content that does NOT have a .pom file&quot;): the same 1.3.42 bundle
 * without the jar (18 MB) validated straight away.
 * The jar is still built (attach&#x3D;false), so the GitHub Release asset and local
 * &#x60;java -jar&#x60; runs keep working; it is just no longer installed or deployed.
 * Nothing consumes it from Central: releases ship as container images.
 * Co-Authored-By: Claude Opus 5.5 &lt;noreply@anthropic.com&gt;

[6c4583107b6abf3](https://github.com/openfilz/openfilz-core/commit/6c4583107b6abf3) yanndemel *2026-10-11 10:49:39*

**[maven-release-plugin] prepare for next development iteration**


[d37941adec14e84](https://github.com/openfilz/openfilz-core/commit/d37941adec14e84) maven-release-bot[bot] *2026-10-09 15:26:08*


## v1.3.42
### No issue

**[maven-release-plugin] prepare release v1.3.42**


[ed719f63a75760f](https://github.com/openfilz/openfilz-core/commit/ed719f63a75760f) maven-release-bot[bot] *2026-10-09 15:26:05*

**Merge pull request #319 from openfilz/develop**

 * Develop

[54d008293283951](https://github.com/openfilz/openfilz-core/commit/54d008293283951) Yann Demel *2026-10-09 15:22:33*

**Merge pull request #318 from openfilz/fix/cors-reason-header**

 * fix(cors): configurable extra request headers (openfilz.security.cors-allowed-headers)

[e05c3c93f93abce](https://github.com/openfilz/openfilz-core/commit/e05c3c93f93abce) Yann Demel *2026-10-09 14:33:06*

**fix(cors): let deployments allow extra request headers on the REST API**

 * &#x60;openfilz.security.cors-allowed-headers&#x60; (env OPENFILZ_SECURITY_CORS_ALLOWED_HEADERS)
 * appends headers to the built-in CORS list (Authorization, Content-Type, TUS headers).
 * Any custom request header triggers a browser preflight, and the API only echoed the
 * built-in list, so a cross-origin call carrying e.g. X-OpenFilz-Reason was refused by
 * the browser and surfaced as a network error.
 * Co-Authored-By: Claude Fable 5.1 &lt;noreply@anthropic.com&gt;

[5ba9cf8f04fa4ab](https://github.com/openfilz/openfilz-core/commit/5ba9cf8f04fa4ab) yanndemel *2026-10-09 14:23:31*

**Merge main into develop**


[18b9951d61e0fad](https://github.com/openfilz/openfilz-core/commit/18b9951d61e0fad) Yann Demel *2026-10-08 23:50:04*

**docs: update changelog for version 1.3.41**

 * Co-Authored-By: Claude Opus 5.5 (1M context) &lt;noreply@anthropic.com&gt;

[e0192512f5981f8](https://github.com/openfilz/openfilz-core/commit/e0192512f5981f8) yann *2026-10-08 23:49:35*

**[maven-release-plugin] prepare for next development iteration**


[a947fb8a35d46a7](https://github.com/openfilz/openfilz-core/commit/a947fb8a35d46a7) maven-release-bot[bot] *2026-10-08 23:39:23*


