// Explicit fixture: port must identify the parent-owned test process.
import React from 'react';
import { createRoot } from 'react-dom/client';
import { LocalizationProvider, ReactLocalization } from '@fluent/react';
import { FluentBundle, FluentResource } from '@fluent/bundle';
import messages from '../../public/i18n/en/translation.ftl?raw';
import { MonakaInputSettings } from '../../src/components/settings/pages/MonakaInputSettings';
import '../../src/index.scss';
if (!new URLSearchParams(window.location.search).has('port'))
  throw new Error('Owned software probe port required');
const bundle = new FluentBundle('en');
bundle.addResource(new FluentResource(messages));
const l10n = new ReactLocalization([bundle]);
createRoot(document.getElementById('root')!).render(
  <LocalizationProvider l10n={l10n}>
    <p>SOFTWARE INPUT TEST MODE</p>
    <MonakaInputSettings />
  </LocalizationProvider>
);
