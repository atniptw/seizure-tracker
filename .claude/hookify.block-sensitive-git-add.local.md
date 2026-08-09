---
name: block-sensitive-git-add
enabled: true
event: bash
pattern: git\s+add\s+.*(-f\s+|--force\s+)?.*(google-services\.json|\.jks|\.keystore|key\.properties|\.p12)
action: block
---

**Blocked: staging a signing key or Firebase config file**

This command appears to `git add` a release keystore, `key.properties`, or
`google-services.json`. These must stay out of git history — once committed, a keystore
password can't be un-leaked by a later commit. If this is genuinely intentional (e.g. adding a
`.gitignore` entry, not the file itself), rephrase the command to target that file specifically.
