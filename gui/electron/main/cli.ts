import { Option, program } from 'commander';

program
  .option('-p, --path <path>', 'set launch path')
  .option('--profile <directory>', 'use an explicit independent application profile')
  .option('--server-port <port>', 'use an explicit WebSocket listen port')
  .option(
    '--network-only',
    'disable native device discovery and native output integration'
  )
  .option('--monaka-mtp', 'enable public MVIP input')
  .option('-s, --steam', 'steam mode')
  .option('-i, --install', 'run the driver installer')
  .option(
    '--skip-server-if-running',
    'gui will not launch the server if it is already running'
  )
  .allowUnknownOption()
  // Allow passing arguments to Electron.
  .allowExcessArguments();

if (process.platform === 'linux') {
  const noUdevOption = new Option('--no-udev', 'disable udev warning');
  noUdevOption.negate = false;
  program.addOption(noUdevOption);
}

program.parse(process.argv, { from: 'electron' });
export const options = program.opts();
