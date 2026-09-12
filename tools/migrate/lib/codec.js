'use strict';

// Lossless JSON encoding of Firestore values.
//
// Why this is not just JSON.stringify(snap.data()):
//
//  * Firestore distinguishes integer from double at the storage layer. `JSON.stringify` collapses
//    them (a double 12.0 serialises as `12` and restores as an integer), so a naive dump silently
//    retypes fields on the way back in. The Admin SDK is initialised with `useBigInt: true` so
//    integers arrive as `BigInt` and doubles as `number`, and this codec tags each explicitly.
//  * `Timestamp`, `GeoPoint`, `DocumentReference` and `Bytes` have no JSON form at all. The legacy
//    shape uses none of them, but the target shape does (`observations.occurredAt` is a real
//    `Timestamp` — migration.md §3), and `backup.js` is also the tool that takes the fresh dump
//    immediately before the §7 cleanup delete, i.e. against the *new* shape.
//  * `NaN` / `Infinity` are valid Firestore doubles and are not valid JSON.
//
// Tag format: a single-key object whose key starts with '@'. A user map that happens to look like
// a tag is wrapped in `{"@map": ...}` so decoding is unambiguous in both directions.

const { Timestamp, GeoPoint, DocumentReference } = require('firebase-admin/firestore');

const TAGS = new Set(['@int', '@double', '@time', '@geo', '@ref', '@bytes', '@map']);

const isPlainObject = (v) =>
  typeof v === 'object' && v !== null && (Object.getPrototypeOf(v) === Object.prototype || Object.getPrototypeOf(v) === null);

/** True if `obj` is a single-key object whose key is one of our tags. */
function taggedKey(obj) {
  if (!isPlainObject(obj)) return null;
  const keys = Object.keys(obj);
  if (keys.length !== 1) return null;
  return TAGS.has(keys[0]) ? keys[0] : null;
}

function encodeValue(value) {
  if (value === null || value === undefined) return null;

  switch (typeof value) {
    case 'boolean':
    case 'string':
      return value;
    case 'bigint':
      // Stringified, not a JSON number: values beyond 2^53 would otherwise lose precision.
      return { '@int': value.toString() };
    case 'number':
      if (Number.isNaN(value)) return { '@double': 'NaN' };
      if (value === Infinity) return { '@double': 'Infinity' };
      if (value === -Infinity) return { '@double': '-Infinity' };
      // JSON.stringify(-0) is "0", so negative zero has to travel as a string to survive.
      if (value === 0 && 1 / value === -Infinity) return { '@double': '-0' };
      return { '@double': value };
    default:
      break;
  }

  if (Array.isArray(value)) return value.map(encodeValue);
  if (value instanceof Timestamp) {
    return { '@time': { s: value.seconds, n: value.nanoseconds } };
  }
  if (value instanceof GeoPoint) {
    return { '@geo': { lat: value.latitude, lng: value.longitude } };
  }
  if (value instanceof DocumentReference) {
    return { '@ref': value.path };
  }
  if (value instanceof Uint8Array || Buffer.isBuffer(value)) {
    return { '@bytes': Buffer.from(value).toString('base64') };
  }
  if (isPlainObject(value)) {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = encodeValue(v);
    return taggedKey(out) ? { '@map': out } : out;
  }

  throw new Error(`Unsupported Firestore value of type ${Object.prototype.toString.call(value)}`);
}

function decodeValue(value, firestore) {
  if (value === null) return null;
  if (typeof value === 'boolean' || typeof value === 'string') return value;
  if (typeof value === 'number') {
    // Only produced by a hand-edited dump. Treated as a double, which is what JSON means.
    return value;
  }
  if (Array.isArray(value)) return value.map((v) => decodeValue(v, firestore));

  const tag = taggedKey(value);
  switch (tag) {
    case '@int':
      return BigInt(value['@int']);
    case '@double': {
      const raw = value['@double'];
      if (raw === 'NaN') return NaN;
      if (raw === 'Infinity') return Infinity;
      if (raw === '-Infinity') return -Infinity;
      if (raw === '-0') return -0;
      // Anything else must be a JSON number. Without this check a hand-edited dump holding
      // {"@double": "12.5"} — the plausible edit, since the four cases above are themselves
      // strings — restores weightKg as the *string* "12.5", the verification gate's content
      // compare would have to be the only thing that notices, and the Kotlin `Double?` field
      // then deserialises as null on the device. Refuse instead.
      if (typeof raw !== 'number') {
        throw new Error(
          `Cannot decode dump value {"@double": ${JSON.stringify(raw)}}: a @double payload must be ` +
            'a JSON number, or one of the strings "NaN", "Infinity", "-Infinity", "-0".'
        );
      }
      return raw;
    }
    case '@time':
      return new Timestamp(value['@time'].s, value['@time'].n);
    case '@geo':
      return new GeoPoint(value['@geo'].lat, value['@geo'].lng);
    case '@ref':
      if (!firestore) throw new Error('decoding a DocumentReference needs a firestore instance');
      return firestore.doc(value['@ref']);
    case '@bytes':
      return Buffer.from(value['@bytes'], 'base64');
    case '@map':
      return decodeMap(value['@map'], firestore);
    default:
      if (isPlainObject(value)) return decodeMap(value, firestore);
      throw new Error(`Cannot decode dump value: ${JSON.stringify(value)}`);
  }
}

