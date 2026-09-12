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
    const raw = encoded['@double'];
    const negativeZero = raw === 0 && 1 / raw === 1 / -0;
    if (typeof raw === 'number' && Number.isSafeInteger(raw) && !negativeZero) acc.push(pathPrefix);
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
