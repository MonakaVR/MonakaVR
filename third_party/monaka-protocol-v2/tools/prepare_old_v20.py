"""Build both decoders from the pinned canonical v2.0 source, read-only Git export."""
import argparse
import io
from pathlib import Path
import subprocess
import tarfile

R=Path(__file__).resolve().parents[1]
PIN='572e58cfa20b8b4335207ea5dbcb3f04c587ddff'

def main():
    p=argparse.ArgumentParser(); p.add_argument('--cmake',default='cmake'); p.add_argument('--gradle',default='gradle'); a=p.parse_args()
    old=R/'build/old-v20'; old.mkdir(parents=True,exist_ok=True)
    raw=subprocess.check_output(['git','archive',PIN],cwd=R)
    with tarfile.open(fileobj=io.BytesIO(raw)) as tar: tar.extractall(old,filter='data')
    subprocess.run([a.cmake,'-S',str(old/'cpp'),'-B',str(old/'build/cpp'),'-DMONAKA_BUILD_TESTS=ON'],check=True)
    subprocess.run([a.cmake,'--build',str(old/'build/cpp'),'--config','Release'],check=True)
    subprocess.run([a.gradle,'-p',str(old/'jvm'),'--no-daemon','build'],check=True)
    print('PASS old decoder source '+PIN)

if __name__=='__main__': main()
