import type { InputApi, InputSnapshot } from './controller';

/** Existing Core GUI WebSocket, separate text namespace; never starts a runtime. */
export class MonakaInputSocket implements InputApi {
  private socket: WebSocket | null = null;
  private opening: Promise<WebSocket> | null = null;
  private serial = 0;
  private pending = new Map<
    string,
    {
      resolve: (x: InputSnapshot) => void;
      reject: (x: Error) => void;
      timer: ReturnType<typeof setTimeout>;
    }
  >();
  constructor(private url: string) {}
  private connect(): Promise<WebSocket> {
    if (this.socket?.readyState === WebSocket.OPEN) return Promise.resolve(this.socket);
    if (this.opening) return this.opening;
    const socket = new WebSocket(this.url);
    this.socket = socket;
    this.opening = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        reject(new Error('Core connection timed out'));
        socket.close();
      }, 5000);
      socket.onopen = () => {
        clearTimeout(timer);
        this.opening = null;
        resolve(socket);
      };
      socket.onerror = () => {
        clearTimeout(timer);
        reject(new Error('Core connection failed'));
      };
      socket.onclose = () => {
        clearTimeout(timer);
        if (this.socket === socket) {
          this.socket = null;
          this.opening = null;
        }
        reject(new Error('Core disconnected'));
        for (const entry of this.pending.values()) {
          clearTimeout(entry.timer);
          entry.reject(new Error('Core disconnected; saved result may be unknown'));
        }
        this.pending.clear();
      };
      socket.onmessage = (event) => {
        if (typeof event.data !== 'string' || event.data.length > 2 * 1024 * 1024)
          return;
        let value;
        try {
          value = JSON.parse(event.data);
        } catch {
          return;
        }
        if (value.type !== 'monaka_inputs_v1' || typeof value.id !== 'string') return;
        const entry = this.pending.get(value.id);
        if (!entry) return;
        this.pending.delete(value.id);
        clearTimeout(entry.timer);
        if (value.ok !== true)
          entry.reject(
            new Error(
              typeof value.error === 'string' ? value.error : 'Input request failed'
            )
          );
        else if (
          typeof value.generation !== 'string' ||
          !Array.isArray(value.candidates) ||
          !Array.isArray(value.assignments) ||
          !Array.isArray(value.bodies)
        )
          entry.reject(new Error('Invalid Core input response'));
        else entry.resolve(value as InputSnapshot);
      };
    });
    return this.opening;
  }
  async request(fields: Record<string, unknown>): Promise<InputSnapshot> {
    const socket = await this.connect();
    if (this.pending.size >= 4) throw new Error('Input request is already pending');
    const id = `${Date.now()}-${++this.serial}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error('Core request timed out; saved result may be unknown'));
      }, 8000);
      this.pending.set(id, { resolve, reject, timer });
      try {
        socket.send(JSON.stringify({ ...fields, type: 'monaka_inputs_v1', id }));
      } catch (error) {
        clearTimeout(timer);
        this.pending.delete(id);
        reject(error);
      }
    });
  }
  close() {
    this.socket?.close();
  }
}
