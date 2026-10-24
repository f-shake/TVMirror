"""TVMirror 电视端协议探针：验证握手、配对码、忙线、心跳在真机上的行为。

不发视频包，只验控制面，所以不需要编码器。
用法：python probe_tv.py 192.168.0.88 <配对码>
"""
import json
import socket
import struct
import sys

PORT = 27183
VIDEO, KEYFRAME_REQ, PING, PONG, CONFIG, AUDIO, BYE, FORMAT = 1, 2, 3, 4, 5, 6, 7, 8


def frame(ftype, pts=0, payload=b""):
    body = struct.pack(">Bq", ftype, pts) + payload
    return struct.pack(">I", len(body)) + body


def read_line(sock):
    data = b""
    while True:
        chunk = sock.recv(1)
        if not chunk:
            return data.decode("utf-8", "replace")
        if chunk == b"\n":
            return data.decode("utf-8")
        data += chunk


def handshake(host, pin, codec="avc"):
    s = socket.create_connection((host, PORT), timeout=5)
    s.settimeout(5)
    payload = {
        "v": 1, "codec": codec, "preset": "latency",
        "width": 1280, "height": 720, "fps": 60, "bitrate": 8000000,
        "audio": False, "pin": pin, "name": "probe",
    }
    s.sendall((json.dumps(payload) + "\n").encode("utf-8"))
    return s, read_line(s)


def check(name, reply, expect):
    ok = expect in reply
    print(f"[{'PASS' if ok else 'FAIL'}] {name}: {reply!r}")
    return ok


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "192.168.0.88"
    pin = sys.argv[2] if len(sys.argv) > 2 else "0000"
    wrong_pin = "0000" if pin != "0000" else "9999"

    s, reply = handshake(host, wrong_pin)
    check("错误配对码应被拒绝", reply, '"err":"pin"')
    s.close()

    s, reply = handshake(host, pin, codec="mpeg2")
    check("未知编码应被拒绝", reply, '"err":"codec"')
    s.close()

    keep, reply = handshake(host, pin)
    if not check("正确配对码应握手成功", reply, '"ok":true'):
        print("[INFO] 未能建立会话，跳过后续用例")
        return

    keep.sendall(frame(PING, 123456789))
    prefix = keep.recv(4)
    length = struct.unpack(">I", prefix)[0]
    body = b""
    while len(body) < length:
        chunk = keep.recv(length - len(body))
        if not chunk:
            break
        body += chunk
    ftype, _ = struct.unpack(">Bq", body[:9])
    echoed = struct.unpack(">q", body[9:17])[0] if len(body) >= 17 else None
    ok = ftype == PONG and echoed == 123456789
    print(f"[{'PASS' if ok else 'FAIL'}] 心跳往返应原样回显: type={ftype} echoed={echoed}")

    busy, busy_reply = handshake(host, pin)
    check("已有人投屏时应拒绝第二台", busy_reply, '"err":"busy"')
    busy.close()

    keep.sendall(frame(BYE))
    keep.close()
    print("[INFO] 会话已主动结束")


if __name__ == "__main__":
    main()
