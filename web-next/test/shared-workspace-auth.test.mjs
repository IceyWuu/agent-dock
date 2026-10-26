import assert from "node:assert/strict"
import test from "node:test"
import { readFileSync } from "node:fs"
import { JSDOM } from "jsdom"
import { registerSource } from "./helpers/onboarding-source.mjs"
const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "https://fixture.example/", pretendToBeVisual: true })
for (const name of ["window", "document", "navigator", "HTMLElement", "Element", "Node", "Event", "MutationObserver"]) Object.defineProperty(globalThis, name, { configurable: true, value: dom.window[name] })
globalThis.IS_REACT_ACT_ENVIRONMENT = true
const { createElement: h, act } = await import("react")
const { createRoot } = await import("react-dom/client")
const { NextIntlClientProvider } = await import("next-intl")
const hooks = registerSource()
const { AuthProvider, useAuth } = await import("../src/components/auth/auth-context.tsx")
const { authApi } = await import("../src/features/auth/api.ts")
hooks.deregister()
const messages = JSON.parse(readFileSync(new URL("../messages/en.json", import.meta.url), "utf8"))
const shared = { accessToken: "shared-token", userId: "owner", role: "admin" }
function State() {
  const state = useAuth()
  return h("output", { "data-screen": state.loading ? "loading" : state.screen, "data-user": state.session?.userId ?? "", "data-error": state.error ?? "" })
}
async function mount(t, hash, fail = false) {
  window.history.replaceState({}, "", `/${hash}`)
  window.localStorage.clear(); window.sessionStorage.clear()
  window.localStorage.setItem("aa.session.v1", JSON.stringify({ accessToken: "old-token", userId: "friend", role: "member" }))
  t.mock.method(authApi, "config", async () => ({ sharedWorkspaceEnabled: true, needsBootstrap: false }))
  t.mock.method(authApi, "sharedSession", async () => { if (fail) throw new Error("shared account disabled"); return shared })
  t.mock.method(authApi, "me", async token => { assert.equal(token, "shared-token"); return { ...shared, disabled: false } })
  const host = document.createElement("div"); document.body.append(host)
  const root = createRoot(host)
  await act(async () => root.render(h(NextIntlClientProvider, { locale: "en", messages, timeZone: "UTC" }, h(AuthProvider, null, h(State)))))
  t.after(async () => { await act(async () => root.unmount()); host.remove() })
  return host.querySelector("output").dataset
}
test("shared boot replaces an old personal account without requesting a password", async t => {
  const result = await mount(t, "#/login")
  assert.equal(result.screen, "app"); assert.equal(result.user, "owner")
  assert.equal(JSON.parse(window.localStorage.getItem("aa.session.v1")).accessToken, "shared-token")
})
test("mobile OAuth continuation survives automatic shared login", async t => {
  const result = await mount(t, "#/mobile-oauth?state=fixture")
  assert.equal(result.screen, "mobile-oauth"); assert.equal(result.user, "owner")
  assert.equal(window.location.hash, "#/mobile-oauth?state=fixture")
})
test("shared login failure cannot fall back to the old personal session", async t => {
  const result = await mount(t, "#/login", true)
  assert.equal(result.screen, "login"); assert.equal(result.user, "")
  assert.equal(result.error, "shared account disabled")
  assert.equal(window.localStorage.getItem("aa.session.v1"), null)
})
