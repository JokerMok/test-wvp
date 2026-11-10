const fs = require('fs')
const path = require('path')
const vm = require('vm')
const assert = require('assert/strict')
const Vue = require('vue')
const compiler = require('vue-template-compiler')
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
let assertions = 0
const check = (value, message) => { assert.ok(value, message); assertions++ }
function evaluate(code, globals) {
  const context = { module: {}, window: { innerHeight: 900 }, ...globals }
  vm.runInNewContext(code.replace(/^import[^\n]*\n/gm, '').replace('export default', 'module.exports ='), context)
  return context.module.exports
}
async function run() {
  let snapshots = [{ channelId: 18, state: 'PLAYING', canStop: true, autoPlayConfigured: true, autoPlayEnabled: true }]
  const timers = new Map();let nextTimer = 0
  let fetch = async () => snapshots
  const mixin = evaluate(read('mixins/channelPlayback.js'), {
    getChannelPlayStatus: ids => fetch(ids), playbackStates: { PLAYING: '收流中', STOPPED: '未收流', UNKNOWN: '状态未知' },
    document: { visibilityState: 'visible' }, setInterval: fn => { timers.set(++nextTimer, fn);return nextTimer }, clearInterval: id => timers.delete(id)
  })
  function page(file, device) {
    const component = compiler.parseComponent(read(file));const dependencies = { channelPlayback: mixin }
    for (const match of component.script.content.matchAll(/^import (\w+) from /gm)) if (!(match[1] in dependencies)) dependencies[match[1]] = {}
    const definition = evaluate(component.script.content, dependencies)
    const compiled = compiler.compileToFunctions(component.template.content)
    const view = new Vue({ data: definition.data, computed: definition.computed, methods: definition.methods, mixins: [mixin],
      render: compiled.render, staticRenderFns: compiled.staticRenderFns,
      beforeCreate() { this.$store = { getters: { authority: 0 } };this.$channelTypeList = {} }
    })
    const row = device ? { id: 18, deviceId: 'camera', status: 'ON', streamId: null } : { gbId: 18, gbDeviceId: 'camera', gbStatus: 'ON', streamId: null }
    view[device ? 'deviceChannelList' : 'channelList'] = [row]
    if (device) view.device = { online: 1 }
    function column(label) {
      function find(node) {
        if (!node) return null
        if (node.tag === 'el-table-column' && node.data.attrs.label === label) return node
        for (const child of node.children || []) { const result = find(child);if (result) return result }
      }
      return find(view._render()).data.scopedSlots.default({ row })
    }
    return { view, row, column }
  }
  const common = page('views/channel/index.vue', false)
  const device = page('views/device/channel/index.vue', true)
  await Vue.nextTick();await common.view.refreshPlaybackStatus();await device.view.refreshPlaybackStatus();await Vue.nextTick()
  function nodes(value) { return Array.isArray(value) ? value : [value] }
  function text(nodesToRead) { return nodes(nodesToRead).map(node => node.text || (node.children ? text(node.children) : '')).join('') }
  function hasStop(nodesToRead) { return nodes(nodesToRead).some(node => node.tag === 'el-button' && text(node).includes('停止')) }
  for (const item of [common,device]) {
    check(text(item.column('播放状态')).includes('收流中'), 'both real templates render the shared live media state')
    check(text(item.column('自动续播')).includes('已开启'), 'automatic configuration is separate from playback health')
    check(hasStop(item.column('操作')), 'enabled task or live media can be stopped from either page without a stored stream ID')
  }
  snapshots = [{ channelId: 18, state: 'STOPPED', canStop: true, autoPlayConfigured: true, autoPlayEnabled: true }]
  await common.view.refreshPlaybackStatus();await device.view.refreshPlaybackStatus()
  check(text(common.column('播放状态')) === text(device.column('播放状态')) && text(common.column('播放状态')).includes('未收流'), 'successful empty media updates both pages independently of an enabled task')
  snapshots = [{ channelId: 18, state: 'STOPPED', canStop: false, autoPlayConfigured: true, autoPlayEnabled: false }]
  common.row.streamId = 'stale-page-cache'
  await common.view.refreshPlaybackStatus();await device.view.refreshPlaybackStatus()
  check(!hasStop(common.column('操作')) && !hasStop(device.column('操作')), 'fresh server state overrides a stale page stream ID for both stop controls')
  check(common.view.autoPlaybackLabel(18) === '已暂停' && device.view.autoPlaybackLabel(18) === '已暂停', 'manual pause is consistent on both pages')
  let finishOld
  fetch = () => new Promise(resolve => { finishOld = resolve })
  const oldRequest = common.view.refreshPlaybackStatus()
  const finishPrevious = finishOld
  common.view.channelList = [{ gbId: 19 }];await Vue.nextTick()
  const finishNew = finishOld
  finishNew([{ channelId: 19, state: 'STOPPED' }]);await Promise.resolve();await Promise.resolve()
  finishPrevious([{ channelId: 18, state: 'PLAYING' }]);await oldRequest
  check(common.view.playbackState(19).state === 'STOPPED' && !common.view.playbackStatuses[18], 'a previous page response cannot replace the current channel set')
  // Stop/start invalidates pending data on page switches, even if its response arrives later.
  let late
  common.view.stopPlaybackPolling();fetch = () => new Promise(resolve => { late = resolve })
  common.view.startPlaybackPolling();const sequence = common.view.playbackSequence
  common.view.stopPlaybackPolling();late([{ channelId: 19, state: 'PLAYING' }]);await Promise.resolve();await Promise.resolve()
  check(common.view.playbackSequence > sequence && common.view.playbackState(19).state !== 'PLAYING', 'late response cannot update a deactivated page')
  check(timers.size === 0, 'deactivation removes the refresh timer')
  fetch = async () => { throw new Error('synthetic unavailable API') }
  device.view.playbackStatuses = { 18: { state: 'PLAYING', canStop: true, autoPlayConfigured: true, autoPlayEnabled: true } }
  await device.view.refreshPlaybackStatus()
  check(device.view.playbackState(18).state === 'UNKNOWN' && device.view.canStopPlayback(18), 'query failure shows unknown and keeps manual stop available')
  common.view.$destroy();device.view.$destroy()
  console.log(JSON.stringify({ passed: true, assertions }))
}
run().catch(error => { console.error(error);process.exit(1) })
