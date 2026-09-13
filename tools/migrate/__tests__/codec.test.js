'use strict';

// Unit tests for the decode-path guard and the content comparator added in response to the #5
// pre-merge review. No Firestore, no emulator, no network — these exercise lib/codec.js directly.
//
// Why they exist:
//  * The review found `decodeValue`'s `@double` case returned an unrecognised payload verbatim, so
//    a hand-edited `{"@double": "12.5"}` restored `weightKg` as the *string* "12.5" and the Kotlin
//    `Double?` field then read as `null` on the device. Hand-edited dumps are explicitly
//    contemplated, so the decode path has to refuse that rather than pass it through.
//  * The verification gate used to compare document ids and counts only. `compareEncoded` is what
//    makes it a claim about content, so its one tolerance — and its refusal to tolerate anything
//    else — is the part worth pinning down.

const {
  decodeValue, decodeDocument, encodeValue, collectIntegralDoubles,
  compareEncoded, compareEncodedDocument, newComparison, isIntegralDouble,
} = require('../lib/codec');
const { assertUseBigInt } = require('../lib/firestore');

describe('decodeValue @double payload validation', () => {
  test('accepts a JSON number and the four special-case tokens', () => {
    expect(decodeValue({ '@double': 12.5 })).toBe(12.5);
    expect(decodeValue({ '@double': 0 })).toBe(0);
    expect(Number.isNaN(decodeValue({ '@double': 'NaN' }))).toBe(true);
    expect(decodeValue({ '@double': 'Infinity' })).toBe(Infinity);
    expect(decodeValue({ '@double': '-Infinity' })).toBe(-Infinity);
    expect(Object.is(decodeValue({ '@double': '-0' }), -0)).toBe(true);
  });

  test('refuses a stringified number rather than writing a string to Firestore', () => {
    // The plausible hand edit, since the four tokens above are themselves strings. Pre-fix this
    // returned "12.5" verbatim and `weightKg` landed as a string, reading back null on the device.
    expect(() => decodeValue({ '@double': '12.5' })).toThrow(/@double.*must be/s);
    expect(() => decodeDocument({ weightKg: { '@double': '12.5' } })).toThrow(/"12.5"/);
  });

  test('refuses any other non-number payload', () => {
    for (const bad of [true, null, [], {}, '12', 'twelve']) {
      expect(() => decodeValue({ '@double': bad })).toThrow(/@double/);
    }
  });
});

describe('the other two number shapes decode used to accept', () => {
  // Both decoded to the RIGHT value, so the round trip was correct — but `compareEncoded` has no
  // untagged-number case and compares @int payloads exactly, so the verification gate reported a
  // difference and exited 1 over correct data, after the irreversible delete had committed. The
  // refusal is at decode (during planning, before anything is touched) rather than a normalisation
  // in the comparator, which keeps the on-disk format exactly one thing: every number tagged, every
  // @int payload a decimal string.
  test('a bare JSON number is refused, and the message names both replacements', () => {
    expect(() => decodeValue(12.5)).toThrow(/bare JSON number/);
    expect(() => decodeValue(12.5)).toThrow(/\{"@double": 12\.5\}/);
    expect(() => decodeValue(12)).toThrow(/\{"@int": "12"\}/);
    expect(() => decodeDocument({ weightKg: 12.5 })).toThrow(/bare JSON number/);
    // Nested, too: inside an array and inside a map.
    expect(() => decodeDocument({ xs: [1] })).toThrow(/bare JSON number/);
    expect(() => decodeDocument({ m: { n: 1 } })).toThrow(/bare JSON number/);
  });

  test('a numeric @int payload is refused', () => {
    expect(() => decodeValue({ '@int': 12 })).toThrow(/must be a decimal string/);
    expect(decodeValue({ '@int': '12' })).toBe(12n);
    // The precision argument the refusal rests on: a JSON number past 2^53 has already lost the
    // value by the time it is parsed, so accepting one would be accepting a wrong integer.
    expect(() => decodeValue({ '@int': 9007199254740993 })).toThrow(/must be a decimal string/);
    expect(decodeValue({ '@int': '9007199254740993' })).toBe(9007199254740993n);
  });

  test('every shape encodeValue emits still decodes', () => {
    // The refusals must only close shapes the encoder cannot produce.
    for (const v of [12, 12.5, -0, 0, NaN, Infinity, -Infinity, 2n ** 60n, 'x', true, null]) {
      const round = decodeValue(encodeValue(v));
      if (typeof v === 'number' && Number.isNaN(v)) expect(Number.isNaN(round)).toBe(true);
      else expect(round).toEqual(typeof v === 'bigint' ? v : v);
    }
  });
});

