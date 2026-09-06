---
name: block-sensitive-git-add
enabled: true
event: bash
pattern: git\s+add\s+.*(-f\s+|--force\s+)?.*(google-services\.json|GoogleService-Info\.plist|\.jks|\.keystore|key\.properties|\.p12|\.p8|\.mobileprovision|ExportOptions\.plist)
action: block
---

**Blocked: staging a signing key or platform config file**

This command appears to `git add` a release keystore, `key.properties`, `google-services.json`,
or an iOS signing/config file (`GoogleService-Info.plist`, an App Store Connect API key `.p8`,
a `.mobileprovision`, `ExportOptions.plist`). These must stay out of git history — once
committed, a keystore password or API key can't be un-leaked by a later commit. If this is
genuinely intentional (e.g. adding a `.gitignore` entry, not the file itself), rephrase the
command to target that file specifically.
