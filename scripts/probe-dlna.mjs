#!/usr/bin/env node

import { spawnSync } from "node:child_process";
import dgram from "node:dgram";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptDir, "..");
const timeoutMs = Number.parseInt(process.env.CAST_PROBE_TIMEOUT_MS || "6000", 10);
const ssdpAddress = "239.255.255.250";
const ssdpPort = 1900;

if (!Number.isFinite(timeoutMs) || timeoutMs < 1000) {
  throw new Error("CAST_PROBE_TIMEOUT_MS 必须是不小于 1000 的整数");
}

function xpath(xml, expression) {
  const result = spawnSync(
    "/usr/bin/xmllint",
    ["--nonet", "--xpath", expression, "-"],
    { input: xml, encoding: "utf8" },
  );
  if (result.status !== 0) {
    const detail = (result.stderr || result.stdout || "XML 解析失败").trim();
    throw new Error(detail);
  }
  return result.stdout.trim();
}

const stringsXml = readFileSync(
  join(root, "app/src/main/res/values/strings.xml"),
  "utf8",
);
const expectedName = xpath(
  stringsXml,
  "string(/resources/string[@name='app_name'])",
);

async function fetchText(url, init = {}) {
  const response = await fetch(url, {
    redirect: "follow",
    signal: AbortSignal.timeout(Math.min(timeoutMs, 4000)),
    ...init,
  });
  if (!response.ok) {
    throw new Error(`${url} 返回 HTTP ${response.status}`);
  }
  return response.text();
}

function parseSsdpHeaders(payload) {
  const lines = payload.toString("utf8").split(/\r?\n/);
  const headers = new Map();
  for (const line of lines.slice(1)) {
    const separator = line.indexOf(":");
    if (separator <= 0) continue;
    headers.set(
      line.slice(0, separator).trim().toLowerCase(),
      line.slice(separator + 1).trim(),
    );
  }
  return headers;
}

function discoverRenderer() {
  return new Promise((resolveDiscovery, rejectDiscovery) => {
    const socket = dgram.createSocket("udp4");
    const seenLocations = new Set();
    let finished = false;

    const finish = (error, result) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      clearTimeout(retryTimer);
      socket.close();
      if (error) rejectDiscovery(error);
      else resolveDiscovery(result);
    };

    const timer = setTimeout(() => {
      finish(
        new Error(
          `在 ${timeoutMs}ms 内未发现 ${expectedName}（收到 ${seenLocations.size} 个描述地址）`,
        ),
      );
    }, timeoutMs);
    let retryTimer;

    socket.on("error", (error) => finish(error));
    socket.on("message", async (message) => {
      const location = parseSsdpHeaders(message).get("location");
      if (!location || seenLocations.has(location)) return;
      seenLocations.add(location);

      try {
        const description = await fetchText(location);
        const friendlyName = xpath(
          description,
          "string((//*[local-name()='friendlyName'])[1])",
        );
        if (friendlyName !== expectedName) return;
        finish(null, { description, location });
      } catch {
        // Other renderers on the LAN must not make this receiver's probe fail.
      }
    });

    const request = Buffer.from(
      [
        "M-SEARCH * HTTP/1.1",
        `HOST: ${ssdpAddress}:${ssdpPort}`,
        'MAN: "ssdp:discover"',
        "MX: 1",
        "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
        "",
        "",
      ].join("\r\n"),
      "utf8",
    );
    const sendSearch = () => socket.send(request, ssdpPort, ssdpAddress);

    socket.bind(0, "0.0.0.0", () => {
      sendSearch();
      retryTimer = setTimeout(sendSearch, 1000);
    });
  });
}

function extractService(description, serviceType) {
  const service = `(//*[local-name()='service'][*[local-name()='serviceType' and normalize-space(text())='${serviceType}']])[1]`;
  const scpdUrl = xpath(
    description,
    `string(${service}/*[local-name()='SCPDURL'])`,
  );
  const controlUrl = xpath(
    description,
    `string(${service}/*[local-name()='controlURL'])`,
  );
  if (!scpdUrl || !controlUrl) {
    throw new Error(`设备描述缺少 ${serviceType}`);
  }
  return { scpdUrl, controlUrl };
}

const { description, location } = await discoverRenderer();
const deviceType = xpath(
  description,
  "string((//*[local-name()='deviceType'])[1])",
);
if (deviceType !== "urn:schemas-upnp-org:device:MediaRenderer:1") {
  throw new Error(`设备类型不匹配：${deviceType || "空"}`);
}

const services = [
  {
    label: "AVTransport",
    type: "urn:schemas-upnp-org:service:AVTransport:1",
    requiredAction: "SetAVTransportURI",
  },
  {
    label: "RenderingControl",
    type: "urn:schemas-upnp-org:service:RenderingControl:1",
    requiredAction: "SetVolume",
  },
  {
    label: "ConnectionManager",
    type: "urn:schemas-upnp-org:service:ConnectionManager:1",
    requiredAction: "GetProtocolInfo",
  },
];

const resolvedServices = new Map();
for (const service of services) {
  const paths = extractService(description, service.type);
  const scpdUrl = new URL(paths.scpdUrl, location).href;
  const controlUrl = new URL(paths.controlUrl, location).href;
  const scpd = await fetchText(scpdUrl);
  const actionCount = Number.parseInt(
    xpath(
      scpd,
      `count(//*[local-name()='action']/*[local-name()='name' and normalize-space(text())='${service.requiredAction}'])`,
    ),
    10,
  );
  if (actionCount < 1) {
    throw new Error(`${service.label} 缺少 ${service.requiredAction}`);
  }
  resolvedServices.set(service.label, { ...paths, controlUrl });
}

const connectionManager = resolvedServices.get("ConnectionManager");
const soapAction = "urn:schemas-upnp-org:service:ConnectionManager:1#GetProtocolInfo";
const soapBody = `<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetProtocolInfo xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1" />
  </s:Body>
</s:Envelope>`;
const soapResponse = await fetchText(connectionManager.controlUrl, {
  method: "POST",
  headers: {
    "Content-Type": 'text/xml; charset="utf-8"',
    SOAPAction: `"${soapAction}"`,
  },
  body: soapBody,
});
const protocolResponseCount = Number.parseInt(
  xpath(soapResponse, "count(//*[local-name()='GetProtocolInfoResponse'])"),
  10,
);
const sinkCount = Number.parseInt(
  xpath(soapResponse, "count(//*[local-name()='Sink'])"),
  10,
);
if (protocolResponseCount !== 1 || sinkCount !== 1) {
  throw new Error("GetProtocolInfo SOAP 响应不完整");
}

const rendererAddress = new URL(location);
console.log(`DLNA 探测通过：${expectedName}`);
console.log(`- 接收端：${rendererAddress.hostname}:${rendererAddress.port || "80"}`);
console.log("- SSDP、设备描述、三项 SCPD 与 GetProtocolInfo：正常");