describe('isIntegralDouble — the one tolerated retype', () => {
  test('is exactly the predicate the dump-time reporter uses', () => {
    expect(isIntegralDouble(12)).toBe(true);
    expect(isIntegralDouble(0)).toBe(true);
    expect(isIntegralDouble(-5)).toBe(true);
    expect(isIntegralDouble(12.5)).toBe(false);
    expect(isIntegralDouble(-0)).toBe(false); // travels as a string, round-trips exactly
    expect(isIntegralDouble('NaN')).toBe(false);
    expect(isIntegralDouble(Number.MAX_SAFE_INTEGER + 2)).toBe(false);
  });

  test('collectIntegralDoubles and compareEncoded build the same field paths', () => {
    const encoded = encodeValue({
      weightKg: 12.5,
      meds: [{ dose: 1.5 }],
      wrapped: { '@int': 'literally a user key' },
    });
    // Force the integral-double shape the Node SDK cannot write but the Android SDK does.
    encoded.weightKg = { '@double': 12 };
    encoded.meds[0].dose = { '@double': 3 };

    const reported = collectIntegralDoubles(encoded, 'households/h1/pets/p1', []);
    expect(reported).toEqual([
      'households/h1/pets/p1.weightKg',
      'households/h1/pets/p1.meds[0].dose',
    ]);

    const retyped = { ...encoded, weightKg: { '@int': '12' }, meds: [{ dose: { '@int': '3' } }] };
    const out = newComparison();
    compareEncodedDocument(encoded, retyped, 'households/h1/pets/p1', out);
    expect(out.diffs).toEqual([]);
    // Same strings, so a tolerated path is directly comparable to a manifest entry.
    expect(out.retyped.sort()).toEqual(reported.sort());
  });
});

describe('compareEncoded', () => {
  const diff = (want, got) => {
    const out = newComparison();
    compareEncoded(want, got, 'f', out);
    return out;
  };

  test('an identical encoded tree compares equal', () => {
    const v = encodeValue({
      s: 'x', b: true, n: null, d: 12.5, big: 9007199254740993n,
      arr: [1, { k: 'v' }], map: {}, empty: [],
      tagLike: { '@map': 'a user map whose only key looks like a tag' },
    });
    expect(diff(v, JSON.parse(JSON.stringify(v))).diffs).toEqual([]);
  });

  test('tolerates ONLY the documented integral double -> integer retype', () => {
    expect(diff({ '@double': 12 }, { '@int': '12' })).toEqual({ diffs: [], retyped: ['f'] });
    // Same shape, different value: not the documented retype.
    expect(diff({ '@double': 12 }, { '@int': '13' }).diffs).toHaveLength(1);
    // A non-integral double coming back as an integer IS corruption.
    expect(diff({ '@double': 12.5 }, { '@int': '12' }).diffs).toHaveLength(1);
    // The reverse direction is not a thing the SDK does, so it is not tolerated either.
    expect(diff({ '@int': '12' }, { '@double': 12 }).diffs).toHaveLength(1);
  });

  test('catches the corruption classes a count + id-set check cannot see', () => {
    // A document written empty — matching id, matching count, no content.
    const emptied = newComparison();
    compareEncodedDocument({ a: 'x', b: { '@double': 1.5 } }, {}, 'households/h1/seizures/s1', emptied);
    expect(emptied.diffs).toEqual([
      'households/h1/seizures/s1.a: in the dump ("x"), absent from the target',
      'households/h1/seizures/s1.b: in the dump (@double 1.5), absent from the target',
    ]);

    // A dropped nested map key, a changed scalar, an extra field, a shortened array.
    expect(diff({ m: { a: 1, b: 2 } }, { m: { a: 1 } }).diffs)
      .toEqual(['f.m.b: in the dump (2), absent from the target']);
    expect(diff({ a: 'yes' }, { a: 'no' }).diffs).toEqual(['f.a: dump has "yes", target has "no"']);
    expect(diff({ a: 1 }, { a: 1, extra: 2 }).diffs)
      .toEqual(['f.extra: in the target (2), absent from the dump']);
    expect(diff([1, 2, 3], [1, 2]).diffs)
      .toEqual(['f: dump has an array of 3, target has an array of 2']);

    // A systematic type change.
    expect(diff({ '@time': { s: 1, n: 0 } }, { '@int': '1' }).diffs).toHaveLength(1);
    expect(diff('12', { '@int': '12' }).diffs).toHaveLength(1);
  });

  test('catches a divergence beyond the first array element, at the right index', () => {
    // A comparator that only walked index 0 (or only compared lengths) would pass both of these:
    // matching length, matching first element, and — for the array of maps — a matching key at
    // the diverging index too. The real clinical data this is standing in for is exactly this
    // shape: `medications`, a seizure's symptom list — repeated elements past the first.
    expect(diff([1, 2, 3], [1, 999, 3]).diffs).toEqual(['f[1]: dump has 2, target has 999']);

    // Array of maps: the second element's field diverges, first element identical.
    expect(diff(
      [{ name: 'a', doseMg: 5 }, { name: 'b', doseMg: 10 }],
      [{ name: 'a', doseMg: 5 }, { name: 'b', doseMg: 999 }]
    ).diffs).toEqual(['f[1].doseMg: dump has 10, target has 999']);
  });

  test('catches a divergence nested two map levels deep, at the full path', () => {
    // The "dropped nested map key" case above only reaches one level of nesting (f.m.b). A
    // comparator whose recursion silently stopped comparing past the first nested map — plausible
    // shape for a regression, since `compareEncoded` recurses into `compareEncodedMap` on every
    // plain-object value — would report this pair as equal. This is also the real shape a
    // medication's structure takes if it ever grows a nested field (e.g. a `schedule` map).
    expect(diff({ m: { n: { a: 1, b: 2 } } }, { m: { n: { a: 1, b: 999 } } }).diffs)
      .toEqual(['f.m.n.b: dump has 2, target has 999']);
    expect(diff({ m: { n: { a: 1, b: 2 } } }, { m: { n: { a: 1 } } }).diffs)
      .toEqual(['f.m.n.b: in the dump (2), absent from the target']);
  });

  test('compares inside tag payloads, and through the @map wrapper', () => {
    expect(diff({ '@time': { s: 1, n: 5 } }, { '@time': { s: 1, n: 6 } }).diffs).toHaveLength(1);
    expect(diff({ '@geo': { lat: 1, lng: 2 } }, { '@geo': { lat: 1, lng: 2 } }).diffs).toEqual([]);
    expect(diff({ '@ref': 'households/h1' }, { '@ref': 'households/h2' }).diffs).toHaveLength(1);
    expect(diff({ '@bytes': 'AAE=' }, { '@bytes': 'AAI=' }).diffs).toHaveLength(1);
    // The @map wrapper adds no path segment, matching collectIntegralDoubles.
    expect(diff({ '@map': { '@int': 'a' } }, { '@map': { '@int': 'b' } }).diffs)
      .toEqual(['f.@int: dump has "a", target has "b"']);
  });

  test('distinguishes NaN, the infinities and -0 from each other', () => {
    expect(diff({ '@double': 'NaN' }, { '@double': 'NaN' }).diffs).toEqual([]);
    expect(diff({ '@double': 'NaN' }, { '@double': 'Infinity' }).diffs).toHaveLength(1);
    expect(diff({ '@double': '-0' }, { '@double': 0 }).diffs).toHaveLength(1);
    expect(diff({ '@double': '-0' }, { '@int': '0' }).diffs).toHaveLength(1);
  });
});

