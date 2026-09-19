'use strict';

/**
 * Minimal `--key=value` / `--flag` parsing. No dependency worth adding for this.
 *
 * Every flag a script reads must be declared, in exactly one of two lists, and the distinction is
 * total — there is no third category and nothing is coerced:
 *
 *  * `valueFlags` MUST carry `=<value>`. Without that list a flag written in the space-separated
 *    form every other CLI trains (`--only households/h1/seizures`) parses as `{only: true}` plus a
 *    stray positional, and a `true` means "no value" to `list()`, which means "select everything"
 *    to restore.js — i.e. a typo silently widens the delete scope from one collection to a whole
 *    household. So a value-taking flag given with no `=` is a hard error, and so is an empty value:
 *    `--only=` is the same hazard written differently.
 *  * `booleanFlags` MUST NOT carry a value. The mirror image, and it is the more dangerous
 *    direction: `--allow-prod=false` used to land as the *string* `'false'`, which is truthy, so
 *    the two gates standing between a `--commit` run and the live project both opened for an
 *    operator who typed "false" while trying to be explicit. A boolean is set by naming it and
 *    unset by omitting it; `=anything` is refused rather than interpreted.
 *
 * An undeclared flag is refused too, because the same class of typo reaches the same hazard by a
 * different route: `--onyl=households/h1/seizures` leaves `only` empty, and an empty `only` means
 * "everything" to the one script that deletes. Silently ignoring an unrecognised flag means the
 * operator's stated intent and the run's actual scope can differ with nothing on screen to say so.
 *
 * `booleanFlags` is optional only so a caller that has not been audited keeps the old
 * value-flag-only behaviour rather than rejecting every boolean it declares nowhere; both scripts
 * in this package declare it, and any new one must.
 */
function parseArgs(argv, { valueFlags = [], booleanFlags = null } = {}) {
  const needsValue = new Set(valueFlags);
  const takesNoValue = booleanFlags ? new Set(booleanFlags) : null;
  const declared = (name) => needsValue.has(name) || (takesNoValue !== null && takesNoValue.has(name));
  const unknown = (name) => {
    const known = [...needsValue].map((f) => `--${f}=<value>`).concat([...takesNoValue].map((f) => `--${f}`));
    return new Error(
      `unknown flag --${name}. This script reads only: ${known.sort().join(' ')}. ` +
        'An unrecognised flag is refused rather than ignored: a misspelled --only or --codeindex ' +
        'would otherwise leave the run wider than what you typed, with nothing on screen to say so.'
    );
  };

  const flags = {};
  const positional = [];
  /**
   * A flag given twice is refused rather than resolved last-wins. Same thesis as the three rules
   * above: `--only=households/h1/seizures --only=codeIndex` silently ran with only the second, so
   * the operator's stated intent and the run's actual scope differed with nothing on screen to say
   * so. Last-wins happens to be the safe direction for `--only` (narrowing) and the wrong one for
   * `--project`, and neither is worth guessing at — nothing in this package means anything by a
   * repeat, and a comma-separated list is how both value flags take more than one thing.
   */
  const seen = (name) => {
    if (Object.prototype.hasOwnProperty.call(flags, name)) {
      throw new Error(
        `--${name} was given more than once. Which one wins is not something this parser guesses ` +
          `at: pass it once${needsValue.has(name) ? ' (--' + name + '=a,b for several values)' : ''}.`
      );
    }
  };
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
      if (takesNoValue !== null && !declared(body)) throw unknown(body);
      seen(body);
      flags[body] = true;
      continue;
    }

    const key = body.slice(0, eq);
    const value = body.slice(eq + 1);
    if (takesNoValue !== null && takesNoValue.has(key)) {
      throw new Error(
        `--${key} is a boolean flag and takes no value, but it was written --${key}=${value}. ` +
          `Write --${key} to enable it, or leave it out entirely to disable it. Accepting a value ` +
          `here would make --${key}=false mean the opposite of what it says: every non-empty value ` +
          'is truthy in JavaScript, so it would switch the flag ON.'
      );
    }
    if (takesNoValue !== null && !declared(key)) throw unknown(key);
    if (needsValue.has(key) && value === '') {
      throw new Error(`--${key}= was given with an empty value. Pass a real value or drop the flag.`);
    }
    seen(key);
    flags[key] = value;
  }
  return { flags, positional };
}

