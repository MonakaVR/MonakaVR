"""Verify preserved historical v2.0 artifact without overwriting the current kit."""
from verify_protocol_v21 import verify
if __name__ == '__main__': verify('dependencies/monaka-protocol-v2.lock.json', check_tree=False)
