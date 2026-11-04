"""
本地假的 SChat 聊天服务器 + WebRTC 工作通道对端。

目的：真机上那条链路卡在「和机器人的好友关系还是 pending」，服务端在信令层就回 not_friends，
所以拿真实服务器验不了 App 这段 WebRTC 代码。这里自己当那台「Friday 桌面端」，把
REMOTE.md §2 的流程完整跑一遍，证明 App 这一侧（信令状态机 + PeerConnection + DataChannel
上的 JSON-RPC）是通的。

跑起来后 App 的服务器地址填 https://10.0.2.2:8443（模拟器里的宿主机），随便什么账号密码。

协议要点（照 App 的实现对齐）：
  客户端 -> {"t":"req","e":"hello","d":{...},"a":"a1"}
  服务端 -> {"t":"ack","a":"a1","d":{...}}
  服务端 -> {"t":"ev","e":"p2p_response","d":{"fromUserId":botId,"requestId":..,"accepted":true}}
"""

import asyncio
import json
import ssl
import sys
from pathlib import Path

from aiohttp import web, WSMsgType
from aiortc import RTCPeerConnection, RTCSessionDescription, RTCConfiguration, RTCIceServer

BOT_ID = 100000057
BOT_NAME = "Friday 助手"
MY_ID = 100000060
TOKEN = "local-test-token"
SUB_PROTOCOL = "schat-ws.v1"

HERE = Path(__file__).resolve().parent
CERT = HERE / "cert.pem"
KEY = HERE / "key.pem"


# ---------------------------------------------------------------- WebRTC 对端


class WorkPeer:
    """被叫侧：收 offer、出 answer、在 DataChannel「friday-work」上跑 JSON-RPC。"""

    def __init__(self, ws_send, request_id: str):
        self.ws_send = ws_send
        self.request_id = request_id
        self.pc = RTCPeerConnection(
            RTCConfiguration(iceServers=[RTCIceServer(urls="stun:stun.l.google.com:19302")])
        )
        self.channel = None
        self.got_hello = asyncio.Event()

        @self.pc.on("icecandidate")
        async def on_ice(candidate):
            if candidate is None:
                return
            payload = json.dumps(
                {
                    "candidate": candidate.candidate,
                    "sdpMid": candidate.sdpMid,
                    "sdpMLineIndex": candidate.sdpMLineIndex,
                }
            )
            await self.ws_send("p2p_signaling", {
                "toUserId": MY_ID, "targetUserId": MY_ID, "fromUserId": BOT_ID,
                "requestId": self.request_id, "type": "ice_candidate", "payload": payload,
            })

        @self.pc.on("datachannel")
        def on_datachannel(channel):
            self.channel = channel
            print(f"[webrtc] DataChannel 开了：{channel.label}", flush=True)

            @channel.on("message")
            def on_message(message):
                asyncio.ensure_future(self.handle_message(message))

        @self.pc.on("connectionstatechange")
        async def on_state():
            print(f"[webrtc] 连接状态：{self.pc.connectionState}", flush=True)

    async def handle_message(self, message):
        if isinstance(message, bytes):
            message = message.decode("utf-8", "replace")
        print(f"[rpc] <- {message[:200]}", flush=True)
        try:
            request = json.loads(message)
        except Exception:
            return
        # App 的 RpcClient 发的是 JSON-RPC 2.0：{jsonrpc, id, method, params}
        if "id" not in request:
            return
        method = request.get("method")
        reply = {"jsonrpc": "2.0", "id": request["id"]}
        try:
            reply["result"] = self.answer(method, request.get("params") or {})
        except Exception as error:  # noqa: BLE001
            reply["error"] = {"code": -32000, "message": str(error)}
        text = json.dumps(reply, ensure_ascii=False)
        print(f"[rpc] -> {text[:200]}", flush=True)
        self.channel.send(text)

    def answer(self, method, params):
        """只回最少够界面显示的东西：证明 DataChannel 上真的能来回。"""
        if method == "hello":
            # 桌面端回的 hello 把服务器信息包在 server 里（REMOTE.md §4）
            return {
                "protocol": 1,
                "server": {
                    "version": "0.3.0-local",
                    "platform": "darwin",
                    "hostname": "local-fake-desktop",
                    "user": "local",
                    "dataDir": "/tmp/friday",
                    "host": "desktop",
                    "defaultWorkingDirectory": "/tmp/friday",
                    "features": ["services"],
                },
            }
        if method in ("sessions.list", "session.list"):
            return {"sessions": []}
        if method == "server.features":
            return {"features": ["services"], "version": "0.3.0-local"}
        return {}


# ---------------------------------------------------------------- HTTP


