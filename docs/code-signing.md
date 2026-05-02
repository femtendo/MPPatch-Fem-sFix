# Code Signing for MPPatch

## Why Sign?

Without a digital signature, Windows SmartScreen will warn users when they download and run the installer. The installer is a native EXE with DLL dependencies, which is exactly the kind of binary that triggers warnings.

## Options

### Option 1: Azure Trusted Signing (Recommended)

Microsoft's newer signing service through Azure. Cheaper than traditional certs (~$10/month).

1. Create an Azure account (free tier works)
2. Set up Azure Trusted Signing
3. Use `signtool` with the Azure Key Vault integration
4. Sign the installer: `signtool sign /fd SHA256 /a /tr http://timestamp.acs.microsoft.com /td SHA256 target/MPPatch-Installer_*.exe`

### Option 2: Traditional Code Signing Certificate

Purchase from a CA (Sectigo, DigiCert, etc.) — $200-500/year.

1. Buy an EV or OV code signing certificate
2. Store the PFX in CI secrets
3. Sign in CI: `signtool sign /f cert.pfx /p $PASSWORD /fd SHA256 /tr http://timestamp.digicert.com /td SHA256 target/MPPatch-Installer_*.exe`

### Option 3: No Signing (Current)

The installer works but SmartScreen will warn. Over time, as enough users download and run it, SmartScreen reputation builds and warnings decrease.

## Recommendation

For an alpha release, skip signing. Focus on getting the build pipeline reliable. Add Azure Trusted Signing before a v1.0 stable release.

## CI Integration

When ready, add a signing step to `.github/workflows/build_dist.yml` after the installer is built:

```yaml
- run: signtool sign /fd SHA256 /a /tr http://timestamp.acs.microsoft.com /td SHA256 target/MPPatch-Installer_*.exe
```
