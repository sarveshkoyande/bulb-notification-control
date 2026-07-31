import struct, sys

def parse(path):
    data = open(path, "rb").read()
    assert data[:8] == b"btsnoop\x00", "not a btsnoop file"
    version, datalink = struct.unpack(">II", data[8:16])
    off = 16
    recs = []
    while off + 24 <= len(data):
        orig_len, incl_len, flags, drops, ts = struct.unpack(">IIIIq", data[off:off+24])
        off += 24
        pkt = data[off:off+incl_len]
        off += incl_len
        recs.append((ts, flags, pkt))
    return datalink, recs

# opcodes of interest
OP_SET_ADV_DATA      = 0x2008
OP_SET_SCAN_RSP      = 0x2009
OP_SET_ADV_ENABLE    = 0x200A
OP_SET_ADV_PARAM     = 0x2006
OP_EXT_ADV_DATA      = 0x2037
OP_EXT_ADV_ENABLE    = 0x2039

def h(b): return b.hex()

def main(path):
    datalink, recs = parse(path)
    print(f"# datalink={datalink} records={len(recs)}", file=sys.stderr)
    t0 = None
    for ts, flags, pkt in recs:
        if not pkt: continue
        # H4: first byte = type (1=cmd,2=acl,4=evt). datalink 1002 = H4.
        ptype = pkt[0]
        body = pkt[1:]
        if ptype != 0x01:  # only HCI commands (host -> controller)
            continue
        if len(body) < 3: continue
        opcode = struct.unpack("<H", body[0:2])[0]
        plen = body[2]
        params = body[3:3+plen]
        if opcode in (OP_SET_ADV_DATA, OP_SET_SCAN_RSP):
            # params: 1 byte significant length + 31 bytes
            adlen = params[0] if params else 0
            ad = params[1:1+adlen]
            name = "ADV_DATA" if opcode==OP_SET_ADV_DATA else "SCAN_RSP"
            if t0 is None: t0 = ts
            print(f"[{(ts-t0)/1e6:8.3f}s] {name} len={adlen} : {h(ad)}")
            dump_ad(ad)
        elif opcode == OP_EXT_ADV_DATA:
            # handle, op, fragpref, datalen, data...
            if len(params) >= 4:
                adlen = params[3]
                ad = params[4:4+adlen]
                if t0 is None: t0 = ts
                print(f"[{(ts-t0)/1e6:8.3f}s] EXT_ADV_DATA len={adlen} : {h(ad)}")
                dump_ad(ad)
        elif opcode in (OP_SET_ADV_ENABLE, OP_EXT_ADV_ENABLE):
            en = params[0] if params else '?'
            if t0 is None: t0 = ts
            print(f"[{(ts-t0)/1e6:8.3f}s] ADV_ENABLE = {en}")

def dump_ad(ad):
    # walk AD structures
    i = 0
    while i < len(ad):
        l = ad[i]
        if l == 0: break
        t = ad[i+1] if i+1 < len(ad) else None
        val = ad[i+2:i+1+l]
        tn = {0x01:"Flags",0x02:"UUID16p",0x03:"UUID16c",0x06:"UUID128",
              0x08:"NameShort",0x09:"NameComplete",0x16:"ServiceData16",
              0x20:"ServiceData32",0x21:"ServiceData128",0xFF:"MfgData"}.get(t, hex(t) if t is not None else "?")
        print(f"      AD type={tn} : {val.hex()}")
        i += l + 1

if __name__ == "__main__":
    main(sys.argv[1])
