# Network drives (SMB, SFTP, FTP/FTPS)

This fork adds built-in network drives with saved credentials. (Polish version with more detail: [NETWORK_DRIVES.pl.md](NETWORK_DRIVES.pl.md).)

## Getting the APK

The **Build APK** GitHub Actions workflow runs the unit tests and builds a debug APK on every push and on demand
(**Actions → Build APK → Run workflow**). Download `file-manager-apk` from the run's *Artifacts* section.
Unless you add the signing secrets `SIGNING_KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD`, the APK is signed with a throw-away debug key and a newer build cannot be installed over it.

## Using it

Menu → **Network drives** → **Add network drive** (saved drives also appear in the storage picker, which has a *Manage network drives…* entry). Choose the protocol, enter the address and
credentials (SFTP also accepts a private key file), optionally scan the local network for servers, test the connection
and save. The drive then shows up in the storage picker next to internal storage and SD cards.

You can browse, open, edit text files in place, share, rename, create folders, search the current folder and
copy / move / delete between the phone and the drive. Transfers run in a foreground service with a progress
notification and conflict handling.

## Security

- Passwords and private keys are encrypted with AES-256-GCM using an Android Keystore key and are excluded from backups.
- SSH host keys and FTPS certificates are pinned on first use; a changed identity is rejected with a warning.
- Plain FTP and unsigned SMB are not fully encrypted — use trusted networks, or SFTP / FTPS.

## Limitations

SMB1 is not supported; no thumbnails or media streaming for remote files; remote search covers only the current
folder; FTPS TLS session reuse (required by e.g. vsftpd `require_ssl_reuse`) has only been verified with Conscrypt on
the JVM; Google Drive is not implemented yet.
