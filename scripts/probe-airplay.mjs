#!/usr/bin/env node

import { spawn } from "node:child_process";
import { readFileSync } from "node:fs";
import net from "node:net";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const root = resolve(scriptDir, "..");
const timeoutMs = Number.parseInt(process.env.CAST_PROBE_TIMEOUT_MS || "5000", 10);

if (!Number.isFinite(timeoutMs) || timeoutMs < 1000) {
  throw new Error("CAST_PROBE_TIMEOUT_MS 必须是不小于 1000 的整数");
}

function captureDnsSd(args) {
  return new Promise((resolveCapture, rejectCapture) => {
    const child = spawn("/usr/bin/dns-sd", args, {
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    child.stdout.setEncoding("utf8");
    child.stderr.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      output += chunk;
    });
    child.stderr.on("data", (chunk) => {
      output += chunk;
    });
    child.on("error", rejectCapture);

    const timer = setTimeout(() => child.kill("SIGINT"), timeoutMs);
    child.on("close", () => {
      clearTimeout(timer);
      resolveCapture(output);
    });
  });
}

async function canConnect(host, port) {
  await new Promise((resolveConnection, rejectConnection) => {
    const socket = net.createConnection({ host, port });
    const timer = setTimeout(() => {
      socket.destroy();
      rejectConnection(new Error(`${host}:${port} 连接超时`));
    }, Math.min(timeoutMs, 3000));
    socket.once("connect", () => {
      clearTimeout(timer);
      socket.end();
      resolveConnection();
    });
    socket.once("error", (error) => {
      clearTimeout(timer);
      rejectConnection(error);
    });
  });
}

const stringsXml = readFileSync(
  join(root, "app/src/main/res/values/strings.xml"),
  "utf8",
);
const nameMatch = stringsXml.match(
  /<string\s+name=["']app_name["']>([^<]+)<\/string>/,
);
if (!nameMatch) throw new Error("无法从 strings.xml 读取接收器名称");
const expectedName = nameMatch[1];

const [airplayBrowse, raopBrowse] = await Promise.all([
  captureDnsSd(["-B", "_airplay._tcp", "local."]),
  captureDnsSd(["-B", "_raop._tcp", "local."]),
]);

const airplayInstance = airplayBrowse
  .split(/\r?\n/)
  .map((line) => line.match(/_airplay\._tcp\.\s+(.+)$/)?.[1]?.trim())
  .find((name) => name === expectedName);
if (!airplayInstance) {
  throw new Error(`mDNS 未发现 ${expectedName}._airplay._tcp`);
}

const raopInstance = raopBrowse
  .split(/\r?\n/)
  .map((line) => line.match(/_raop\._tcp\.\s+(.+)$/)?.[1]?.trim())
  .find((name) => name?.endsWith(`@${expectedName}`));
if (!raopInstance) {
  throw new Error(`mDNS 未发现 *@${expectedName}._raop._tcp`);
}

const [airplayLookup, raopLookup] = await Promise.all([
  captureDnsSd(["-L", airplayInstance, "_airplay._tcp", "local."]),
  captureDnsSd(["-L", raopInstance, "_raop._tcp", "local."]),
]);

const endpoint = airplayLookup.match(/can be reached at\s+(\S+):(\d+)/);
if (!endpoint) throw new Error("AirPlay 服务无法解析到 TCP 地址");
const host = endpoint[1];
const port = Number.parseInt(endpoint[2], 10);
if (port !== 7000) throw new Error(`AirPlay 端口应为 7000，实际为 ${port}`);

for (const attribute of [
  "features=0x5A7FFEF7,0x400",
  "pw=false",
  "model=AppleTV3,2",
]) {
  if (!airplayLookup.includes(attribute)) {
    throw new Error(`AirPlay TXT 缺少 ${attribute}`);
  }
}
if (!raopLookup.includes("can be reached at")) {
  throw new Error("RAOP 服务无法解析到 TCP 地址");
}

await canConnect(host, port);

console.log(`AirPlay 探测通过：${expectedName}`);
console.log(`- _airplay._tcp：${host}:${port}`);
console.log(`- _raop._tcp：${raopInstance}`);
console.log("- AirPlay TXT 与 TCP 7000：正常");
