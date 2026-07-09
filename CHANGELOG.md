## Unreleased
### No issue

**[maven-release-plugin] prepare for next development iteration**


[2a92156b8494952](https://github.com/openfilz/openfilz-core/commit/2a92156b8494952) maven-release-bot[bot] *2026-07-09 18:58:06*


## v1.2.14
### No issue

**[maven-release-plugin] prepare release v1.2.14**


[77b47499de94792](https://github.com/openfilz/openfilz-core/commit/77b47499de94792) maven-release-bot[bot] *2026-07-09 18:58:03*

**ci(release): stop upgrading npm — bundled npm 11.16 has sigstore, self-upgrade doesn't**

 * The release-backend.yml &#x60;npm install -g npm@latest&#x60; step ships a broken npm
 * whose bundled &#x60;sigstore&#x60; is missing, so the TypeScript SDK&#x27;s &#x60;npm publish&#x60;
 * (provenance auto-enabled under OIDC Trusted Publishing) fails with
 * &#x60;Cannot find module &#x27;sigstore&#x27;&#x60; in libnpmpublish/lib/provenance.js (npm/cli#9722).
 * Node 24.18 already bundles npm 11.16, which is &gt;&#x3D; 11.5.1 (the OIDC minimum) and
 * has an intact sigstore. Remove the self-upgrade and rely on the pristine
 * Node-bundled npm.
 * Co-Authored-By: Claude Opus 4.8 &lt;noreply@anthropic.com&gt;
 * (cherry picked from commit 10620ff17e0229b8ff6e874828c3b06344d9c6d1)

[2680b6125deb952](https://github.com/openfilz/openfilz-core/commit/2680b6125deb952) yanndemel *2026-07-09 18:54:11*


