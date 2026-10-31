const path = require('node:path');
const root = path.resolve(__dirname, '..');

module.exports = {
  extends: path.join(root, 'gui', 'electron-builder.yml'),
  directories: { output: path.join(root, 'Build', 'runtime', 'package-stage') },
  electronDist: path.join(root, 'gui', 'node_modules', 'electron', 'dist'),
  npmRebuild: false,
  win: {
    signAndEditExecutable: false,
    extraFiles: [
      { from: path.join(root, 'Build', 'gradle', 'server', 'desktop', 'libs', 'slimevr.jar'), to: '.' },
      { from: path.join(root, 'server', 'core', 'resources'), to: '.', filter: ['**/*'] },
      { from: path.join(root, 'Build', 'runtime', 'bindings-provider', 'Release', 'SlimeVR-Bindings-Provider.exe'), to: '.' },
      { from: path.join(root, 'Build', 'runtime', 'bindings-provider', 'openvr_api.dll'), to: '.' },
    ],
  },
};