def make_tls():
    if CERT.exists() and KEY.exists():
        return
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    import datetime

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = issuer = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "local-fake-schat")])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365))
        .add_extension(
            x509.SubjectAlternativeName([
                x509.DNSName("localhost"),
                x509.IPAddress(__import__("ipaddress").ip_address("10.0.2.2")),
                x509.IPAddress(__import__("ipaddress").ip_address("127.0.0.1")),
            ]),
            critical=False,
        )
        .sign(key, hashes.SHA256())
    )
    CERT.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    KEY.write_bytes(
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.TraditionalOpenSSL,
            serialization.NoEncryption(),
        )
    )


async def handle_login(request):
    body = await request.json()
    print(f"[http] 登录：{body.get('username')}", flush=True)
    return web.json_response({
        "userId": MY_ID,
        "token": TOKEN,
        "accessToken": TOKEN,
        "refreshToken": "local-refresh",
        "expiresIn": 86400,
    })


async def handle_friends(request):
    return web.json_response([
        {
            "id": BOT_ID,
            "nickname": BOT_NAME,
            "name": BOT_NAME,
            "isBot": True,
            "botCalls": True,
            "botDisabled": False,
            "relation": {"ext": {"remark": BOT_NAME}},
        }
    ])


async def handle_ice(request):
    return web.json_response([])



async def handle_ws(request):
    ws = web.WebSocketResponse(protocols=[SUB_PROTOCOL])
    await ws.prepare(request)
    peer = None
    print("[ws] 客户端连上了", flush=True)

    async def send_event(event: str, data: dict):
        await ws.send_str(json.dumps({"t": "ev", "e": event, "d": data}, ensure_ascii=False))

    async for message in ws:
        if message.type != WSMsgType.TEXT:
            continue
        envelope = json.loads(message.data)
        event = envelope.get("e")
        data = envelope.get("d") or {}
        ack = envelope.get("a")
        print(f"[ws] <- {event} {json.dumps(data, ensure_ascii=False)[:150]}", flush=True)

        if ack:
            payload = {"id": MY_ID} if event == "hello" else {"success": True}
            await ws.send_str(json.dumps({"t": "ack", "a": ack, "d": payload}))

        if event == "p2p_request":
            request_id = data.get("requestId")
            print(f"[ws] 收到呼叫 callType={data.get('callType')}，接受", flush=True)
            await send_event("p2p_response", {
                "fromUserId": BOT_ID, "toUserId": MY_ID,
                "requestId": request_id, "accepted": True,
            })

        elif event == "p2p_signaling" and data.get("type") == "offer":
            request_id = data.get("requestId")
            peer = WorkPeer(send_event, request_id)
            await peer.pc.setRemoteDescription(
                RTCSessionDescription(sdp=data.get("payload"), type="offer")
            )
            answer = await peer.pc.createAnswer()
            await peer.pc.setLocalDescription(answer)
            print("[webrtc] 回了 answer", flush=True)
            await send_event("p2p_signaling", {
                "toUserId": MY_ID, "targetUserId": MY_ID, "fromUserId": BOT_ID,
                "requestId": request_id, "type": "answer",
                "payload": peer.pc.localDescription.sdp,
            })

        elif event == "p2p_signaling" and data.get("type") == "ice_candidate" and peer:
            payload = json.loads(data.get("payload") or "{}")
            raw = (payload.get("candidate") or "").strip()
            if raw:
                from aiortc.sdp import candidate_from_sdp
                try:
                    candidate = candidate_from_sdp(raw.removeprefix("candidate:"))
                    candidate.sdpMid = payload.get("sdpMid")
                    candidate.sdpMLineIndex = payload.get("sdpMLineIndex")
                    await peer.pc.addIceCandidate(candidate)
                except Exception as error:  # noqa: BLE001
                    print(f"[webrtc] ICE 加不上：{error}  raw={raw[:80]}", flush=True)

    print("[ws] 客户端断开了", flush=True)
    if peer:
        await peer.pc.close()
    return ws


def main():
    make_tls()
    app = web.Application()
    app.router.add_post("/auth/login", handle_login)
    app.router.add_get("/friends", handle_friends)
    app.router.add_get("/calls/ice-servers", handle_ice)
    app.router.add_get("/ws", handle_ws)
    app.router.add_get("/schat-ws", handle_ws)  # 两种都接，方便对照

    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8443
    plain = "--http" in sys.argv
    if plain:
        print(f"假 chatserver（明文 HTTP）起在 http://0.0.0.0:{port}（模拟器里填 http://10.0.2.2:{port}）", flush=True)
        web.run_app(app, host="0.0.0.0", port=port, print=None)
        return
    print(f"假 chatserver 起在 https://0.0.0.0:{port}（模拟器里填 https://10.0.2.2:{port}）", flush=True)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(CERT, KEY)
    web.run_app(app, host="0.0.0.0", port=port, ssl_context=context, print=None)


if __name__ == "__main__":
    main()
