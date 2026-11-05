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


md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
md.detail = True

TARGET = 0x1D54D8  # 命令表基址
text_off = v2o(0x1000)
text_size = 0x1D3000

reg = {}
hits = []
for ins in md.disasm(d[text_off:text_off + text_size], 0x1000):
    m = ins.mnemonic
    ops = ins.op_str
    if m == "adrp":
        parts = ops.split(", #")
        if len(parts) == 2:
            try:
                reg[parts[0].strip()] = int(parts[1], 0)
            except ValueError:
                pass
    elif m == "add":
        parts = [x.strip() for x in ops.split(",")]
        if len(parts) == 3 and parts[0] == parts[1] and parts[2].startswith("#"):
            base = reg.get(parts[0])
            if base is not None:
                try:
                    val = base + int(parts[2][1:], 0)
                except ValueError:
                    continue
                if val == TARGET:
                    hits.append(ins.address)
    elif m in ("ldr", "str"):
        parts = [x.strip() for x in ops.split(",")]
        if len(parts) == 3 and parts[1] == "[" + parts[0] + "]":
            base = reg.get(parts[0])
            if base is not None and parts[2].startswith("#"):
                try:
                    val = base + int(parts[2][1:], 0)
                except ValueError:
                    continue
                if val == TARGET:
                    hits.append(ins.address)

print("命令表 0x%x 的引用点共 %d 处" % (TARGET, len(hits)))
for h in hits:
    print("  VA 0x%08x  (函数区间参照索引)" % h)
