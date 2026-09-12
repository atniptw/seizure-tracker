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

/** A temp CLOUDSDK_CONFIG dir, optionally holding a plausible gcloud ADC file. */
function fakeCloudSdkConfig({ withAdc }) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'seizuretracker-gcloud-'));
  if (withAdc) {
    fs.writeFileSync(
      path.join(dir, 'application_default_credentials.json'),
      JSON.stringify({
        type: 'authorized_user',
        client_id: 'test-client-id.apps.googleusercontent.com',
        client_secret: 'test-secret',
        refresh_token: 'test-refresh-token',
      }),
      { mode: 0o600 }
    );
  }
  process.env.CLOUDSDK_CONFIG = dir;
  return dir;
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
    process.env.GOOGLE_APPLICATION_CREDENTIALS = '/abs/path/prod-service-account.json';
    expect(resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' }))
      .toEqual({ kind: 'key-file', path: '/abs/path/prod-service-account.json' });
  });

  test('accepts a key file without a project id — the key carries its own project_id', () => {
    process.env.GOOGLE_APPLICATION_CREDENTIALS = '/abs/path/prod-service-account.json';
    expect(resolveCredentialSource({ emulatorHost: null, projectId: undefined }).kind)
      .toBe('key-file');
  });

  test('accepts gcloud ADC with an explicit project id, and reports the file it found', () => {
    const dir = fakeCloudSdkConfig({ withAdc: true });
    expect(resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' }))
      .toEqual({ kind: 'adc', path: path.join(dir, 'application_default_credentials.json') });
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
    process.env.GOOGLE_APPLICATION_CREDENTIALS = '/abs/path/prod-service-account.json';
    expect(resolveCredentialSource({ emulatorHost: null, projectId: 'seizure-tracker-x' }).kind)
      .toBe('key-file');
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
