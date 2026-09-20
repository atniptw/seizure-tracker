'use strict';

// Credential resolution for the live-project path (issue #5 follow-up).
//
// `admin.credential.applicationDefault()` accepts both a service-account key file and gcloud
// Application Default Credentials, so the precondition in initFirestore must accept both too — the
// original version hard-required GOOGLE_APPLICATION_CREDENTIALS and so refused to start on the very
// credential the code underneath already supported. ADC is the preferred path for the rehearsal:
// `security-privacy.md §2.3` treats a downloaded key as an actor with the reach of the whole
// database, while ADC leaves nothing long-lived on disk and revokes with one command.
//
// Added alongside the existing round-trip suite, not in place of any of it. These cases touch no
// Firestore and no network: they exercise resolveCredentialSource() directly, and the fake ADC file
// lives in a temp CLOUDSDK_CONFIG so the operator's real gcloud config is never read or written.
//
// Every credential path here is a **real temp file** with real (invented) contents. An earlier
// version pointed GOOGLE_APPLICATION_CREDENTIALS at a path that did not exist and asserted the
// result was a key file, which encoded the round-5 bug in its own test title: the function was
// deciding on the variable's name rather than the file's `type`, so an operator who pointed
// GOOGLE_APPLICATION_CREDENTIALS at a gcloud `authorized_user` file skipped the project-id
// requirement and died inside the SDK on "Client is not yet ready to issue requests".
//
// No value below is a real credential: the keys are syntactically shaped and semantically junk.

const fs = require('fs');
const os = require('os');
const path = require('path');

const { resolveCredentialSource, adcFilePath } = require('../lib/firestore');
const { describeCredential } = require('../lib/cli');

const ENV_KEYS = ['GOOGLE_APPLICATION_CREDENTIALS', 'CLOUDSDK_CONFIG'];
const saved = {};

beforeEach(() => {
  for (const key of ENV_KEYS) saved[key] = process.env[key];
  for (const key of ENV_KEYS) delete process.env[key];
});

afterEach(() => {
  for (const key of ENV_KEYS) {
    if (saved[key] === undefined) delete process.env[key];
    else process.env[key] = saved[key];
  }
});

// Removed when this file's tests finish: one of these directories holds a (fake) credential file,
// and a suite that leaves credential-shaped files in /tmp teaches the wrong habit even when the
// contents are invented.
const tmpConfigDirs = [];
afterAll(() => {
  while (tmpConfigDirs.length) fs.rmSync(tmpConfigDirs.pop(), { recursive: true, force: true });
});

/** The contents gcloud writes for user credentials. Invented values; not a credential. */
const AUTHORIZED_USER = {
  type: 'authorized_user',
  client_id: 'test-client-id.apps.googleusercontent.com',
  client_secret: 'not-a-real-secret',
  refresh_token: 'not-a-real-refresh-token',
};

/** The shape of a downloaded service-account key. Invented values; not a credential. */
function serviceAccountKey(projectId) {
  return {
    type: 'service_account',
    project_id: projectId,
    private_key_id: 'not-a-real-key-id',
    private_key: '-----BEGIN PRIVATE KEY-----\nNOT-A-REAL-KEY\n-----END PRIVATE KEY-----\n',  // id-scan:ignore — fake fixture, not a key
    client_email: `not-a-real-account@${projectId}.iam.gserviceaccount.com`,
    client_id: '000000000000000000000',
  };
}

/** A temp CLOUDSDK_CONFIG dir, optionally holding a plausible gcloud ADC file. */
function fakeCloudSdkConfig({ withAdc }) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'seizuretracker-gcloud-'));
  tmpConfigDirs.push(dir);
  if (withAdc) {
    fs.writeFileSync(
      path.join(dir, 'application_default_credentials.json'),
      JSON.stringify(AUTHORIZED_USER),
      { mode: 0o600 }
    );
  }
  process.env.CLOUDSDK_CONFIG = dir;
  return dir;
}

/**
 * Write `contents` to a temp file and point GOOGLE_APPLICATION_CREDENTIALS at it — the route an
 * operator takes with `export GOOGLE_APPLICATION_CREDENTIALS=/path/to/whatever.json`. `contents`
 * is an object (serialised) or a raw string, so the not-JSON case can be expressed too.
 */
