import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { renderToStaticMarkup } from 'react-dom/server';
import { MemoryRouter } from 'react-router-dom';
import { LocalizationProvider, ReactLocalization } from '@fluent/react';
import { FluentBundle, FluentResource } from '@fluent/bundle';
import {
  BodyPart,
  TrackerDataT,
  TrackerInfoT,
  TrackerStatus as TrackerStatusEnum,
} from 'solarxr-protocol';
import type { ReactNode } from 'react';
import { HomeInputCatalogReader } from '../../src/monaka-input/home-catalog';
import { snapshot, source } from './fixtures';

// Browser modules read this at import time. No storage, network or app startup.
globalThis.window = { AudioContext: class {} } as unknown as Window &
  typeof globalThis;
// Legacy FirmwareIcon imports sound singletons. Keep browser constructors inert;
// no sound behavior or tracker hooks are replaced in this presentation test.
globalThis.Audio = class {
  load() {}
} as unknown as typeof Audio;
const { HomeInputCatalogPanel } =
  await import('../../src/components/home/HomeInputCatalog');
const { TrackerCard } =
  await import('../../src/components/tracker/TrackerCard');
const { ConfigContextC } = await import('../../src/hooks/config');
const { LangContext } = await import('../../src/i18n/config');

test('Japanese cards show logical IDs and optional compact IDs; technical information stays in collapsed details', async () => {
  const bundle = new FluentBundle('ja', { useIsolating: false });
  bundle.addResource(
    new FluentResource(readFileSync('public/i18n/ja/translation.ftl', 'utf8'))
  );
  const reader = new HomeInputCatalogReader({
    request: async () =>
      snapshot([
        source('opaque-one', { tracker: 'logical-hips', displayName: null }),
        source('opaque-two', {
          tracker: 'logical-left',
          displayName: '入力実機',
        }),
        source('opaque-three', {
          tracker: 'logical-right',
          displayName: '入力実機',
        }),
      ]),
  });
  await reader.load();
  try {
    const render = (children: ReactNode) =>
      renderToStaticMarkup(
        <LocalizationProvider l10n={new ReactLocalization([bundle], null)}>
          <ConfigContextC.Provider
            value={{
              config: null,
              setConfig: async () => {},
              saveConfig: async () => {},
            }}
          >
            <LangContext.Provider
              value={{ currentLocales: ['ja'], changeLocales: async () => {} }}
            >
              <MemoryRouter>{children}</MemoryRouter>
            </LangContext.Provider>
          </ConfigContextC.Provider>
        </LocalizationProvider>
      );
    const html = render(
      <HomeInputCatalogPanel
        reader={reader}
        search="?ip=test.invalid&amp;port=2222"
      />
    );
    assert.equal([...html.matchAll(/data-input-id=/g)].length, 3);
    const visible = html
      .replace(/(<\/summary>)[\s\S]*?<\/details>/g, '$1</details>')
      .replace(/<[^>]+>/g, '');
    assert.match(visible, /logical-hips/);
    assert.match(visible, /logical-left.*▶ID : 入力実機/);
    assert.match(visible, /logical-right.*▶ID : 入力実機/);
    assert.doesNotMatch(visible, /物理ID/);
    assert.match(visible, /入力有効/);
    assert.doesNotMatch(visible, /左膝/);
    assert.match(html, /title="保存済みの身体部位: 左膝"/);
    assert.doesNotMatch(
      visible,
      /INPUT:|Q|6DoF|MVIP|静的対応能力|Identity:|opaque-one|opaque-two|opaque-three|入力経路|割当済みでも|保存済みの身体部位/
    );
    assert.equal([...html.matchAll(/<details\b/g)].length, 3);
    assert.doesNotMatch(html, /<details[^>]*\bopen\b/);
    assert.match(html, /<summary[^>]*aria-label="[^"]*詳細"/);
    assert.equal([...html.matchAll(/h-\[70px\]/g)].length, 3);
    assert.match(html, /grid sm:grid-cols-1 md:grid-cols-2 gap-4 px-5 my-5/);
    assert.match(html, /\[&amp;::-webkit-details-marker\]:hidden/);
    assert.match(html, /focus-visible:outline/);
    assert.doesNotMatch(html, /LEFT_UPPER_LEG/);
    assert.match(html, /位置 \+ 回転/);
    assert.match(html, /opaque-one/);
    assert.match(html, /opaque-two/);
    assert.match(html, /opaque-three/);
    assert.match(html, /割当済みでも/);
    assert.match(html, /左膝 \(Q\), 左膝 \(6DoF\)/);
    assert.doesNotMatch(html, /<(button|input|select|form)\b/);
    assert.match(html, /\/onboarding\/trackers-assign\?ip=test.invalid/);
    const tracker = new TrackerDataT();
    tracker.info = new TrackerInfoT();
    tracker.info.customName = 'logical-hips';
    tracker.info.bodyPart = BodyPart.LEFT_UPPER_LEG;
    tracker.status = TrackerStatusEnum.OK;
    const legacy = render(<TrackerCard tracker={tracker} smol interactable />);
    const compact = html.match(/<summary[^>]*>([\s\S]*?)<\/summary>/)![1];
    assert.doesNotMatch(compact, /▶ID :/);
    const logicalIdWithTitle = '<span title="logical-hips">logical-hips</span>';
    assert.ok(compact.includes(logicalIdWithTitle));
    // The Home row without a physical ID uses the same compact layout as SolarXR.
    assert.ok(
      legacy
        .replace(
          bundle.getMessage('tracker-status-ok')!.value as string,
          '入力有効'
        )
        .includes(compact.replace(logicalIdWithTitle, 'logical-hips'))
    );
    assert.match(
      legacy,
      /hover:bg-background-50 cursor-pointer bg-background-60/
    );
    assert.match(
      html,
      /hover:bg-background-50 cursor-pointer bg-background-60/
    );
  } finally {
    reader.stop();
  }
});
