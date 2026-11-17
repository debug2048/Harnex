// Public HTTP entrypoints share a default; managed Runtime ports are allocated separately.
export const DEFAULT_WEB_PORT = 18081;

export function resolveWebPort(args = [], env = process.env, envKeys = ['HARNEX_PORT', 'SERVER_PORT']) {
  for (const arg of args) {
    const match = /^--(?:server\.)?port=(\d+)$/.exec(String(arg));
    if (match && Number(match[1]) >= 1 && Number(match[1]) <= 65535) return Number(match[1]);
  }
  for (const key of envKeys) {
    const port = Number(env[key]);
    if (Number.isInteger(port) && port >= 1 && port <= 65535) return port;
  }
  return DEFAULT_WEB_PORT;
}
