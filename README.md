# ParentGuard — Consent-First Android Parental Monitoring System

ParentGuard is a dual-application Android parental monitoring platform engineered around verifiable child consent, architectural transparency, edge privacy minimization, and zero-knowledge end-to-end encryption (E2EE).

## Dual-App Architecture
1. **Device Agent (`com.parentguard.agent` — `:agent:app-agent`)**: Installed on the child's smartphone.
   - Displays an unhideable launcher icon, a persistent ongoing status notification (`TransparencyNotificationManager`), and a 7-day local **Child Transparency Audit Ledger** (`TransparencyAuditDatabase`) showing every payload sent to the parent.
   - Enforces modular, child-granted Android permissions with graceful degradation when any permission is refused or revoked.
   - Performs on-device edge scrubbing and interval aggregation before encrypting payloads using **Google Tink HPKE** (`X25519-HKDF-SHA256-AES256GCM`) and signing with a hardware-backed Android Keystore key (`Ed25519` / `ECDSA P-256`).
2. **Monitor App (`com.parentguard.monitor` — `:monitor:app-monitor`)**: Installed on the parent's smartphone.
   - Requests **zero** collection permissions.
   - Initiates the physical QR pairing ceremony with mutual 6-digit Short Authentication String (`SAS`) verification.
   - Pulls E2EE envelopes from the stateless store-and-forward relay upon receiving FCM wake-up pushes, verifies the child's cryptographic signature, decrypts into a local 30-day rolling SQLCipher database (`allowBackup="false"`), and immediately purges the relay copy via `DELETE /v1/envelopes/{id}`.

## Unified Direct-Install Build (Private Family Sideloading — No Play Store Restrictions)
- Because ParentGuard is distributed privately via direct APK installation rather than Google Play, all four capabilities—(1) Device Status & Battery, (2) App Usage Summaries (`PACKAGE_USAGE_STATS`), (3) Opt-In Notification Metadata (`BIND_NOTIFICATION_LISTENER_SERVICE`), and (4) Opt-In SMS & Call Metadata (`READ_CALL_LOG`, `READ_SMS`, `READ_CONTACTS`)—are compiled directly into the main `:agent:app-agent` build without needing separate `playStore` vs. `directFamily` product flavors.
- Includes an Android 13+ (API 33+) **Restricted Settings Unlock Helper** (`RestrictedSettingsHelper.kt`) that detects sideloaded installation and guides the child and parent through unlocking Special App Access (`Allow restricted settings` in App Info) while preserving every consent and on-device privacy guardrail.

## CI Privacy & Manifest Verification Gate
Run `./gradlew verifyPrivacyAndManifestInvariants` to assert that no prohibited stalkerware permissions (`ACCESS_FINE_LOCATION`, `BIND_ACCESSIBILITY_SERVICE`, `BIND_DEVICE_ADMIN`, `RECORD_AUDIO`, `QUERY_ALL_PACKAGES`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) or icon-hiding APIs exist anywhere in the project.