function decodeMap(obj, firestore) {
  const out = {};
  for (const [k, v] of Object.entries(obj)) out[k] = decodeValue(v, firestore);
  return out;
}

/** Encode a whole document's field map. */
const encodeDocument = (data) => {
  const out = {};
  for (const [k, v] of Object.entries(data)) out[k] = encodeValue(v);
  return out;
};

const decodeDocument = (data, firestore) => decodeMap(data, firestore);

module.exports = { encodeValue, decodeValue, encodeDocument, decodeDocument };

/**
 * Paths of every `@double` field whose value is an integral safe integer.
 *
 * These are the one class of value this tooling CANNOT round-trip, and it is a limit of the Node
 * Admin SDK, not of this codec: its serializer encodes any JS `number` that passes
 * `Number.isSafeInteger()` as an `integerValue` (@google-cloud/firestore serializer.js), so a
 * Firestore double of 12.0 comes back out of a restore as the integer 12. There is no API to force
 * a doubleValue. Negative zero and non-integral/huge doubles are unaffected (they are special-cased
 * or fail the safe-integer test).
 *
 * Practical impact is small — the only double in the shipped shape is `Pet.weightKg`, the Firebase
 * Android SDK widens an integer to a `Double?` field when reading, and Firestore's own numeric
 * comparisons span int and double — but it is a real retype and both scripts report it by field
 * path so nobody discovers it during verification.
 */
function collectIntegralDoubles(encoded, pathPrefix, acc) {
  if (encoded === null || typeof encoded === 'boolean' || typeof encoded === 'string') return acc;
  if (Array.isArray(encoded)) {
    encoded.forEach((v, i) => collectIntegralDoubles(v, `${pathPrefix}[${i}]`, acc));
    return acc;
  }
  const tag = taggedKey(encoded);
  if (tag === '@double') {
    // Shared with restore.js's content compare (isIntegralDouble, below) so the field the dump
    // reports as retyped and the field the verification gate tolerates are the same field.
    if (isIntegralDouble(encoded['@double'])) acc.push(pathPrefix);
    return acc;
  }
  if (tag === '@map') return collectIntegralDoubles(encoded['@map'], pathPrefix, acc);
  if (tag) return acc;
  if (isPlainObject(encoded)) {
    for (const [k, v] of Object.entries(encoded)) collectIntegralDoubles(v, `${pathPrefix}.${k}`, acc);
  }
  return acc;
}

module.exports.collectIntegralDoubles = collectIntegralDoubles;

/**
 * The one class of value a Node Admin SDK restore cannot reproduce: a `@double` whose payload is a
 * safe integer (see `collectIntegralDoubles` above) comes back as a Firestore integer. Shared by
 * the dump-time reporter and the restore-time content compare so the two cannot drift.
 */
const isIntegralDouble = (raw) =>
  typeof raw === 'number' && Number.isSafeInteger(raw) && !(raw === 0 && 1 / raw === -Infinity);

/** Structural equality for plain JSON — used on tag payloads, which hold no nested encoded values. */
function plainEqual(a, b) {
  if (Object.is(a, b)) return true;
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((v, i) => plainEqual(v, b[i]));
  }
  if (isPlainObject(a) && isPlainObject(b)) {
    const ka = Object.keys(a);
    const kb = Object.keys(b);
    return ka.length === kb.length
      && ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && plainEqual(a[k], b[k]));
  }
  return false;
}