describe('assertUseBigInt', () => {
  // A throw from db.settings() means "settings were already applied", NOT "already applied with
  // useBigInt". Without this check the codec's whole premise (integers arrive as BigInt) could be
  // false and nothing would say so — and the suite itself applies settings first, so the scripts'
  // own settings() call throws on every test run and used to be swallowed unchecked.
  const already = new Error('Firestore has already been initialized');

  test('passes when the applied settings did set useBigInt', () => {
    expect(() => assertUseBigInt({ _settings: { useBigInt: true } }, already)).not.toThrow();
  });

  test('throws when the applied settings did not set useBigInt', () => {
    expect(() => assertUseBigInt({ _settings: { projectId: 'p' } }, already))
      .toThrow(/WITHOUT useBigInt/);
    expect(() => assertUseBigInt({ _settings: { useBigInt: false } }, already))
      .toThrow(/silently lossy/);
  });

  test('reports, rather than fails, when the SDK does not expose its settings', () => {
    const spy = jest.spyOn(console, 'log').mockImplementation(() => {});
    try {
      expect(() => assertUseBigInt({}, already)).not.toThrow();
      expect(spy.mock.calls.flat().join(' ')).toMatch(/could not be confirmed/);
    } finally {
      spy.mockRestore();
    }
  });
});

// --- round-3 nits (review) ----------------------------------------------------------------------
describe('collectIntegralDoubles under an @map wrapper', () => {
  test('discloses an integral double inside a tag-lookalike map', () => {
    // The @map branch recursed on the inner map, so taggedKey() ran again on the very map that was
    // wrapped *because* its single key looks like a tag, took the tag branch and returned []. The
    // retype was then tolerated and recorded post-restore, but never disclosed in the dry run.
    const encoded = encodeValue({ '@int': 12 });
    expect(encoded).toEqual({ '@map': { '@int': { '@double': 12 } } });
    expect(collectIntegralDoubles(encoded, 'households/h1/pets/p1.m', []))
      .toEqual(['households/h1/pets/p1.m.@int']);
  });

  test('still reports nothing for a genuine tag, and the plain-map case is unchanged', () => {
    expect(collectIntegralDoubles(encodeValue(12n), 'doc.n', [])).toEqual([]);
    expect(collectIntegralDoubles(encodeValue({ a: { b: 12 } }), 'doc.m', []))
      .toEqual(['doc.m.a.b']);
  });
});

describe('the bare-number refusal', () => {
  test('suggests an @int spelling that is itself decodable, for any magnitude', () => {
    // The message offered {"@int": "1e+21"} for 1e21, and BigInt("1e+21") throws — a named fix
    // that does not work, on a hand-edit path where the message is all the operator has.
    const message = (v) => { try { decodeValue(v); return null; } catch (err) { return err.message; } };
    const suggested = (v) => message(v).match(/\{"@int": "([^"]+)"\}/)[1];
    expect(suggested(1e21)).toBe('1000000000000000000000');
    expect(() => BigInt(suggested(1e21))).not.toThrow();
    expect(suggested(12.7)).toBe('12');
    expect(suggested(-3.2)).toBe('-3');
  });
});
