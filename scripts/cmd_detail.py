import struct
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


def cstr(a, mx=120):
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
md.detail = True

BASE = 0x1D54D8
cmds = []
for i in range(73):
    p, f = struct.unpack_from("<QQ", d, BASE + i * 16)
    cmds.append((cstr(p), f))

ranges = []
try:
    for line in open("/storage/emulated/0/荣耀WIN_RT_引导链分析/07_ABL反编译/abl_a_index.txt"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 4:
            ranges.append((int(parts[0], 16), int(parts[3])))
    ranges.sort()
except FileNotFoundError:
    pass

idxmap = {}
for a, s in ranges:
    idxmap[a] = s
addrs = sorted(idxmap)


def limit_of(a):
    lo, hi = 0, len(addrs)
    while lo < hi:
        mid = (lo + hi) // 2
        if addrs[mid] <= a:
            lo = mid + 1
        else:
            hi = mid
    if lo < len(addrs):
        return min(addrs[lo], a + 0x2000)
    return a + 0x2000


for name, fn in cmds:
    o = v2o(fn)
    end = min(limit_of(fn), fn + 0x800)
    print("")
    print("=" * 76)
    print("CMD  %-34s  fn=0x%08x  span~%d" % (name, fn, end - fn))
    if o is None:
        print("   [unmapped]")
        continue
    reg = {}
    strs = []
    calls = []
    n = 0
    for ins in md.disasm(d[o:v2o(end)], fn):
        n += 1
        if n > 400:
            break
        m, ops = ins.mnemonic, ins.op_str
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
                    if s and s not in strs:
                        strs.append(s)
        elif m == "bl":
            t = ops.lstrip("#")
            try:
                tv = int(t, 0)
            except ValueError:
                continue
            if len(calls) < 40:
                calls.append(tv)
    print("   calls: " + ", ".join("0x%x" % c for c in calls[:24]))
    for s in strs[:12]:
        print("   str : " + repr(s))