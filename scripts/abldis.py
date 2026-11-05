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


md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

targets = []
for a in sys.argv[1:]:
    targets.append(int(a, 0))
if not targets:
    targets = [0x7A690, 0x7BBF4, 0x7BB84, 0x7BC68]

for tgt in targets:
    o = v2o(tgt)
    print("\n===== VA 0x%08x =====" % tgt)
    if o is None:
        print("  未映射")
        continue
    n = 0
    for ins in md.disasm(d[o:o + 160], tgt):
        print("  %08x  %-8s %s" % (ins.address, ins.mnemonic, ins.op_str))
        n += 1
        if ins.mnemonic in ("ret", "b") and n > 2:
            break