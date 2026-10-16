"""Bounded loopback capture into pinned production ingress; no pose/time rewrite.

Root/fallback are explicitly synthetic. UDP receipt is stamped by an independent
reader before Core/file-IPC backpressure. Overflow or >500ms forwarding backlog
stops the run; neither silently discards nor re-times tracking samples.
"""
from pathlib import Path
import argparse,json,socket,subprocess,time,os,sys,queue,threading

def receive_loop(sock,raw,pending,stop,deadline,statistics):
 try:
  while not stop.is_set() and time.monotonic()<deadline:
   try:data,peer=sock.recvfrom(65536)
   except socket.timeout:continue
   receipt=time.perf_counter_ns()
   # Save exact wire before queueing; canonical parsing/admission stays in Java.
   record=dict(received_at_ns=receipt,peer=list(peer),wire_utf8=data.decode('utf-8'))
   raw.write(json.dumps(record)+'\n');raw.flush();statistics['received']+=1
   try:pending.put_nowait((data,peer,receipt))
   except queue.Full:raise RuntimeError('Capture queue overflow; run stopped without timestamp/validity substitution')
   statistics['max_queue_depth']=max(statistics['max_queue_depth'],pending.qsize())
 except Exception as error:statistics['reader_error']=str(error);stop.set()

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 for name in ['package','config','experiment','output']:parser.add_argument('--'+name,type=Path,required=True)
 parser.add_argument('--java',required=True);parser.add_argument('--port',type=int,default=29811);parser.add_argument('--seconds',type=float,default=300)
 args=parser.parse_args();pkg=args.package.absolute();cfg=json.loads(args.config.read_text(encoding='utf-8'));experiment=json.loads(args.experiment.read_text(encoding='utf-8'))
 if experiment.get('syntheticRootAndFallback') is not True:raise RuntimeError('Explicit synthetic root/fallback acknowledgement required')
 if args.seconds<=0 or args.seconds>3600:raise ValueError('Bounded duration required')
 args.output.mkdir(parents=True,exist_ok=False)
 command=[args.java,'-Dfile.encoding=UTF-8','-cp',str(pkg/'runtime-ingress/monaka-runtime-ingress.jar')+os.pathsep+str(pkg/'core/lib/*'),'dev.monaka.runtime.ingress.RuntimeIngressMain','--package',str(pkg/'core'),'--config',str(args.config.absolute()),'--session',str(args.output/'core-session'),'--input','-','--output',str(args.output/'core-ingress.jsonl')]
 count=0;failure=None;close_error=None;statistics=dict(received=0,max_queue_depth=0,max_forward_delay_ns=0,reader_error=None);pending=queue.Queue(maxsize=256);stop=threading.Event()
 with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock,(args.output/'native-mtp.jsonl').open('x',encoding='utf-8') as raw,(args.output/'carriers.jsonl').open('x',encoding='utf-8') as carriers,(args.output/'ingress-process.log').open('x',encoding='utf-8') as log:
  if os.name=='nt':sock.setsockopt(socket.SOL_SOCKET,socket.SO_EXCLUSIVEADDRUSE,1)
  sock.bind(('127.0.0.1',args.port));sock.settimeout(.1)
  child=subprocess.Popen(command,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True,encoding='utf-8')
  reader=threading.Thread(target=receive_loop,args=(sock,raw,pending,stop,time.monotonic()+args.seconds,statistics),daemon=True);reader.start()
  (args.output/'ready.json').write_text(json.dumps(dict(port=sock.getsockname()[1],command=command,syntheticRootAndFallback=True,queue_capacity=256,max_forward_age_ns=500000000)),encoding='utf-8')
  try:
   while reader.is_alive() or not pending.empty():
    if child.poll() is not None:raise RuntimeError('Ingress terminated; inspect process log')
    if statistics['reader_error']:raise RuntimeError(statistics['reader_error'])
    try:data,peer,receipt=pending.get(timeout=.1)
    except queue.Empty:continue
    delay=time.perf_counter_ns()-receipt;statistics['max_forward_delay_ns']=max(statistics['max_forward_delay_ns'],delay)
    if delay>500000000:raise RuntimeError('Capture forwarding backlog exceeds existing 500ms freshness bound')
    sample=dict(sequence=count,sampleAtNanos=receipt,sourceEpoch='explicit-synthetic-root-fallback',calibrationEpoch='synthetic-body',commonWorldEpoch=None,valid=True,quaternionXyzw=[0,0,0,1])
    frame=dict(timestampNanos=receipt,sequence=count,imu=dict(sample,sourceId=cfg['imuSourceId']),hmd=dict(sample,sourceId=cfg['hmdSourceId'],positionMeters=experiment['rootPositionMeters']))
    row=dict(envelope=json.loads(data),received_at_ns=receipt,peer=str(peer),frame=frame)
    line=json.dumps(row,allow_nan=False)+'\n';carriers.write(line);carriers.flush();child.stdin.write(line);child.stdin.flush();count+=1
   if statistics['reader_error']:raise RuntimeError(statistics['reader_error'])
  except KeyboardInterrupt:pass
  except BaseException as error:failure=str(error)
  finally:
   stop.set();reader.join(timeout=2)
   if reader.is_alive():failure=failure or 'Capture reader did not stop'
   try:child.stdin.close()
   except OSError as error:close_error=str(error)
   try:code=child.wait(timeout=30)
   except subprocess.TimeoutExpired:child.terminate();child.wait(timeout=10);code=124
   result=dict(datagrams=count,exit_code=code,error=failure,stdin_close_error=close_error,all_datagrams_forwarded=count==statistics['received'] and not failure,syntheticRootAndFallback=True,hardware_validation='NOT RUN by software preparation',statistics=statistics)
   (args.output/'transport-result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
  if code or failure or close_error:raise RuntimeError(f'Ingress failure: {failure}, close={close_error}, exit={code}')
 print(f'Forwarded {count} datagrams; bounded receipt queue and clean shutdown')

if __name__=='__main__':main()