function fakeKeyFileEnv(contents, name = 'key.json') {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'seizuretracker-creds-'));
  tmpConfigDirs.push(dir);
  const file = path.join(dir, name);
  fs.writeFileSync(file, typeof contents === 'string' ? contents : JSON.stringify(contents), {
    mode: 0o600,
  });
  process.env.GOOGLE_APPLICATION_CREDENTIALS = file;
  return file;
}

describe('adcFilePath', () => {
  test('defaults to the gcloud well-known path under the home directory', () => {
    expect(adcFilePath()).toBe(
      path.join(os.homedir(), '.config', 'gcloud', 'application_default_credentials.json')
    );
  });

  test('honours CLOUDSDK_CONFIG, like gcloud itself', () => {
    const dir = fakeCloudSdkConfig({ withAdc: false });
    expect(adcFilePath()).toBe(path.join(dir, 'application_default_credentials.json'));
  });
});

describe('resolveCredentialSource — emulator', () => {
  test('needs no credentials at all', () => {
    expect(resolveCredentialSource({ emulatorHost: '127.0.0.1:8080', projectId: 'demo-x' }))
      .toEqual({ kind: 'emulator' });
  });

  test('still requires an explicit project id', () => {
    expect(() => resolveCredentialSource({ emulatorHost: '127.0.0.1:8080', projectId: undefined }))
      .toThrow(/--project/);
  });
});

