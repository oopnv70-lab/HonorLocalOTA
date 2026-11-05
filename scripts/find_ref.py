import struct

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

# 直接在原始字节里找 adrp+add 指向 0x209ee0 的指令对
# adrp xN, page ; add xN, xN, #imm
import re

TARGET = 0x209EE0
hits = []
for va, vs, raw in secs:
    if va != 0x1000:
        continue
    data = d[raw:raw + 0x1d3000]
    for off in range(0, len(data) - 8, 4):
        w0 = struct.unpack_from("<I", data, off)[0]
        w1 = struct.unpack_from("<I", data, off + 4)[0]
        # adrp: 1xx10000 with immlo/immhi
        if (w0 & 0x9F000000) != 0x90000000:
            continue
        # add (immediate): sf 0 0 100010 sh imm12 Rn Rd
        if (w1 & 0xFFC00000) != 0x91000000:
            continue
        rd0 = w0 & 0x1F
        rd1 = w1 & 0x1F
        rn1 = (w1 >> 5) & 0x1F
        if rd0 != rn1:
            continue
        immlo = (w0 >> 29) & 3
        immhi = (w0 >> 5) & 0x7FFFF
        imm = (immhi << 2) | immlo
        if imm & (1 << 20):
            imm -= 1 << 21
        page = (off + 0x1000) & ~0xFFF
        page_base = page + imm * 0x1000
        immed = (w1 >> 10) & 0xFFF
        addr = page_base + immed
        if addr == TARGET:
            hits.append((0x1000 + off, 0x1000 + off + 4))

print("引用 0x%x 的指令对：%d 处" % (TARGET, len(hits)))
for a, b in hits:
    print("  adrp @ 0x%08x, add @ 0x%08x" % (a, b))