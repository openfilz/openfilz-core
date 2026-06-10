## Unreleased
### No issue

**[maven-release-plugin] prepare for next development iteration**


[c463b9adcdaabd5](https://github.com/openfilz/openfilz-core/commit/c463b9adcdaabd5) maven-release-bot[bot] *2026-06-10 00:26:25*


## v1.2.1
### No issue

**[maven-release-plugin] prepare release v1.2.1**


[96949581294c6d8](https://github.com/openfilz/openfilz-core/commit/96949581294c6d8) maven-release-bot[bot] *2026-06-10 00:26:23*

**feat: generate ngx-env.js at container start from NG_APP_* env**

 * Replace the mounted/Makefile-generated ngx-env.js with env-based generation across all deployments: docker-compose, dokploy compose, and the Helm chart now pass NG_APP_* via env (the openfilz-web image writes ngx-env.js at startup). Removes ngx-env.template.js + the dokploy reference file + the Helm ConfigMap; Makefile passes per-target auth/onlyoffice toggles via env instead of envsubst. Docs + test-compose.sh updated.
 * Co-Authored-By: Claude Opus 4.8 &lt;noreply@anthropic.com&gt;

[bd270e56cb3651b](https://github.com/openfilz/openfilz-core/commit/bd270e56cb3651b) yanndemel *2026-06-09 23:40:14*


