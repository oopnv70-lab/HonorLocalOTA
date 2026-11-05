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


def cstr(a, mx=140):
    o = v2o(a)
    if o is None:
        return None
    b = d[o:o + mx].split(b"\x00")[0]
    if not b:
        return None
    try:
        s = b.decode("utf-8")
    except UnicodeDecodeError:
        return None
    if not all(32 <= ord(c) < 127 or c in "\t\n" for c in s):
        return None
    return s


md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)

addr = int(sys.argv[1], 0)
ln = int(sys.argv[2], 0) if len(sys.argv) > 2 else 0x200

o = v2o(addr)
if o is None:
    print("unmapped")
    sys.exit(1)

reg = {}
print("===== 0x%08x, %d bytes =====" % (addr, ln))
for ins in md.disasm(d[o:o + ln], addr):
    a = ins.address
    m = ins.mnemonic
    ops = ins.op_str
    note = ""
    if m == "adrp":
        pr = [x.strip() for x in ops.split(",")]
        if len(pr) == 2:
            try:
                reg[pr[0]] = int(pr[1].lstrip("#"), 0)
            except ValueError:
                pass
    elif m == "add":
        pr = [x.strip() for x in ops.split(",")]
        if len(pr) == 3 and pr[0] == pr[1] and pr[2].startswith("#"):
            b = reg.get(pr[0])
            if b is not None:
                s = cstr(b + int(pr[2][1:], 0))
                if s:
                    note = '   ; "%s"' % s
    print("  %08x  %-8s %-40s%s" % (a, m, ops, note))