# Windows v1 testing build

- [x] Inspect Windows/runtime/signing prerequisites and choose a trusted signing integration.
- [x] Implement Windows-private endpoint support and packaged desktop/core startup.
- [x] Add repeatable Windows build, bundled Java/media tools, installer and signing workflow.
- [ ] Validate locally, run Windows CI, retrieve and verify installer artifacts.
- [ ] Deliver installer/testing guide with exact signing/validation status.

Constraints: v1 is a testing preview, not full PRD release acceptance. User specifically requested a publicly trusted signature, not a self-signed test certificate. Trusted identity/enrollment cannot be fabricated; prepare unsigned artifact while required signing account enrollment is pending. No private key, cookies, user database, download, or media URL goes to source control/artifacts. Preserve existing Linux behavior and downloads. Existing GitHub account is arigavincent and ArDownload repository is private. Private AJDM repository created at arigavincent/AJDM; source-only checkout at /tmp/ajdm-windows-repo.
