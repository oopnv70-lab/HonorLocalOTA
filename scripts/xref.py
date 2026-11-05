import struct
import sys

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


def o2v(o):
    for va, vs, raw in secs:
        if raw <= o < raw + vs:
            return va + (o - raw)
    return None


kw = sys.argv[1].encode()
off = d.find(kw)
text_off = v2o(0x1000)
text = d[text_off:text_off + 0x1D3000]
while off >= 0:
    va = o2v(off)
    print("string %r  file_off=0x%x  va=%s" % (kw.decode(errors="replace"), off, hex(va) if va else "?"))
    if va:
        found = []
        for i in range(0, len(text) - 8, 4):
            w0 = struct.unpack_from("<I", text, i)[0]
            w1 = struct.unpack_from("<I", text, i + 4)[0]
            if (w0 & 0x9F000000) != 0x90000000:
                continue
            if (w1 & 0xFFC00000) != 0x91000000:
                continue
            rd0 = w0 & 0x1F
            rn1 = (w1 >> 5) & 0x1F
            if rd0 != rn1:
                continue
            immlo = (w0 >> 29) & 3
            immhi = (w0 >> 5) & 0x7FFFF
            imm = (immhi << 2) | immlo
            if imm & (1 << 20):
                imm -= 1 << 21
            page = (i + 0x1000) & ~0xFFF
            addr = page + imm * 0x1000 + ((w1 >> 10) & 0xFFF)
            if addr == va:
                found.append(0x1000 + i)
        if found:
            print("   xref: %s" % ", ".join("0x%x" % f for f in found[:12]))
    off = d.find(kw, off + 1)