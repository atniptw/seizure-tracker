'use strict';

/**
 * Minimal `--key=value` / `--flag` parsing. No dependency worth adding for this.
 *
 * `valueFlags` names the flags that MUST carry `=<value>`. Without that list a flag written in
 * the space-separated form every other CLI trains (`--only households/h1/seizures`) parses as
 * `{only: true}` plus a stray positional, and a `true` means "no value" to `list()`, which means
 * "select everything" to restore.js — i.e. a typo silently widens the delete scope from one
 * collection to a whole household. So a value-taking flag given with no `=` is a hard error, and
 * so is an empty value: `--only=` is the same hazard written differently.
 */
function parseArgs(argv, { valueFlags = [] } = {}) {
  const needsValue = new Set(valueFlags);
  const flags = {};
  const positional = [];
  for (const arg of argv) {
    if (!arg.startsWith('--')) { positional.push(arg); continue; }
    const body = arg.slice(2);
    const eq = body.indexOf('=');
    if (eq === -1) {
      if (needsValue.has(body)) {
        throw new Error(
          `--${body} takes a value and must be written --${body}=<value>. ` +
            `A space-separated "--${body} <value>" parses as a bare flag with the value dropped, ` +
            'which would silently change the scope of this run.'
        );
      }
      flags[body] = true;
      continue;
    }
    const key = body.slice(0, eq);
    const value = body.slice(eq + 1);
    if (needsValue.has(key) && value === '') {
      throw new Error(`--${key}= was given with an empty value. Pass a real value or drop the flag.`);
    }
    flags[key] = value;
  }
  return { flags, positional };
}

/**
 * A comma-separated flag value as a trimmed list.
 *
 * `undefined` (flag absent) is the only input that yields `[]`. `true` cannot reach here for a
 * flag declared in `valueFlags` — parseArgs rejects it — but the guard stays so a caller that
 * forgets to declare one gets an empty list rather than `"true"` as a literal value.
 */
const list = (value) =>
  value === undefined || value === true || value === ''
    ? []
    : String(value).split(',').map((s) => s.trim()).filter(Boolean);

const log = (...args) => console.log(...args);
const warn = (...args) => console.log('WARNING:', ...args);

/** A right-aligned count table, longest path first column. */
function countTable(counts) {
  const paths = Object.keys(counts).sort();
  const width = paths.reduce((w, p) => Math.max(w, p.length), 0);
  return paths.map((p) => `  ${p.padEnd(width)}  ${String(counts[p]).padStart(6)}`).join('\n');
}

const HEALTH_DATA_WARNING = [
  'This dump is an unencrypted, complete copy of a household health record — every seizure',
  'entry, medication and vet contact, in plain JSON on this laptop. security-privacy.md §2.1/§2.3',
  'treats it as an asset equivalent to the database itself. Keep it off any synced or backed-up',
  'location, and delete it once the migration.md §7 cleanup has verified.',
].join('\n');

/** One-line description of the credential initFirestore resolved, for the target banner. */
function describeCredential(credential) {
  if (!credential) return 'unknown';
  if (credential.kind === 'emulator') return 'none (emulator)';
  if (credential.kind === 'adc') return `gcloud ADC (${credential.path})`;
  return `service-account key (${credential.path})`;
}

module.exports = {
  parseArgs, list, log, warn, countTable, describeCredential, HEALTH_DATA_WARNING,
};
