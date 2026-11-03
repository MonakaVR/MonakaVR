const path = require('node:path');
const root = path.resolve(__dirname, '..');

module.exports = {
  extends: path.join(root, 'gui', 'electron-builder.yml'),
  directories: { output: path.join(root, 'Build', 'runtime', 'package-stage') },
  electronDist: path.join(root, 'gui', 'node_modules', 'electron', 'dist'),
  npmRebuild: false,
  win: {
    signAndEditExecutable: false,
  },
};
