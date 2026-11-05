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

BASE = 0x1D54D8
# 各命令函数真实边界（由表中相邻 func_ptr 决定，最后一辆用 0x400）
fns = []
names = []
for i in range(73):
    p, f = struct.unpack_from("<QQ", d, BASE + i * 16)
    names.append(cstr(p))
    fns.append(f)

bounds = []
for i in range(73):
    nxt = None
    for j in range(i + 1, 73):
        if fns[j] > fns[i]:
            nxt = fns[j]
            break
    if nxt is None or nxt - fns[i] > 0x400:
        nxt = fns[i] + 0x400
    bounds.append((fns[i], nxt))

# 固定窗口 0x100（跳板函数 60~260 字节）
WIN = 0x100

print("| # | 命令 | 函数地址 | 关键字符串 |")
print("|---|---|---|---|")
for i in range(73):
    fn = fns[i]
    o = v2o(fn)
    if o is None:
        print("| %d | `%s` | 0x%06x | [未映射] |" % (i, names[i], fn))
        continue
    reg = {}
    strs = []
    for ins in md.disasm(d[o:o + WIN], fn):
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
                    if s and s not in strs and len(s) > 2:
                        strs.append(s)
    cell = "; ".join(strs[:3]).replace("|", "/")
    if len(cell) > 110:
        cell = cell[:107] + "..."
    print("| %d | `%s` | 0x%06x | %s |" % (i, names[i], fn, cell or "—"))

print("")
print("=== 含锁状态检查的命令 ===")
for i in range(73):
    fn = fns[i]
    o = v2o(fn)
    if o is None:
        continue
    reg = {}
    strs = []
    for ins in md.disasm(d[o:o + WIN], fn):
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
    lock = [s for s in strs if "Lock" in s or "lock" in s or "state" in s.lower()]
    if lock:
        print("  %-32s 0x%06x  %s" % (names[i], fn, " / ".join(lock)))