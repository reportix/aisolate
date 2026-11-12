# The Linux release key

`aisolate-release-key.asc` is the public half of the key that signs every tag's `aisolate-cli-linux-x64.tar.gz`
(`cli-linux-archive` in `.gitlab-ci.yml`). Generate the pair once, keep the private half as the CI variable
`GPG_SIGNING_KEY` (docs/release.md, Signing), and commit the public half here:

```bash
gpg --quick-generate-key "AIsolate release <release@aisolate.ai>" ed25519 sign 3y
gpg --armor --export release@aisolate.ai > aisolate-release-key.asc
gpg --armor --export-secret-keys release@aisolate.ai > /tmp/aisolate-release-private.asc   # the variable, then shred it
```
