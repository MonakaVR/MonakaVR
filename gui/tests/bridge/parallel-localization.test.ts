import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { FluentBundle, FluentResource } from '@fluent/bundle';
import { ReactLocalization } from '@fluent/react';

const expected = {
  'monaka-tracking-mode-ik': ['IK output', 'IK出力'],
  'monaka-tracking-mode-direct': ['Direct 6DoF output', '6DoF直接出力'],
  'monaka-tracking-mode-hybrid': [
    'Hybrid (Direct + IK fallback)',
    'ハイブリッド（Direct + IKフォールバック）',
  ],
  'monaka-tracking-restart-required': [
    'Restart required to apply saved mode',
    '保存済みモードの反映には再起動が必要',
  ],
  'monaka-tracking-capability': ['Declared capability', '静的対応能力'],
  'monaka-tracking-validity': ['Current input validity', '現在の入力有効性'],
  'monaka-tracking-offline': ['Offline / unavailable', 'オフライン／利用不可'],
  'monaka-tracking-assigned': ['Assigned source', '割当済みソース'],
  'monaka-tracking-active': ['Active mode', '稼働中のモード'],
  'monaka-tracking-pending': ['Saved, not yet applied', '保存済み・未反映'],
  'monaka-tracking-unknown': ['Unknown / unverified', '未確認／不明'],
};

// These messages are plain strings. DOM overlays are outside this Node test;
// getString still uses the production Fluent fallback and formatting code.
function localization(bundles: FluentBundle[]) {
  return new ReactLocalization(bundles, null, (error) => {
    throw error;
  });
}

function bundle(locale: string) {
  const source = readFileSync(`public/i18n/${locale}/translation.ftl`, 'utf8');
  const resource = new FluentResource(source);
  const result = new FluentBundle(locale);
  // Pinned dev a07d0c7b already has these three English collisions. Assert
  // the exact baseline so neither new errors nor a changed baseline go unnoticed.
  const baselineCollisions =
    locale === 'en'
      ? [
          'settings-general-interface-dev_mode',
          'settings-general-interface-dev_mode-description',
          'settings-general-interface-dev_mode-label',
        ].map((key) => `Attempt to override an existing message: "${key}"`)
      : [];
  assert.deepEqual(
    result.addResource(resource).map((error) => error.message),
    baselineCollisions,
    `${locale}: full-resource errors differ from pinned baseline`
  );
  const monaka = new FluentBundle(locale);
  assert.deepEqual(
    monaka.addResource(
      new FluentResource(
        source
          .split('\n')
          .filter((line) => line.startsWith('monaka-tracking-'))
          .join('\n')
      )
    ),
    [],
    `${locale}: new Monaka messages must have no errors`
  );
  return { source, result };
}

test('parallel localization: all eleven messages parse once with agreed en/ja meanings', () => {
  for (const [index, locale] of ['en', 'ja'].entries()) {
    const { source, result } = bundle(locale);
    const translated = localization([result]);
    for (const [key, translations] of Object.entries(expected)) {
      assert.equal(
        source.split('\n').filter((line) => line.startsWith(`${key} =`)).length,
        1,
        `${locale}: duplicate or missing ${key}`
      );
      assert.ok(result.getMessage(key)?.value, `${locale}: unparsed ${key}`);
      assert.equal(translated.getString(key), translations[index]);
    }
  }
});

test('parallel localization: existing locale then English preserves fallback and Japanese precedence', () => {
  const english = bundle('en').result;
  const japanese = bundle('ja').result;
  const french = bundle('fr').result;
  const fallback = localization([french, english]);
  const selected = localization([japanese, english]);
  for (const [key, translations] of Object.entries(expected)) {
    assert.equal(french.hasMessage(key), false);
    assert.equal(fallback.getString(key), translations[0]);
    assert.equal(selected.getString(key), translations[1]);
  }
  // An override with a missing Monaka key follows the same bundle order.
  const override = new FluentBundle('ja');
  override.addResource(new FluentResource('custom-label = カスタム'));
  const overridden = localization([override, english]);
  assert.equal(overridden.getString('custom-label'), 'カスタム');
  assert.equal(overridden.getString('monaka-tracking-mode-ik'), 'IK output');
});
