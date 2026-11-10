/* Focused component checks: node tests/auto-play-log.cjs (from web/). */
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const assert = require('assert/strict')
const compiler = require('vue-template-compiler')
let assertions = 0
function check(value, message) { assert.ok(value, message); assertions++ }
function load(file, additions = {}) {
  const component = compiler.parseComponent(fs.readFileSync(path.join(__dirname, '../src/views', file), 'utf8'))
  const compiled = compiler.compile(component.template.content)
  check(compiled.errors.length === 0, `${file}: template compilation`)
  const sandbox = { module: {}, window: { innerHeight: 1000, location: { origin: 'http://localhost' } }, location: { protocol: 'http:' }, Date, console, setTimeout, clearTimeout, setInterval, clearInterval, atob, ...additions }
  vm.runInNewContext(component.script.content.replace(/^import .*$/gm, '').replace('export default', 'module.exports ='), sandbox, { filename: file })
  const definition = sandbox.module.exports
  const instance = { $refs: {}, $emit() {}, $nextTick(fn) { fn() }, ...definition.data() }
  for (const [key, fn] of Object.entries(definition.methods || {})) instance[key] = fn.bind(instance)
  for (const [key, fn] of Object.entries(definition.computed || {})) Object.defineProperty(instance, key, { get: () => fn.call(instance) })
  return { instance, definition }
}
async function run() {
  const sockets = [], timers = []
  let token = `header.${Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) - 1 })).toString('base64url')}.sig`
  const { instance: log, definition: logDefinition } = load('operations/showLog.vue', {
    logViewer: {}, stripAnsi: value => value, request: async () => '', getToken: () => token,
    WebSocket: function (url, protocol) { this.url = url; this.protocol = protocol; this.close = () => { this.closed = true }; sockets.push(this) },
    setTimeout: (fn, delay) => { const timer = { fn, delay }; timers.push(timer); return timer }, clearTimeout: timer => { if (timer) timer.cancelled = true }
  })
  log.remoteUrl = 'ws://localhost/channel/log'
  log.connect(); check(sockets.length === 0 && log.connectionState === '请重新登录', 'expired token does not open WebSocket')
  token = `header.${Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url')}.sig`
  log.connect(); const socket = sockets[0]
  check(socket.protocol === token, 'token travels in WebSocket protocol header')
  socket.onopen(); check(log.connectionState === '已连接', 'connected status')
  socket.onmessage({ data: Array.from({ length: 2005 }, (_, i) => `line-${i}`).join('\n') })
  check(log.data.length === 2000 && log.data[0] === 'line-5', 'live log retention is bounded')
  socket.onclose({ code: 1006 }); check(timers[0].delay === 2000, 'initial reconnect backoff')
  logDefinition.beforeDestroy.call(log); check(timers[0].cancelled && log.disposed, 'disposing viewer cancels reconnect')
  log.connect(); check(sockets.length === 1, 'disposed viewer never reconnects')

  let selectedTasks = [], sent = [], plays = 0, attached = 0
  const { instance: player, definition: playerDefinition } = load('common/playerTabs.vue', {
    jessibucaPlayer: {}, rtcPlayer: {}, h265web: {}, getAutoPlayTasks: () => Promise.resolve(selectedTasks)
  })
  player.streamInfo = { app: 'rtp', stream: 'camera' }; player.streamAttachedAt = Date.now()
  player.$store = { dispatch: async () => { plays++; return { app: 'rtp', stream: 'camera' } } }
  player.setStreamInfo = () => { attached++; player.recoveryNeeded = false }
  selectedTasks = [{ enabled: true, status: 'PLAYING', app: 'rtp', streamId: 'camera', channelId: 145, lastSuccessTime: player.streamAttachedAt - 1 }]
  await player.recoverPreview(); check(plays === 0, 'healthy unchanged source is not reopened')
  player.recoveryNeeded = true; await player.recoverPreview(); check(plays === 1 && attached === 1, 'selected recovered source can reconnect preview')
  player.manualPaused = true; player.recoveryNeeded = true; await player.recoverPreview(); check(plays === 1, 'manual pause suppresses reconnect')
  player.manualPaused = false; const pending = player.recoverPreview(); player.manualPaused = true; await pending
  check(plays === 1, 'pause while task query is in flight prevents new play request')
  player.manualPaused = false; let resolvePlay
  player.$store.dispatch = () => { plays++; return new Promise(resolve => { resolvePlay = resolve }) }
  const inFlight = player.recoverPreview(); await new Promise(resolve => setImmediate(resolve)); player.manualPaused = true
  resolvePlay({ app: 'rtp', stream: 'camera' }); await inFlight; check(attached === 1, 'late response does not reopen paused preview')
  playerDefinition.beforeDestroy.call(player); check(player.disposed, 'preview lifecycle stops polling')

  const { instance: dialog } = load('channel/autoPlay.vue', {
    autoPlayStates: { PAUSED: '已暂停' }, getAutoPlayTasks: async () => [], setAutoPlayTasks: async (...args) => { sent.push(args) }
  })
  dialog.$message = { success() {} }; dialog.selectedChannels = [{ gbId: 145 }, { gbId: 200 }]
  check(dialog.interval === 60, 'default interval is sixty seconds')
  dialog.batch(true); await new Promise(resolve => setImmediate(resolve))
  check(JSON.stringify(sent[0]) === '[[145,200],true,60]', 'only selected common channel IDs are enabled')
  dialog.toggle({ channelId: 145, enabled: true, checkIntervalSeconds: 60 }); await new Promise(resolve => setImmediate(resolve))
  check(JSON.stringify(sent[1]) === '[[145],false,60]', 'configured task can be paused')

  for (const name of ['jessibuca.vue', 'h265web.vue']) {
    const source = fs.readFileSync(path.join(__dirname, '../src/views/common', name), 'utf8')
    check(source.includes('@click="stopByUser"') && source.includes("stopByUser() { this.$emit('manualPause')"), `${name}: stop reports user intent`)
  }
  console.log(JSON.stringify({ passed: true, assertions }))
}
run().catch(error => { console.error(error); process.exitCode = 1 })