describe('resolveCredentialSource — live project', () => {
  test('accepts a service-account key file from GOOGLE_APPLICATION_CREDENTIALS', () => {
    const file = fakeKeyFileEnv(serviceAccountKey('seizure-tracker-x'));
    expect(resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' }))
      .toEqual({ kind: 'key-file', path: file, type: 'service_account', projectId: 'seizure-tracker-x' });
  });

  test('accepts a service-account key with no --project — the key carries its own project_id', () => {
    const file = fakeKeyFileEnv(serviceAccountKey('seizure-tracker-x'));
    expect(resolveCredentialSource({ emulatorHost: null, projectId: undefined }))
      .toEqual({ kind: 'key-file', path: file, type: 'service_account', projectId: 'seizure-tracker-x' });
  });

  // The round-5 finding. GOOGLE_APPLICATION_CREDENTIALS accepts any ADC file, and gcloud's own is
  // `authorized_user` — which carries no project_id. Deciding "key file, therefore it names its
  // project" from the variable's name skipped the requirement and pushed the failure into the SDK,
  // as "Client is not yet ready to issue requests", at the first RPC inside the cutover window.
  test('refuses an authorized_user file via GOOGLE_APPLICATION_CREDENTIALS with no project id', () => {
    const file = fakeKeyFileEnv(AUTHORIZED_USER, 'application_default_credentials.json');
    expect(() => resolveCredentialSource({ emulatorHost: null, projectId: undefined }))
      .toThrow(/but no project id/);
    expect(() => resolveCredentialSource({ emulatorHost: null, projectId: undefined }))
      .toThrow(/--project=<id> or set GOOGLE_CLOUD_PROJECT/);
    // The same refusal the ADC route gives, and it names the file so the operator can see which
    // one it read rather than which variable pointed at it.
    expect(() => resolveCredentialSource({ emulatorHost: null, projectId: undefined }))
      .toThrow(new RegExp(`authorized_user[\\s\\S]*${file.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`));
  });

  test('accepts an authorized_user file via GOOGLE_APPLICATION_CREDENTIALS when --project is given', () => {
    const file = fakeKeyFileEnv(AUTHORIZED_USER, 'application_default_credentials.json');
    expect(resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' }))
      .toEqual({ kind: 'key-file', path: file, type: 'authorized_user', projectId: undefined });
  });

  // A quota project is a billing target, not the database to operate on; inferring one from it
  // would pick a project the operator never named — on the tool whose other mode deletes.
  test('does not take project_id from a non-service_account file', () => {
    fakeKeyFileEnv({ ...AUTHORIZED_USER, project_id: 'some-other-project' });
    expect(() => resolveCredentialSource({ emulatorHost: null, projectId: undefined }))
      .toThrow(/but no project id/);
  });

  test('refuses a credential file that is missing, naming the path', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'seizuretracker-creds-'));
    tmpConfigDirs.push(dir);
    const missing = path.join(dir, 'not-here.json');
    process.env.GOOGLE_APPLICATION_CREDENTIALS = missing;
    expect(() => resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' }))
      .toThrow(/Could not read the credential file/);
  });

  test('refuses a credential file that is not JSON', () => {
    fakeKeyFileEnv('this is not json');
    expect(() => resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' }))
      .toThrow(/not valid JSON/);
  });

  test('accepts gcloud ADC with an explicit project id, and reports the file it found', () => {
    const dir = fakeCloudSdkConfig({ withAdc: true });
    expect(resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' }))
      .toEqual({
        kind: 'adc',
        path: path.join(dir, 'application_default_credentials.json'),
        type: 'authorized_user',
        projectId: undefined,
      });
  });

  test('rejects gcloud ADC with no project id — ADC carries none, so the SDK would fail late', () => {
    fakeCloudSdkConfig({ withAdc: true });
    expect(() => resolveCredentialSource({ emulatorHost: null, projectId: undefined }))
      .toThrow(/ADC carries no project id/);
    expect(() => resolveCredentialSource({ emulatorHost: null, projectId: undefined }))
      .toThrow(/--project=<id> or set GOOGLE_CLOUD_PROJECT/);
  });

  test('prefers the key file when both sources are present (applicationDefault does too)', () => {
    fakeCloudSdkConfig({ withAdc: true });
    const file = fakeKeyFileEnv(serviceAccountKey('seizure-tracker-x'));
    const resolved = resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' });
    expect(resolved.kind).toBe('key-file');
    expect(resolved.path).toBe(file);
  });

  test('with neither source, names all three options and the ADC path it looked at', () => {
    const dir = fakeCloudSdkConfig({ withAdc: false });
    let error;
    try {
      resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' });
    } catch (e) {
      error = e;
    }
    expect(error).toBeDefined();
    expect(error.message).toContain('FIRESTORE_EMULATOR_HOST');
    expect(error.message).toContain('GOOGLE_APPLICATION_CREDENTIALS');
    expect(error.message).toContain('gcloud auth application-default login');
    expect(error.message).toContain(path.join(dir, 'application_default_credentials.json'));
  });
});

// --- The whole-resolver matrix (review round 6) ------------------------------------------------
//
// Every route × every file type × project-supplied-or-not, each row stating its exact outcome.
// The cases above test the same function; this exists because they test it a route at a time, and
// twice now a fix verified on the route a finding named has changed the *sibling* route unnoticed:
//
// - round 5: the decision moved from "which variable named the file" to "what the file's type is",
//   which fixed GOOGLE_APPLICATION_CREDENTIALS→authorized_user and, on the ADC branch, turned
//   "always require a project id" into "require one unless the file names one";
// - so a `service_account` key sitting at the gcloud well-known ADC path supplied the project by
//   itself, and `restore.js dump.json --allow-prod --commit` — the one irreversible command here —
//   could delete and rewrite a project the operator never typed. `--allow-prod` names no project.
//
// Neither round's ADC fixture was ever anything but `authorized_user`, so nothing failed. A matrix
// cannot have that gap: a row exists for each combination whether or not a finding pointed at it,
// and the outcomes are written out rather than computed, so a test cannot agree with a bug by
// sharing its reasoning.
//
// No value below is a real credential; every path is a real temp file with invented contents.

/** A well-formed external_account (workload-identity) file — a real type applicationDefault() takes. */
const EXTERNAL_ACCOUNT = {
  type: 'external_account',
  audience: '//iam.googleapis.com/projects/000000000000/locations/global/workloadIdentityPools/not-a-real-pool/providers/not-a-real-provider',
  subject_token_type: 'urn:ietf:params:oauth:token-type:jwt',
  token_url: 'https://sts.googleapis.com/v1/token',
  credential_source: { file: '/dev/null' },
};

/** Sentinels for the two "there is no readable file" fixtures. */
const MISSING = Symbol('no file written at all');
const UNREADABLE = Symbol('a directory where a file should be — readFileSync gives EISDIR');

// The two project ids are deliberately different, so a row's expected `projectId` says which of
// the two the resolver picked rather than being ambiguous between them.
const TYPED = 'seizure-tracker-typed';      // what the operator passed as --project
const IN_KEY = 'seizure-tracker-in-the-key'; // what the fixture key file's own project_id says

/** Write one fixture at `at`; returns the path the resolver will be pointed at. */
function writeFixture(at, file) {
  if (file === MISSING) return at;
  if (file === UNREADABLE) {
    fs.mkdirSync(at);
    return at;
  }
  fs.writeFileSync(at, typeof file === 'string' ? file : JSON.stringify(file), { mode: 0o600 });
  return at;
}

function tmpDir() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'seizuretracker-creds-'));
  tmpConfigDirs.push(dir);
  return dir;
}