/**
 * A comma-separated flag value as a trimmed, de-duplicated list.
 *
 * `undefined` (flag absent) is the only input that yields `[]`. `true` cannot reach here for a
 * flag declared in `valueFlags` — parseArgs rejects it — but the guard stays so a caller that
 * forgets to declare one gets an empty list rather than `"true"` as a literal value.
 *
 * De-duplicated because a repeated entry is never meaningful to either caller and is harmful to
 * one: `backup.js --household=h1,h1` crawled h1 twice and counted its household document twice,
 * so the manifest claimed two households against a single dumped entry and every restore from
 * that dump then failed verification — after committing.
 */
const list = (value) =>
  value === undefined || value === true || value === ''
    ? []
    : [...new Set(String(value).split(',').map((s) => s.trim()).filter(Boolean))];

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

/**
 * One-line description of the credential initFirestore resolved, for the target banner.
 *
 * `kind: 'key-file'` means "the file GOOGLE_APPLICATION_CREDENTIALS named", which is not always a
 * service-account key — an operator can point it at gcloud user credentials. Say what the file
 * actually is, so the banner is not the thing that tells the operator the wrong story.
 */
function describeCredential(credential) {
  if (!credential) return 'unknown';
  if (credential.kind === 'emulator') return 'none (emulator)';
  if (credential.kind === 'adc') return `gcloud ADC (${credential.path})`;
  if (credential.type === 'authorized_user') return `user credentials (${credential.path})`;
  if (credential.type && credential.type !== 'service_account') {
    return `"${credential.type}" credential file (${credential.path})`;
  }
  return `service-account key (${credential.path})`;
}

/**
 * Exit with `code`, but never before what was printed has actually left the process.
 *
 * `process.exit()` discards whatever is still queued on stdout, and stdout is asynchronous
 * whenever it is a pipe rather than a TTY — so `node restore.js … | less` (or a slow `tee`, or any
 * reader that does not drain promptly) lost the tail of the output. Measured on this repo's own
 * output volume against a reader that pauses briefly: `process.exit()` delivered 0 of 4001 lines,
 * this delivers all 4001. The line lost is the last one printed, which is exactly the
 * `OK:`/`FAILED:` verdict README step 3 tells the operator to read before migration.md §7's
 * irreversible cleanup delete — and the more warnings a run prints, the more likely the loss.
 *
 * Setting `process.exitCode` alone would be the smaller change but risks the opposite failure: the
 * Admin SDK keeps its gRPC channel (and its keep-alive timer) open, so the event loop can outlive
 * `main()` and the command appears to hang after printing its verdict — and an operator mid-window
 * cannot tell a hang from an unfinished restore. So: flush, then exit for real. If the reader never
 * drains we stay alive waiting for it, which is the correct behaviour and the same as exitCode's.
 */
function exitWhenFlushed(code) {
  process.exitCode = code;
  // Empty write == "call me once everything queued ahead of this has gone to the OS". Nothing is
  // queued on a TTY (writes there are synchronous), so the common case exits immediately.
  const pending = [process.stdout, process.stderr].filter((s) => s.writableLength > 0);
  if (!pending.length) { process.exit(code); return; }
  let waiting = pending.length;
  for (const stream of pending) {
    stream.write('', () => { waiting -= 1; if (waiting === 0) process.exit(code); });
  }
}

module.exports = {
  parseArgs, list, log, warn, countTable, describeCredential, exitWhenFlushed, HEALTH_DATA_WARNING,
};
