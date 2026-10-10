"""Local file IPC client. Starts only the standalone Core harness; never a backend or endpoint."""
from pathlib import Path
import argparse, json, os, shutil, subprocess, time, uuid

PACKAGE = Path(__file__).resolve().parent

def send(session, op, **values):
    request_id = uuid.uuid4().hex
    request = dict(id=request_id, op=op, **values)
    with (session/'requests.jsonl').open('a', encoding='utf-8', newline='\n') as out:
        out.write(json.dumps(request, ensure_ascii=False, allow_nan=False) + '\n')
    response = session/f'response-{request_id}.json'
    deadline = time.monotonic() + 30
    while not response.exists():
        if time.monotonic() > deadline:
            raise TimeoutError(f'No response to {op}; inspect {session / "process.log"}')
        time.sleep(.01)
    return json.loads(response.read_text(encoding='utf-8'))

def start(session, config, hil):
    session.mkdir(parents=True, exist_ok=False)
    java = Path(os.environ['JAVA_HOME'])/'bin/java.exe' if os.environ.get('JAVA_HOME') else shutil.which('java')
    if not java: raise RuntimeError('Java 17+ required; set JAVA_HOME explicitly')
    command = [str(java), '-cp', str(PACKAGE/'lib/*'), 'dev.monaka.tracking.hil.CorePocHilMain',
               '--session', str(session), '--config', str(config.resolve())] + (['--hil'] if hil else [])
    with (session/'process.log').open('wb') as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    deadline = time.monotonic() + 30
    while not (session/'ready.json').exists():
        if process.poll() is not None: raise RuntimeError(f'Harness exited {process.returncode}; inspect {session / "process.log"}')
        if time.monotonic() > deadline: raise TimeoutError('Harness startup timeout')
        time.sleep(.02)
    result = json.loads((session/'ready.json').read_text(encoding='utf-8'))
    (session/'launch.json').write_text(json.dumps(dict(pid=process.pid, command=command, ready=result), indent=2)+'\n', encoding='utf-8')
    return result

def replay(session, path):
    frames = 0
    with path.open(encoding='utf-8') as source:
        for line in source:
            if not line.strip(): continue
            request = json.loads(line)
            op = request.pop('op')
            send(session, op, **request)
            frames += op == 'input'
    return dict(replayed_frames=frames, status=send(session, 'status'))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--session', type=Path, required=True)
    sub = parser.add_subparsers(dest='op', required=True)
    boot = sub.add_parser('start'); boot.add_argument('--config', type=Path, required=True); boot.add_argument('--hil', action='store_true')
    for op in ('status','capture-stop','shutdown'): sub.add_parser(op)
    capture = sub.add_parser('capture-start'); capture.add_argument('--name', default='capture.jsonl')
    valid = sub.add_parser('6dof-valid'); valid.add_argument('value', choices=('on','off'))
    mark = sub.add_parser('mark'); mark.add_argument('note')
    play = sub.add_parser('replay'); play.add_argument('path', type=Path)
    feed = sub.add_parser('input'); feed.add_argument('path', type=Path, help='One Common Pose diagnostic input frame')
    args = parser.parse_args(); session = args.session.resolve()
    if args.op == 'start': result = start(session, args.config, args.hil)
    elif args.op == 'replay': result = replay(session, args.path)
    elif args.op == 'input': result = send(session, 'input', frame=json.loads(args.path.read_text(encoding='utf-8')))
    else:
        values = {k:getattr(args,k) for k in ('name','value','note') if hasattr(args,k)}
        result = send(session, args.op, **values)
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))

if __name__ == '__main__': main()
