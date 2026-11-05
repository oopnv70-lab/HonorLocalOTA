import struct
import sys
from capstone import *

PATH = "/storage/emulated/0/荣耀WIN_RT_引导链分析/07_ABL反编译/abl_a_loader.efi"
d = open(PATH, "rb").read()

pe = struct.unpack_from("<I", d, 0x3C)[0]
nsec = struct.unpack_from("<H", d, pe + 6)[0]
opt = struct.unpack_from("<H", d, pe + 20)[0]
secs = []
for i in range(nsec):
    o = pe + 24 + opt + i * 40
    vs = struct.unpack_from("<I", d, o + 8)[0]
    va = struct.unpack_from("<I", d, o + 12)[0]
    raw = struct.unpack_from("<I", d, o + 20)[0]
    secs.append((va, vs, raw))


def v2o(a):
    for va, vs, raw in secs:
        if va <= a < va + max(vs, 1):
            return raw + (a - va)
    return None


TARGET = sys.argv[1] if len(sys.argv) > 1 else "flashing unlock"
tb = TARGET.encode() + b"\x00"
off = d.find(tb)
while off >= 0:
    print("---- string %r at file 0x%x (VA 0x%x)" % (TARGET, off, off))
    print("   raw:", repr(d[off:off + len(tb) + 8]))
    off = d.find(tb, off + 1)