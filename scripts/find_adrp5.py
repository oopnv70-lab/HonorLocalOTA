import struct

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

text_off = [s for s in secs if s[1] == 0x1000][0][2]
text = d[text_off:text_off + 0x1D3000]

# 找 adrp 目标在 0x1d5000 页的所有指令，列出后续 3 条
print("=== adrp 指向 0x1d5000 页的指令 ===")
n = 0
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
    addr = ((i + 0x1000) & ~0xFFF) + imm * 0x1000
    if addr == 0x1D5000:
        va = 0x1000 + i
        nxt = []
        for k in range(1, 4):
            wk = struct.unpack_from("<I", text, i + k * 4)[0]
            nxt.append("%08x" % wk)
        print("  0x%08x  adrp x%d, #0x1d5000   next: %s" % (va, rd, " ".join(nxt)))
        n += 1
        if n > 40:
            break
print("  合计 %d 处" % n)