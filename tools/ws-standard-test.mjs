// 用标准 WebSocket 实现验证桌面端服务端的 RFC 6455 兼容性。
const port = process.argv[2];
const pin = process.argv[3];
const ws = new WebSocket(`ws://127.0.0.1:${port}/smsbridge`);
let finished = false;

function finish(code, message) {
  if (finished) return;
  finished = true;
  console.log(message);
  try { ws.close(); } catch (e) {}
  setTimeout(() => process.exit(code), 100);
}

ws.onopen = () => {
  ws.send(JSON.stringify({
    type: "pair",
    deviceId: "node-standard-client",
    deviceName: "Node 标准客户端",
    pin: pin
  }));
};

ws.onmessage = (event) => {
  let msg;
  try { msg = JSON.parse(event.data); } catch (e) { return; }
  if (msg.type === "pair_ok") {
    console.log("pair_ok token=" + (msg.token || "").length);
    ws.send(JSON.stringify({
      type: "sms",
      deviceId: "node-standard-client",
      from: "标准客户端",
      body: "【测试】您的验证码是 864209，请勿泄露。",
      ts: Date.now(),
      id: "node-sms-1"
    }));
  } else if (msg.type === "sms_ack") {
    if (msg.code === "864209" && msg.copied === true) {
      finish(0, "sms_ack code=864209 copied=true");
    } else {
      finish(1, "unexpected ack: " + JSON.stringify(msg));
    }
  }
};

ws.onerror = (e) => finish(1, "websocket error");
setTimeout(() => finish(1, "timeout"), 8000);
