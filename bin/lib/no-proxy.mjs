const LOOPBACK_NO_PROXY = ["localhost", "127.0.0.1", "::1"];

function splitNoProxy(value) {
  return String(value || "")
    .split(",")
    .map(v => v.trim())
    .filter(Boolean);
}

export function ensureLoopbackNoProxy(env = process.env) {
  const entries = [];
  const seen = new Set();

  for (const entry of [...splitNoProxy(env.NO_PROXY), ...splitNoProxy(env.no_proxy)]) {
    const key = entry.toLowerCase();
    if (seen.has(key)) continue;
    entries.push(entry);
    seen.add(key);
  }

  for (const host of LOOPBACK_NO_PROXY) {
    const key = host.toLowerCase();
    if (seen.has(key)) continue;
    entries.push(host);
    seen.add(key);
  }

  const value = entries.join(",");
  env.NO_PROXY = value;
  env.no_proxy = value;
  return value;
}
