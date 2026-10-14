// Publica 7 mensajes de prueba (validos, malformados y de falla simulada) en RabbitMQ via la API HTTP de gestion.
// Uso (Node 18+):  node infra/publicar-mensajes-prueba.js     [requiere el stack levantado, notify con PEDIDOS360_SIMULATE_FAILURES=true]
// Luego: bash infra/verificar-cluster.sh   y   docker logs pedidos360-notify
const auth = "Basic " + Buffer.from("guest:guest").toString("base64");

function envelope(eventId, type, payload) {
  return JSON.stringify({
    type, eventId, timestamp: new Date().toISOString(),
    traceId: "t-" + eventId, correlationId: "demo", payload,
  });
}

async function publish(exchange, routingKey, payloadString, label) {
  const res = await fetch(`http://localhost:15672/api/exchanges/%2F/${exchange}/publish`, {
    method: "POST",
    headers: { Authorization: auth, "Content-Type": "application/json" },
    body: JSON.stringify({
      properties: { content_type: "application/json" },
      routing_key: routingKey, payload: payloadString, payload_encoding: "string",
    }),
  });
  const body = await res.json();
  console.log(`${label.padEnd(46)} -> ${exchange}/${routingKey}  routed=${body.routed}`);
}

(async () => {
  const id = Date.now();
  // 1-3: validos (email por TOPIC, kitchen e invoice por DIRECT)
  await publish("cmd.topic", "cmd.email.aceptado",
    envelope("ok-email-" + id, "email.send", { orderId: 1, customerId: "cliente-123", status: "ACEPTADO" }),
    "1 email valido (topic cmd.email.aceptado)");
  await publish("cmd.direct", "kitchen.ticket",
    envelope("ok-kitchen-" + id, "kitchen.ticket", { orderId: 1, items: 2 }),
    "2 kitchen valido (direct)");
  await publish("cmd.direct", "invoice.gen",
    envelope("ok-invoice-" + id, "invoice.gen", { orderId: 1, totalAmount: 4500 }),
    "3 invoice valido (direct)");
  // 4: cuerpo que no es JSON -> DLQ email
  await publish("cmd.direct", "email.send", "esto no es json", "4 cuerpo NO JSON (-> DLQ email)");
  // 5: falta campo obligatorio (items) -> error no recuperable -> DLQ kitchen
  await publish("cmd.direct", "kitchen.ticket",
    envelope("bad-kitchen-" + id, "kitchen.ticket", { orderId: 2 }),
    "5 kitchen sin 'items' (-> DLQ kitchen)");
  // 6: falla simulada recuperable -> 1 reintento -> DLQ invoice
  await publish("cmd.direct", "invoice.gen",
    envelope("trans-invoice-" + id, "invoice.gen", { orderId: 3, totalAmount: 100, failMode: "transient" }),
    "6 invoice failMode=transient (retry y DLQ)");
  // 7: falla simulada no recuperable -> DLQ email directo
  await publish("cmd.topic", "cmd.email.entregado",
    envelope("poison-email-" + id, "email.send", { orderId: 4, customerId: "x", status: "ENTREGADO", failMode: "poison" }),
    "7 email failMode=poison (-> DLQ email)");
})();
