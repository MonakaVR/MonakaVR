import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { LocalizationProvider, ReactLocalization } from '@fluent/react';
import { FluentBundle, FluentResource } from '@fluent/bundle';
import { HomeInputCatalogPanel } from '../../src/components/home/HomeInputCatalog';
import { HomeInputCatalogReader } from '../../src/monaka-input/home-catalog';
import { snapshot, source } from './fixtures';

test('Japanese cards show name, unique saved body and input status; technical information stays in collapsed details', async () => {
  const bundle = new FluentBundle('ja', { useIsolating: false });
  bundle.addResource(
    new FluentResource(readFileSync('public/i18n/ja/translation.ftl', 'utf8'))
  );
  const reader = new HomeInputCatalogReader({
    request: async () =>
      snapshot([
        source('opaque-one', { displayName: '入力実機' }),
        source('opaque-two', { displayName: '入力実機' }),
      ]),
  });
  await reader.load();
  try {
    const html = renderToStaticMarkup(
      <LocalizationProvider l10n={new ReactLocalization([bundle], null)}>
        <MemoryRouter>
          <HomeInputCatalogPanel
            reader={reader}
            search="?ip=test.invalid&amp;port=2222"
          />
        </MemoryRouter>
      </LocalizationProvider>
    );
    assert.equal([...html.matchAll(/data-input-id=/g)].length, 2);
    const visible = html
      .replace(/<details\b[\s\S]*?<\/details>/g, '')
      .replace(/<[^>]+>/g, '');
    assert.match(visible, /入力実機/);
    assert.match(visible, /入力有効/);
    assert.match(visible, /左膝/);
    assert.equal([...visible.matchAll(/左膝/g)].length, 1);
    assert.doesNotMatch(
      visible,
      /INPUT:|Q|6DoF|MVIP|静的対応能力|Identity:|opaque-one|opaque-two|入力経路|割当済みでも|保存済みの身体部位/
    );
    assert.equal([...html.matchAll(/<details\b/g)].length, 2);
    assert.doesNotMatch(html, /<details[^>]*\bopen\b/);
    assert.match(html, /<summary[^>]*>詳細<\/summary>/);
    assert.doesNotMatch(html, /LEFT_UPPER_LEG/);
    assert.match(html, /位置 \+ 回転/);
    assert.match(html, /opaque-one/);
    assert.match(html, /opaque-two/);
    assert.match(html, /割当済みでも/);
    assert.match(html, /左膝 \(Q\), 左膝 \(6DoF\)/);
    assert.doesNotMatch(html, /<(button|input|select|form)\b/);
    assert.match(html, /\/settings\/monaka-input\?ip=test.invalid/);
  } finally {
    reader.stop();
  }
});