/**
 * Put the environment into the state the row describes and return what to call the resolver with.
 *
 * CLOUDSDK_CONFIG is redirected on **every** row, including the key-file ones: a row that means
 * "no ADC file exists" must not silently pass because the machine running the suite happens to
 * have real ADC on it, or fail because it does not.
 */
function arrange(row) {
  const configDir = tmpDir();
  process.env.CLOUDSDK_CONFIG = configDir;
  const adcPath = path.join(configDir, 'application_default_credentials.json');

  let credPath = null;
  if (row.route === 'adc') credPath = writeFixture(adcPath, row.file);
  if (row.route === 'key-file' || row.ambient === 'key-file') {
    const at = writeFixture(path.join(tmpDir(), 'creds.json'), row.file);
    process.env.GOOGLE_APPLICATION_CREDENTIALS = at;
    if (row.route === 'key-file') credPath = at;
  }
  if (row.ambient === 'adc') writeFixture(adcPath, AUTHORIZED_USER);

  return {
    args: {
      emulatorHost: row.route === 'emulator' ? '127.0.0.1:8080' : null,
      projectId: row.project,
    },
    credPath,
  };
}

const MATRIX = [
  // --- emulator: no credential is read at all, but the project must still be named -------------
  {
    name: 'emulator + --project — no credential of any kind is consulted',
    route: 'emulator', project: TYPED,
    resolves: { kind: 'emulator' },
  },
  {
    name: 'emulator, no --project — refused',
    route: 'emulator', project: undefined,
    refuses: [/emulator needs an explicit --project/],
  },
  {
    name: 'emulator + --project, with a service-account key in the environment — key ignored',
    route: 'emulator', ambient: 'key-file', file: () => serviceAccountKey(IN_KEY), project: TYPED,
    resolves: { kind: 'emulator' },
  },
  {
    name: 'emulator + --project, with an ADC file on disk — ADC ignored',
    route: 'emulator', ambient: 'adc', project: TYPED,
    resolves: { kind: 'emulator' },
  },

  // --- GOOGLE_APPLICATION_CREDENTIALS: a file the operator named in this command ---------------
  // This is the one route where the file may supply the project id, and only for a
  // service-account key. Unchanged by round 6.
  {
    name: 'key file: service_account + --project — resolves; initFirestore prefers the typed id',
    route: 'key-file', file: () => serviceAccountKey(IN_KEY), project: TYPED,
    resolves: { kind: 'key-file', type: 'service_account', projectId: IN_KEY },
  },
  {
    name: 'key file: service_account, no --project — the key names its own project, so accepted',
    route: 'key-file', file: () => serviceAccountKey(IN_KEY), project: undefined,
    resolves: { kind: 'key-file', type: 'service_account', projectId: IN_KEY },
  },
  {
    name: 'key file: authorized_user + --project — accepted, project id NOT taken from the file',
    route: 'key-file', file: () => AUTHORIZED_USER, project: TYPED,
    resolves: { kind: 'key-file', type: 'authorized_user', projectId: undefined },
  },
  {
    name: 'key file: authorized_user, no --project — refused (round 5)',
    route: 'key-file', file: () => AUTHORIZED_USER, project: undefined,
    refuses: [/but no project id/, /type "authorized_user"/, /--project=<id> or set GOOGLE_CLOUD_PROJECT/],
  },
  {
    name: 'key file: authorized_user carrying a project_id, no --project — still refused',
    route: 'key-file', file: () => ({ ...AUTHORIZED_USER, project_id: 'a-quota-project' }), project: undefined,
    refuses: [/but no project id/],
  },
  {
    name: 'key file: external_account + --project — accepted, described by its own type',
    route: 'key-file', file: () => EXTERNAL_ACCOUNT, project: TYPED,
    resolves: { kind: 'key-file', type: 'external_account', projectId: undefined },
  },
  {
    name: 'key file: external_account, no --project — refused, naming the type',
    route: 'key-file', file: () => EXTERNAL_ACCOUNT, project: undefined,
    refuses: [/but no project id/, /type "external_account"/],
  },

  // --- the well-known ADC path: ambient, so --project is required unconditionally --------------
  // Nothing found here may choose the project, whatever its type. The operator did not name this
  // file in the command; `restore.js --allow-prod --commit` deletes and rewrites whatever it is
  // pointed at, and --allow-prod names no project.
  {
    name: 'ADC path: service_account key, no --project — REFUSED (round 6 blocking regression)',
    route: 'adc', file: () => serviceAccountKey(IN_KEY), project: undefined,
    refuses: [
      /but no project id/,
      /type "service_account"/,
      /ambient, not something you named in this command/,
      /--project=<id> or set GOOGLE_CLOUD_PROJECT/,
    ],
  },
  {
    name: 'ADC path: service_account key + --project — the typed id is the only candidate',
    route: 'adc', file: () => serviceAccountKey(IN_KEY), project: TYPED,
    // projectId: undefined even though the file carries IN_KEY. The resolver never offers it, so
    // initFirestore's `projectId || credential.projectId` cannot fall back to a project the
    // operator did not type — which is also the whole answer to "what if the two disagree".
    resolves: { kind: 'adc', type: 'service_account', projectId: undefined },
  },
  {
    name: 'ADC path: authorized_user + --project — accepted',
    route: 'adc', file: () => AUTHORIZED_USER, project: TYPED,
    resolves: { kind: 'adc', type: 'authorized_user', projectId: undefined },
  },
  {
    name: 'ADC path: authorized_user, no --project — refused',
    route: 'adc', file: () => AUTHORIZED_USER, project: undefined,
    refuses: [/but no project id/, /ADC carries no project id/],
  },
  {
    name: 'ADC path: external_account + --project — accepted, described by its own type',
    route: 'adc', file: () => EXTERNAL_ACCOUNT, project: TYPED,
    resolves: { kind: 'adc', type: 'external_account', projectId: undefined },
  },
  {
    name: 'ADC path: external_account, no --project — refused',
    route: 'adc', file: () => EXTERNAL_ACCOUNT, project: undefined,
    refuses: [/but no project id/, /type "external_account"/],
  },

  // --- a file with no `type` is not a credential file, on either route ------------------------
  // Refused before the project-id check, so the message is the same with or without --project: the
  // complaint is about the file, and "supply --project" would be useless advice. Before this, any
  // JSON at all plus --project resolved, the banner asserted "service-account key", and the SDK
  // failed at the first RPC — the exact late failure readCredentialFile exists to pre-empt.
  {
    name: 'key file: JSON with no "type" + --project — refused, naming the path',
    route: 'key-file', file: () => ({ client_id: 'not-a-real-client-id', note: 'not a credential' }), project: TYPED,
    refuses: [/has no "type" field/, /"type": "service_account"/, /gcloud auth application-default login/],  // id-scan:ignore — regex naming the token the guard hunts, not a key
  },
  {
    name: 'key file: JSON with no "type", no --project — same refusal, not the project one',
    route: 'key-file', file: () => ({ client_id: 'not-a-real-client-id' }), project: undefined,
    refuses: [/has no "type" field/],
    alsoNot: [/but no project id/],
  },
  {
    name: 'key file: "type" present but empty — refused',
    route: 'key-file', file: () => ({ type: '', project_id: 'a-project' }), project: TYPED,
    refuses: [/has no "type" field/],
  },
  {
    name: 'key file: valid JSON that is not an object at all (an array) — refused',
    route: 'key-file', file: '[]', project: TYPED,
    refuses: [/has no "type" field/],
  },
  {
    name: 'key file: valid JSON that is a bare string — refused',
    route: 'key-file', file: '"not-a-credential"', project: TYPED,
    refuses: [/has no "type" field/],
  },
  {
    name: 'ADC path: JSON with no "type" + --project — refused',
    route: 'adc', file: () => ({ note: 'not a credential' }), project: TYPED,
    refuses: [/has no "type" field/],
  },
  {
    name: 'ADC path: JSON with no "type", no --project — the file complaint comes first',
    route: 'adc', file: () => ({ note: 'not a credential' }), project: undefined,
    refuses: [/has no "type" field/],
    alsoNot: [/but no project id/],
  },

  // --- the file cannot be read or parsed: fatal here, before any RPC, naming the path ----------
  // These land before the project-id check on both routes, so each is tested with and without one.
  {
    name: 'key file: path does not exist + --project — refused, naming the path',
    route: 'key-file', file: MISSING, project: TYPED,
    refuses: [/Could not read the credential file/],
  },
  {
    name: 'key file: path does not exist, no --project — the read failure comes first',
    route: 'key-file', file: MISSING, project: undefined,
    refuses: [/Could not read the credential file/],
  },
  {
    name: 'key file: unreadable (a directory at that path) + --project — refused',
    route: 'key-file', file: UNREADABLE, project: TYPED,
    refuses: [/Could not read the credential file/],
  },
  {
    name: 'key file: not JSON + --project — refused',
    route: 'key-file', file: 'this is not json', project: TYPED,
    refuses: [/is not valid JSON/],
  },
  {
    name: 'key file: not JSON, no --project — the parse failure comes first',
    route: 'key-file', file: 'this is not json', project: undefined,
    refuses: [/is not valid JSON/],
  },
  {
    name: 'ADC path: unreadable (a directory at that path) + --project — refused',
    route: 'adc', file: UNREADABLE, project: TYPED,
    refuses: [/Could not read the credential file/],
  },
  {
    name: 'ADC path: not JSON + --project — refused',
    route: 'adc', file: 'this is not json', project: TYPED,
    refuses: [/is not valid JSON/],
  },
  {
    name: 'ADC path: not JSON, no --project — the parse failure comes first',
    route: 'adc', file: 'this is not json', project: undefined,
    refuses: [/is not valid JSON/],
  },

  // --- nothing at all ------------------------------------------------------------------------
  {
    name: 'no emulator, no GOOGLE_APPLICATION_CREDENTIALS, no ADC file — names all three options',
    route: 'none', project: TYPED,
    refuses: [/No credentials/, /FIRESTORE_EMULATOR_HOST/, /gcloud auth application-default login/],
  },
  {
    name: 'no credentials and no --project — still the no-credentials refusal',
    route: 'none', project: undefined,
    refuses: [/No credentials/],
  },
];

