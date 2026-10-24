"""把一个 Annex-B 的 H.264 文件按 TVMirror 协议推给电视，用来在没有手机的情况下验证解码链路。

用法：python send_stream.py 192.168.0.88 <配对码> <h264文件> [fps]
"""
import json
import socket
import struct
import sys
import time

PORT = 27183
VIDEO, PING, PONG, CONFIG, BYE = 1, 3, 4, 5, 7


def frame(ftype, pts, payload=b""):
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


def iter_nals(data):
    starts = []
    i = 0
    while True:
        j = data.find(b"\x00\x00\x01", i)
        if j < 0:
            break
        starts.append(j)
        i = j + 3
    for idx, j in enumerate(starts):
        begin = j + 3
        end = starts[idx + 1] if idx + 1 < len(starts) else len(data)
        nal = data[begin:end].rstrip(b"\x00")
        if nal:
            yield nal


def build_units(nals):
    """按 AUD 切访问单元；SPS/PPS 单独收集成配置帧。"""
    config = []
    units = []
    current = []

    def flush():
        if current:
            units.append(b"".join(b"\x00\x00\x00\x01" + n for n in current))
            current.clear()

    for nal in nals:
        nal_type = nal[0] & 0x1F
        if nal_type in (7, 8):
            config.append(nal)
            continue
        if nal_type == 9:
            flush()
            continue
        current.append(nal)
        if nal_type in (1, 5):
            # 单 slice 一帧的测试流：碰到 VCL 就结束这一帧
            flush()
    flush()
    return b"".join(b"\x00\x00\x00\x01" + n for n in config), units


def main():
    host = sys.argv[1] if len(sys.argv) > 1 else "192.168.0.88"
    pin = sys.argv[2] if len(sys.argv) > 2 else "0000"
    path = sys.argv[3] if len(sys.argv) > 3 else "out/test.h264"
    fps = int(sys.argv[4]) if len(sys.argv) > 4 else 30

    with open(path, "rb") as f:
        data = f.read()
    config, units = build_units(list(iter_nals(data)))
    print(f"配置帧 {len(config)} 字节，访问单元 {len(units)} 个")

    sock = socket.create_connection((host, PORT), timeout=5)
    sock.settimeout(5)
    hello = {
        "v": 1, "codec": "avc", "preset": "latency",
        "width": 1280, "height": 720, "fps": fps, "bitrate": 8000000,
        "audio": False, "pin": pin, "name": "ffmpeg-probe",
    }
    sock.sendall((json.dumps(hello) + "\n").encode("utf-8"))
    reply = read_line(sock)
    print("握手:", reply)
    if '"ok":true' not in reply:
        return

    base = int(time.monotonic() * 1_000_000)
    sock.sendall(frame(CONFIG, 0, config))

    interval = 1.0 / fps
    deadline = time.monotonic()
    for index, unit in enumerate(units):
        pts = base + int(index * 1_000_000 / fps)
        sock.sendall(frame(VIDEO, pts, unit))
        deadline += interval
        sleep = deadline - time.monotonic()
        if sleep > 0:
            time.sleep(sleep)

    print(f"已发送 {len(units)} 帧，保持连接 3 秒…")
    time.sleep(3)
    sock.sendall(frame(BYE, 0))
    sock.close()


if __name__ == "__main__":
    main()
