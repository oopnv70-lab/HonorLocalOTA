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

print("=== A) 文件里是否存有 0x1d54d8 这个指针值（8字节 LE）===")
needle = struct.pack("<Q", 0x1D54D8)
off = d.find(needle)
cnt = 0
while off >= 0 and cnt < 10:
    # 判断落在哪个节
    for name, va, vs, raw in secs:
        if raw <= off < raw + vs:
            print("  file 0x%06x -> %s VA=0x%x" % (off, name, va + off - raw))
            break
    cnt += 1
    off = d.find(needle, off + 1)
if cnt == 0:
    print("  未找到")

print("")
print("=== B) 指向 0x1d4000 页的 adrp 指令（找 .data 访问入口）===")
text_off = v2o(0x1000)
text = d[text_off:text_off + 0x1D3000]
hits = []
for i in range(0, len(text) - 4, 4):
    w = struct.unpack_from("<I", text, i)[0]
    if (w & 0x9F000000) != 0x90000000:
        continue
    rd = w & 0x1F
    immlo = (w >> 29) & 3
    immhi = (w >> 5) & 0x7FFFF
    imm = (immhi << 2) | immlo
    if imm & (1 << 20):
        imm -= 1 << 21
    addr = (i + 0x1000 & ~0xFFF) + imm * 0x1000
    if 0x1D4000 <= addr < 0x1D4800:
        hits.append((0x1000 + i, addr, rd))
print("  命中 %d 处（仅列前 25）" % len(hits))
for a, t, rd in hits[:25]:
    print("   0x%08x  adrp x%d, #0x%x" % (a, rd, t))