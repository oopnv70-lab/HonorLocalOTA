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


# 在 .data 段里找 8 字节指针 == 0x1d54d8
print("=== 在 .data 段找 0x1d54d8 的 8 字节指针 ===")
data_va, data_vs, data_raw = [s for s in secs if s[0] == 0x1D4000][0]
needle = struct.pack("<Q", 0x1D54D8)
for i in range(0, data_vs - 8, 8):
    if d[data_raw + i:data_raw + i + 8] == needle:
        print("  找到 @ VA 0x%x" % (data_va + i))

# 找条目内 name_ptr 指向 "flashing unlock"=0xe805d 的 8 字节值
needle2 = struct.pack("<Q", 0xE805D)
print("")
print("=== 在 .data 段找 0xe805d 的 8 字节指针（应出现在表项里）===")
for i in range(0, data_vs - 8, 8):
    if d[data_raw + i:data_raw + i + 8] == needle2:
        print("  找到 @ VA 0x%x" % (data_va + i))

print("")
print("=== 表项 0 位置确认 ===")
print("  0x1d54d8 处 16 字节:", d[0x1D54D8:0x1D54D8 + 16].hex())

print("")
print("=== 0x1d5400-0x1d5600 区域 dump（找相邻结构）===")
for i in range(0x1D5400, 0x1D5600, 16):
    print("  %06x: %s" % (i, d[i:i + 16].hex(" ", 8)))