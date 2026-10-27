# App Link test site (ChatGPT → Autopilot spike)

Static files for the one verified App Link route in `docs/CHATGPT_APP_LINK_SPIKE.md`. **Nothing here
is published yet.** Publishing needs the owner's approval.

| File | Served at | Purpose |
| --- | --- | --- |
| `.well-known/assetlinks.json` | `https://hassanjaveed84.github.io/.well-known/assetlinks.json` | Tells Android this site trusts `com.lifeadmin.autopilot` signed with the personal key |
| `autopilot/test.html` | `https://hassanjaveed84.github.io/autopilot/test` | Fallback page shown only when a browser keeps the link. It never sends the `#message=…` fragment anywhere |
| `.nojekyll` | — | Stops GitHub Pages' Jekyll step from dropping the `.well-known` directory, because Jekyll skips dot-folders |
| `assetlinks-from-apk.sh` | not published | Builds `assetlinks.json` from the certificate an APK is actually signed with |

## Why GitHub Pages on `hassanjaveed84.github.io`

- It's free, HTTPS is automatic, and it uses the GitHub account Autopilot already lives on. No DNS or registrar is involved.
- App Links need `assetlinks.json` at the **root of the host**. A project site (`…github.io/autopilot/`)
  can't serve `/.well-known/` at the root, so this has to be the **user site**: a public repository
  named exactly `HassanJaveed84.github.io`.
- `github.io` is on the Public Suffix List, so `hassanjaveed84.github.io` is its own site. Only this
  GitHub account can publish there.
- GitHub Pages is expected to serve `.json` as `application/json` and to serve `/autopilot/test` from `autopilot/test.html`
  without a redirect. This hasn't been checked here because nothing is published; step 4 below checks it.
- Later, a custom domain (about $10–15 a year) would make links independent of the GitHub username. That's not needed for the spike.

Cloudflare Pages (`<name>.pages.dev`) is an equally free alternative, but it needs a new account.

**Caveat:** if GitHub Pages is ever turned on for the `autopilot` repository itself, its project site
takes over `https://hassanjaveed84.github.io/autopilot/…` and replaces the fallback page. The app link
and `assetlinks.json` would keep working, because `/.well-known/` stays with the user site.

## Publish (owner, after approval)

1. On GitHub, create a **public** repository named `HassanJaveed84.github.io`. The contents are public anyway: only a certificate digest and a static page.
2. Copy `.nojekyll`, `.well-known/assetlinks.json` and `autopilot/test.html` into the repository root, keeping their paths, and push to `main`.
3. Under **Settings → Pages**, check that the source is "Deploy from a branch", branch `main`, folder `/ (root)`. **Enforce HTTPS** is always on for `github.io`.
4. Wait for the Pages deployment (Actions tab, about 1 minute), then check:

   ```sh
   curl -sS -o /dev/null -w '%{http_code} %{content_type} redirect=%{redirect_url}\n' https://hassanjaveed84.github.io/.well-known/assetlinks.json
   # expect: 200 application/json; charset=utf-8 redirect=
   curl -sS https://hassanjaveed84.github.io/.well-known/assetlinks.json
   # Google's own reading of the file:
   curl -sS 'https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://hassanjaveed84.github.io&relation=delegate_permission/common.handle_all_urls'
   ```

5. **Then** install (or reinstall) the APK. Android 12+ verifies when the app is installed or updated.
   Android 15+ also re-verifies in the background, but that can take days.

## Check that the fingerprint matches the installed APK

The fingerprint must be the certificate of the APK **on the phone**, not of a local debug build.

- On the phone, the ChatGPT Link Test screen shows **Signing certificate SHA-256**. It must equal the value in `assetlinks.json`.
- For an APK file: `applink-site/assetlinks-from-apk.sh app-release.apk` (or `apksigner verify --print-certs app-release.apk`).
- Every CI run prints this file in its job summary and uploads it as the `assetlinks` artifact.
  It matches the installed app only when CI signed with the personal key (`AUTOPILOT_KEYSTORE_B64` set).
