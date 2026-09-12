'use strict';

/** Minimal `--key=value` / `--flag` parsing. No dependency worth adding for this. */
function parseArgs(argv) {
  const flags = {};
  const positional = [];
  for (const arg of argv) {
    if (!arg.startsWith('--')) { positional.push(arg); continue; }
    const body = arg.slice(2);
    const eq = body.indexOf('=');
    if (eq === -1) flags[body] = true;
    else flags[body.slice(0, eq)] = body.slice(eq + 1);
  }
  return { flags, positional };
}

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
