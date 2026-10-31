// Hollo Worker: routes app traffic to each group's Durable Object, serves the
// invite page and the Android installer, and hosts the admin console.
//
//   /g/<groupId>/...        the apps' API + WebSocket (server address = https://host/g/<groupId>)
//   /g/<groupId>/join?c=..  invite page (open in Hollo / install help)
//   /install/hollo.apk      Android installer uploaded from the console
//   /console                admin console (password protected)

import { consoleApi } from "./console";
import { joinPage, notFoundPage } from "./pages";
import { errJson, json, type Env } from "./util";

export { Group } from "./group";
export { Registry } from "./registry";

export const registry = (env: Env) => env.REGISTRY.get(env.REGISTRY.idFromName("main"));
export const groupStub = (env: Env, gid: string) => env.GROUP.get(env.GROUP.idFromName(gid));

const GID = /^[a-z0-9]{6,16}$/;

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const path = url.pathname;

    if (path === "/healthz") return new Response("ok");
    if (path === "/" || path === "") return Response.redirect(url.origin + "/console/", 302);

    const g = path.match(/^\/g\/([^/]+)(\/.*)?$/);
    if (g) {
      const gid = g[1];
      const rest = g[2] ?? "/";
      if (!GID.test(gid)) return errJson(404, "no_group", "This group doesn't exist");
      const base = `${url.origin}/g/${gid}`;
      if (rest === "/join" && req.method === "GET") return joinPage(env, gid, base, url.searchParams.get("c") ?? "");
      const inner = new URL(rest + url.search, "https://group.internal");
      const fwd = new Request(inner.toString(), req);
      fwd.headers.set("x-hollo-base", base);
      return groupStub(env, gid).fetch(fwd);
    }

    if (path === "/install/hollo.apk" || path === "/install/walkie.apk") return serveApk(env);

    if (path.startsWith("/console/api/")) return consoleApi(req, env, url);
    if (path === "/console") return Response.redirect(url.origin + "/console/", 301);
    if (path.startsWith("/console/")) {
      // Asset handling serves /console/ as the directory's index.html (and
      // would redirect /console/index.html back to /console/).
      const r = await env.ASSETS.fetch(new Request(new URL(path, url.origin), req));
      if (r.status === 404) return env.ASSETS.fetch(new Request(new URL("/console/", url.origin), req));
      return r;
    }

    if (req.headers.get("accept")?.includes("text/html")) return notFoundPage();
    return errJson(404, "not_found", "Not found");
  },
} satisfies ExportedHandler<Env>;

async function serveApk(env: Env): Promise<Response> {
  const reg = registry(env);
  const s = await reg.settings();
  if (!s.apk) return new Response("The Android app hasn't been uploaded yet.", { status: 404 });
  const n = s.apk.chunks;
  let i = 0;
  const stream = new ReadableStream({
    async pull(ctrl) {
      if (i >= n) return ctrl.close();
      const c = await reg.apkChunk(i++);
      if (c) ctrl.enqueue(new Uint8Array(c));
    },
  });
  return new Response(stream, {
    headers: {
      "content-type": "application/vnd.android.package-archive",
      "content-disposition": 'attachment; filename="hollo.apk"',
      "content-length": String(s.apk.size),
      "cache-control": "no-cache",
    },
  });
}

export { json };