describe('resolveCredentialSource — the whole matrix', () => {
  test.each(MATRIX.map((row) => [row.name, row]))('%s', (_name, row) => {
    const { args, credPath } = arrange({ ...row, file: typeof row.file === 'function' ? row.file() : row.file });

    if (row.refuses) {
      for (const pattern of row.refuses) {
        expect(() => resolveCredentialSource(args)).toThrow(pattern);
      }
      // Which refusal it is matters as much as that it refused: a row that should complain about
      // the file must not instead tell the operator to pass --project.
      for (const pattern of row.alsoNot || []) {
        expect(() => resolveCredentialSource(args)).not.toThrow(pattern);
      }
      // Every refusal names the file it read, when there was one to read.
      if (credPath) expect(() => resolveCredentialSource(args)).toThrow(credPath);
      return;
    }

    const expected = row.resolves.kind === 'emulator'
      ? { kind: 'emulator' }
      : { ...row.resolves, path: credPath };
    expect(resolveCredentialSource(args)).toEqual(expected);
  });

  test('a credential-file refusal never quotes the file, only its path', () => {
    // The first 10 characters of a document are echoed by V8 when JSON.parse fails at the very
    // start: JSON.parse('qqqSECRET…') gives `Unexpected token 'q', "qqqSECRET"... is not valid
    // JSON`. A credential file is secret material — a raw token file pointed at by mistake would
    // put its opening characters in the operator's terminal — and the operator can read their own
    // file, so the message names the path and stops there.
    const contents = 'qqqSECRETqqq — standing in for a raw token file, not JSON, not a credential';
    const file = fakeKeyFileEnv(contents);

    let nativeMessage;
    try {
      JSON.parse(contents);
    } catch (err) {
      nativeMessage = err.message;
    }
    // Guard against a vacuous test: if this Node stops echoing, the assertions below prove nothing
    // and should be retired rather than left looking load-bearing.
    expect(nativeMessage).toContain('qqqSECRET');

    let error;
    try {
      resolveCredentialSource({ emulatorHost: null, projectId: TYPED });
    } catch (err) {
      error = err;
    }
    expect(error.message).toContain(file);
    expect(error.message).toContain('is not valid JSON');
    expect(error.message).not.toContain('qqq');
    expect(error.message).not.toContain('SECRET');
  });

  test('the matrix covers every route the resolver can take', () => {
    expect(new Set(MATRIX.map((r) => r.route))).toEqual(new Set(['emulator', 'key-file', 'adc', 'none']));
    // Both file routes, both project states, for each type this tool can meet.
    for (const route of ['key-file', 'adc']) {
      for (const type of ['service_account', 'authorized_user', 'external_account']) {
        for (const project of [TYPED, undefined]) {
          const covered = MATRIX.filter((r) => r.route === route && r.project === project)
            .some((r) => typeof r.file === 'function' && r.file().type === type);
          expect({ route, type, project: project || 'none', covered }).toEqual({ route, type, project: project || 'none', covered: true });
        }
      }
    }
    // And every type whose absence hid a defect: no `type` at all, on both file routes.
    for (const route of ['key-file', 'adc']) {
      expect(MATRIX.some((r) => r.route === route && (r.refuses || []).some((p) => String(p).includes('type'))))
        .toBe(true);
    }
  });
});