/** How a value reads in a mismatch line. */
function describeEncoded(value) {
  const tag = taggedKey(value);
  if (tag === '@map') return 'a map';
  if (tag) return `${tag} ${JSON.stringify(value[tag])}`;
  if (Array.isArray(value)) return `an array of ${value.length}`;
  if (isPlainObject(value)) return 'a map';
  const json = JSON.stringify(value);
  return json === undefined ? String(value) : json;
}

/**
 * Deep-compare two values in this codec's encoded form — the dump's `collections[...].data` on one
 * side, a fresh `encodeDocument()` of what the target actually holds on the other.
 *
 * This is what makes restore.js's verification gate a claim about *content* and not just about
 * document identity. Both sides are already in the same encoded format (the verify crawl runs the
 * same `crawlDocument`/`encodeDocument` as the dump), so this is a comparison and not extra I/O.
 *
 * Exactly one difference is tolerated, and it is the documented one: `{"@double": 12}` in the dump
 * against `{"@int": "12"}` in the target. Every tolerated path is recorded in `out.retyped` so the
 * caller can report it and cross-check it against `manifest.integralDoubleFields` — a tolerance
 * that is invisible is indistinguishable from a gate that does not look.
 *
 * Paths are built exactly as `collectIntegralDoubles` builds them (`.key` for map keys, `[i]` for
 * array indices, nothing appended for the `@map` wrapper), so a path in `out.retyped` is directly
 * comparable to a `manifest.integralDoubleFields` entry.
 *
 * `out` is `{ diffs: string[], retyped: string[] }`.
 */
function compareEncoded(want, got, path, out) {
  const wantTag = taggedKey(want);
  const gotTag = taggedKey(got);

  if (
    wantTag === '@double' && gotTag === '@int'
    && isIntegralDouble(want['@double']) && got['@int'] === String(want['@double'])
  ) {
    out.retyped.push(path);
    return out;
  }

  if (wantTag || gotTag) {
    if (wantTag !== gotTag) {
      out.diffs.push(`${path}: dump has ${describeEncoded(want)}, target has ${describeEncoded(got)}`);
      return out;
    }
    if (wantTag === '@map') return compareEncodedMap(want['@map'], got['@map'], path, out);
    if (!plainEqual(want[wantTag], got[gotTag])) {
      out.diffs.push(`${path}: dump has ${describeEncoded(want)}, target has ${describeEncoded(got)}`);
    }
    return out;
  }

  if (Array.isArray(want) || Array.isArray(got)) {
    if (!Array.isArray(want) || !Array.isArray(got) || want.length !== got.length) {
      out.diffs.push(`${path}: dump has ${describeEncoded(want)}, target has ${describeEncoded(got)}`);
      return out;
    }
    for (let i = 0; i < want.length; i += 1) compareEncoded(want[i], got[i], `${path}[${i}]`, out);
    return out;
  }

  if (isPlainObject(want) || isPlainObject(got)) {
    if (!isPlainObject(want) || !isPlainObject(got)) {
      out.diffs.push(`${path}: dump has ${describeEncoded(want)}, target has ${describeEncoded(got)}`);
      return out;
    }
    return compareEncodedMap(want, got, path, out);
  }

  if (!Object.is(want, got)) {
    out.diffs.push(`${path}: dump has ${describeEncoded(want)}, target has ${describeEncoded(got)}`);
  }
  return out;
}

/** compareEncoded over a field map — also the entrypoint for a whole document. */
function compareEncodedMap(want, got, path, out) {
  for (const k of Object.keys(want)) {
    if (!Object.prototype.hasOwnProperty.call(got, k)) {
      out.diffs.push(`${path}.${k}: in the dump (${describeEncoded(want[k])}), absent from the target`);
      continue;
    }
    compareEncoded(want[k], got[k], `${path}.${k}`, out);
  }
  for (const k of Object.keys(got)) {
    if (!Object.prototype.hasOwnProperty.call(want, k)) {
      out.diffs.push(`${path}.${k}: in the target (${describeEncoded(got[k])}), absent from the dump`);
    }
  }
  return out;
}

/** A fresh `{ diffs, retyped }` accumulator for compareEncoded. */
const newComparison = () => ({ diffs: [], retyped: [] });

module.exports.isIntegralDouble = isIntegralDouble;
module.exports.compareEncoded = compareEncoded;
module.exports.compareEncodedDocument = (want, got, docPath, out) =>
  compareEncodedMap(want || {}, got || {}, docPath, out);
module.exports.newComparison = newComparison;
