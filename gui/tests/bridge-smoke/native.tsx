// Test-only renderer. Uses the real preload IPC and temporary native config.
import React from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { MonakaBridgeSettings } from '../../src/components/settings/pages/MonakaBridgeSettings';
import '../../src/index.scss';
import '@fontsource/poppins/500.css';
createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <MemoryRouter>
      <p className="p-3 bg-red-800 text-white">
        SOFTWARE TEST MODE - real Desktop IPC, temporary config only; no Core,
        backend or tracking output
      </p>
      <MonakaBridgeSettings inputControls={null} />
    </MemoryRouter>
  </React.StrictMode>
);
