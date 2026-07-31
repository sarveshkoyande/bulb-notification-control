def crc8(data, init=0x7d, poly=0x07):
    crc = init
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = ((crc << 1) ^ poly) & 0xFF if (crc & 0x80) else (crc << 1) & 0xFF
    return crc

frames = [
 ("PRE ", "137e1c000700300102e15766e93c6c4bcd922c78ab32fbb72aee"),
 ("PRE ", "137e1c000700310102e15766e93c6c4bcd922c78ab32fbb72a38"),
 ("PRE ", "137e1c000700320102e15766e93c6c4bcd922c78ab32fbb72a45"),
 ("0104", "0b7e1c000700330104a6e2821d4a446733ebc84a8815448b39b7"),
 ("PRE2", "137e1c00080034010201a3995897a060bcba1ccf674de551a70a"),
 ("ON  ", "0b7e1c000800350533ce41efa6d7b9782770bb518e60132c100b"),
 ("OFF ", "0b7e1c0008003605d6b1bf63503f8f85a54347f805cce4d042d7"),
 ("ON  ", "0b7e1c000800370533ce41efa6d7b9782770bb518e60132c10a0"),
 ("RED?", "0b7e1c0008003805d783f8bf0cd4dc8a70e977c7fb86546fc5fb"),
 ("OFF ", "0b7e1c0008003905d6b1bf63503f8f85a54347f805cce4d04259"),
]

print("=== CRC check (init 0x7d, poly 0x07) ===")
allok = True
for label, h in frames:
    p = bytes.fromhex(h)
    c = crc8(p[:-1])
    ok = c == p[-1]
    allok &= ok
    print(f"{label} epoch={p[3]:02x}{p[4]:02x} seq={p[5]:02x}{p[6]:02x} crc={c:02x}/{p[-1]:02x} {'OK' if ok else 'MISMATCH'}")
print("ALL CRC OK" if allok else "SOME FAILED")

print("\n=== payload17 (byte8..24) — first byte is the plaintext command id ===")
for label, h in frames:
    p = bytes.fromhex(h)
    if p[0] == 0x0b and p[7] == 0x05:
        print(f"{label} id={p[8]:02x} block={p[9:25].hex()}")

print("\n=== old vs new (re-pair changed the AES block, id byte stayed) ===")
old = {"ON": "33e8133e2195b5e01c66e4fdca6314d00b", "OFF": "d6b8d8714ece3182c6fb800f02770f3f79"}
new = {"ON": "33ce41efa6d7b9782770bb518e60132c10", "OFF": "d6b1bf63503f8f85a54347f805cce4d042"}
for k in old:
    print(f"{k}: old id={old[k][:2]} block={old[k][2:]}")
    print(f"{k}: new id={new[k][:2]} block={new[k][2:]}")

print("\n=== CURRENT values to hardcode ===")
print("epoch    = 0x0008")
print("next seq = 0x0040  (last app seq was 0x0039)")
print("preamble = 01a3995897a060bcba1ccf674de551a7   (type 0102, epoch 0008)")
print("ON       = 33ce41efa6d7b9782770bb518e60132c10")
print("OFF      = d6b1bf63503f8f85a54347f805cce4d042")
