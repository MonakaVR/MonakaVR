import { isBridgePolicy, type BridgeDesktopRequest } from '../bridge-types.js';
import type { MonakaBridgeService } from './monaka-bridge-service.js';

// Private Desktop IPC only. Native dialogs stay in the window owner.
export function bridgeControlHandler(
  ownerId: () => number | null,
  service: () => MonakaBridgeService | null,
  chooseFile: (kind: 'config' | 'validator') => Promise<string | null>
) {
  let dialogOpen = false;
  return async (senderId: number, request: BridgeDesktopRequest) => {
    const control = service();
    if (senderId !== ownerId() || !control)
      throw new Error('Bridge controls require the Desktop window.');
    if (!request || typeof request !== 'object')
      throw new Error('Invalid Desktop Bridge request.');
    if (dialogOpen && request.operation !== 'health' && request.operation !== 'cancel')
      throw new Error('Finish the Bridge file selection first.');
    const id = (value: unknown) => {
      if (typeof value !== 'string' || !value || value.length > 128)
        throw new Error('Invalid Desktop operation identifier.');
      return value;
    };
    switch (request.operation) {
      case 'load':
        return control.load(id(request.requestId));
      case 'health':
        return control.health();
      case 'cancel':
        control.cancel(id(request.requestId));
        return;
      case 'apply':
        if (!isBridgePolicy(request.policy))
          throw new Error('Select an existing Bridge output policy.');
        return control.apply(id(request.token), request.policy, id(request.requestId));
      case 'choose': {
        if (control.busy) throw new Error('Wait for the current Bridge operation.');
        if (request.kind !== 'config' && request.kind !== 'validator')
          throw new Error('Unknown Bridge file selection.');
        dialogOpen = true;
        try {
          const path = await chooseFile(request.kind);
          return path ? control.configure(request.kind, path) : null;
        } finally {
          dialogOpen = false;
        }
      }
      default:
        throw new Error('Unknown Desktop Bridge operation.');
    }
  };
}
