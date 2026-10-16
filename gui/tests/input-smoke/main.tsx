// Explicit fixture: port must identify the parent-owned test process.
import React from 'react';
import { createRoot } from 'react-dom/client';
import { MonakaInputSettings } from '../../src/components/settings/pages/MonakaInputSettings';
import '../../src/index.scss';
if (!new URLSearchParams(window.location.search).has('port'))
  throw new Error('Owned software probe port required');
createRoot(document.getElementById('root')!).render(
  <>
    <p>SOFTWARE INPUT TEST MODE</p>
    <MonakaInputSettings />
  </>
);
