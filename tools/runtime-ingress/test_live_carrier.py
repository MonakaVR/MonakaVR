"""Bounded UDP-reader behavior without Core, hardware or real sockets."""
import importlib.util,io,json,queue,socket,threading,time,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('live_carrier',Path(__file__).with_name('live-mtp-carrier.py'));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class ReaderTests(unittest.TestCase):
 def run_reader(self,rows,capacity=256):
  stop=threading.Event();pending=queue.Queue(maxsize=capacity);raw=io.StringIO();statistics=dict(received=0,max_queue_depth=0,reader_error=None)
  class Socket:
   def __init__(self):self.rows=iter(rows)
   def recvfrom(self,_):
    try:return next(self.rows),('127.0.0.1',1234)
    except StopIteration:stop.set();raise socket.timeout()
  before=time.perf_counter_ns();module.receive_loop(Socket(),raw,pending,stop,time.monotonic()+1,statistics);after=time.perf_counter_ns()
  return pending,[json.loads(x) for x in raw.getvalue().splitlines()],statistics,before,after
 def test_exact_wire_and_receipt_survive_delayed_consumer(self):
  rows=[b'{"sequence":"0","valid":false}',b'{"sequence":"1","valid":true}']
  pending,raw,stats,before,after=self.run_reader(rows);self.assertIsNone(stats['reader_error']);self.assertEqual(stats['received'],2)
  for expected,record in zip(rows,raw):
   data,peer,receipt=pending.get_nowait();self.assertEqual(data,expected);self.assertEqual(record['wire_utf8'],expected.decode());self.assertEqual(record['received_at_ns'],receipt)
   self.assertLessEqual(before,receipt);self.assertLessEqual(receipt,after);self.assertEqual(peer,('127.0.0.1',1234))
 def test_overflow_stops_without_silent_drop_or_retime(self):
  pending,raw,stats,_,_=self.run_reader([b'one',b'two',b'three'],1)
  self.assertIn('overflow',stats['reader_error']);self.assertEqual(stats['received'],2);self.assertEqual(pending.qsize(),1);self.assertEqual([x['wire_utf8'] for x in raw],['one','two'])
 def test_invalid_utf8_stops_visibly(self):
  pending,raw,stats,_,_=self.run_reader([b'\xff'])
  self.assertIsNotNone(stats['reader_error']);self.assertEqual(pending.qsize(),0);self.assertEqual(raw,[])

if __name__=='__main__':unittest.main()
