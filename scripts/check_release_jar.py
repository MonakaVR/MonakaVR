"""Current v2 application artifact gate; historical Task5 checks remain separate."""
import argparse
from pathlib import Path
import verify_task5

p = argparse.ArgumentParser()
p.add_argument('--work', type=Path, required=True)
a = p.parse_args()
a.work.mkdir(parents=True, exist_ok=True)
verify_task5.EVIDENCE = a.work
verify_task5.inspect_active_jar(verify_task5.ROOT / 'server/desktop/build/libs/slimevr.jar', wire_major=2)
