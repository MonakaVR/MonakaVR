from pathlib import Path
from stable_launcher import main

if __name__ == "__main__":
    raise SystemExit(main('MonakaBridge', Path(__file__).resolve().parents[2]))
