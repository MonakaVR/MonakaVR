import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renderToStaticMarkup } from 'react-dom/server';
import { LocalizationProvider, ReactLocalization } from '@fluent/react';
import { FluentBundle, FluentResource } from '@fluent/bundle';
import { MonakaTrackingStatusPanel } from '../../src/components/monaka-status/MonakaTrackingStatusPanel';
import { fixtures, snapshot, source } from './fixtures';

for (const fixture of fixtures) {
  test(`component DOM: ${fixture.name}`, () => {
    const html = renderToStaticMarkup(
      <LocalizationProvider l10n={new ReactLocalization([], null)}>
        <MonakaTrackingStatusPanel
          snapshot={fixture.snapshot}
          connection={fixture.connection}
        />
      </LocalizationProvider>
    );
    const text = html.replace(/<[^>]+>/g, '');
    for (const expected of fixture.expected)
      assert.ok(text.includes(expected), `${fixture.name}: ${expected}`);
    assert.doesNotMatch(html, /<(button|input|select|form|textarea)\b/);
    assert.match(text, /do not confirm Direct output/);
    assert.doesNotMatch(text, /Last received|Last updated|restart required/i);
  });
}
test('shared Fluent keys render the supplied translations and missing keys fall back', () => {
  const bundle = new FluentBundle('en', { useIsolating: false });
  bundle.addResource(
    new FluentResource(
      'monaka-tracking-capability = Translated capability\nmonaka-tracking-validity = Translated validity\nmonaka-tracking-unknown = Translated unknown'
    )
  );
  const html = renderToStaticMarkup(
    <LocalizationProvider l10n={new ReactLocalization([bundle], null)}>
      <MonakaTrackingStatusPanel
        snapshot={fixtures[5].snapshot}
        connection="connected"
      />
    </LocalizationProvider>
  );
  assert.match(html, /Translated capability/);
  assert.match(html, /Translated validity/);
  assert.match(html, /Translated unknown/);
  assert.match(html, /Saved \(<span>offline<\/span>\)/);
});
test('untrusted source text is escaped and two panels get separate accessible IDs', () => {
  const input = snapshot([
    source('publisher-a', { tracker: '<script>alert(1)</script>' }),
  ]);
  const html = renderToStaticMarkup(
    <LocalizationProvider l10n={new ReactLocalization([], null)}>
      <MonakaTrackingStatusPanel snapshot={input} connection="connected" />
      <MonakaTrackingStatusPanel snapshot={input} connection="connected" />
    </LocalizationProvider>
  );
  assert.doesNotMatch(html, /<script>/);
  assert.match(html, /&lt;script&gt;/);
  const ids = [...html.matchAll(/aria-labelledby="([^"]+)"/g)].map((x) => x[1]);
  assert.equal(new Set(ids).size, 2);
});
