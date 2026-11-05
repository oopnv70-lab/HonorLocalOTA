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
    name = d[o:o+8].split(b"\x00")[0].decode()
    vs = struct.unpack_from("<I", d, o + 8)[0]
    va = struct.unpack_from("<I", d, o + 12)[0]
    raw = struct.unpack_from("<I", d, o + 20)[0]
    secs.append((name, va, vs, raw))


def v2o(a):
    for name, va, vs, raw in secs:
        if va <= a < va + max(vs, 1):
            return raw + (a - va)
    return None


def cstr(a, mx=96):
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
    if not all(32 <= ord(c) < 127 or c in "\t" for c in s):
        return None
    return s


BASE = 0x1D54D8
rows = []
i = 0
while True:
    off = BASE + i * 16
    p, f = struct.unpack_from("<QQ", d, off)
    if p == 0 and f == 0:
        if i > 80:
            break
        i += 1
        continue
    nm = cstr(p)
    rows.append((i, p, f, nm))
    i += 1
    if i > 200:
        break

print("表基址 0x%x，非空项 %d 个，扫到索引 %d" % (BASE, len(rows), i))
print("")
print("%-3s %-10s %-10s %s" % ("#", "name_ptr", "func_ptr", "name"))
for idx, p, f, nm in rows:
    print("%-3d 0x%08x 0x%08x %s" % (idx, p, f, nm))