// --- the banner (lib/cli.js) -------------------------------------------------------------------
//
// Lives here rather than in a cli test file because the thing under test is the pair: what
// resolveCredentialSource decided, and what the operator is then told it decided. Fed the real
// resolver output for that reason — a hand-built credential object could agree with the banner
// while neither matched what the tool actually resolves.
//
// `Creds:` is the line README.md tells the operator to read to know whether they are running on a
// revocable user credential or on a downloaded key with the reach of the whole database
// (`security-privacy.md §2.3`). Describing the *location* and calling it the *type* is how a
// service-account key at the ADC path printed `gcloud ADC (…)` and looked like the safe option.

describe('describeCredential — every route names the actual file type', () => {
  const BANNERS = [
    {
      route: 'emulator', project: TYPED,
      expected: () => 'none (emulator)',
    },
    {
      route: 'adc', file: () => serviceAccountKey(IN_KEY), project: TYPED,
      expected: (p) => `service-account key at the gcloud ADC path (${p})`,
    },
    {
      route: 'adc', file: () => AUTHORIZED_USER, project: TYPED,
      expected: (p) => `user credentials at the gcloud ADC path (${p})`,
    },
    {
      route: 'adc', file: () => EXTERNAL_ACCOUNT, project: TYPED,
      expected: (p) => `"external_account" credential file at the gcloud ADC path (${p})`,
    },
    {
      route: 'key-file', file: () => serviceAccountKey(IN_KEY), project: TYPED,
      expected: (p) => `service-account key from GOOGLE_APPLICATION_CREDENTIALS (${p})`,
    },
    {
      route: 'key-file', file: () => AUTHORIZED_USER, project: TYPED,
      expected: (p) => `user credentials from GOOGLE_APPLICATION_CREDENTIALS (${p})`,
    },
    {
      route: 'key-file', file: () => EXTERNAL_ACCOUNT, project: TYPED,
      expected: (p) => `"external_account" credential file from GOOGLE_APPLICATION_CREDENTIALS (${p})`,
    },
  ];

  test.each(BANNERS.map((row) => [`${row.route}: ${row.file ? row.file().type : 'no file'}`, row]))(
    '%s',
    (_name, row) => {
      const { args, credPath } = arrange({ ...row, file: row.file ? row.file() : undefined });
      const credential = resolveCredentialSource(args);
      expect(describeCredential(credential)).toBe(row.expected(credPath));
    }
  );

  test('a service-account key at the ADC path is not described as plain "gcloud ADC"', () => {
    const { args } = arrange({ route: 'adc', file: serviceAccountKey(IN_KEY), project: TYPED });
    const line = describeCredential(resolveCredentialSource(args));
    expect(line).toMatch(/^service-account key/);
    expect(line).not.toMatch(/^gcloud ADC/);
  });

  test('no credential resolved yet reads as unknown rather than as anything reassuring', () => {
    expect(describeCredential(null)).toBe('unknown');
    expect(describeCredential(undefined)).toBe('unknown');
  });
});
