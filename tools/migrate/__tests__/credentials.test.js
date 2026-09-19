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
