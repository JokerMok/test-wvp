/* Regression for the real channel toolbar, login role and page-refresh guard. */
const fs = require('fs')
const path = require('path')
const vm = require('vm')
const assert = require('assert/strict')
const Vue = require('vue')
const compiler = require('vue-template-compiler')
let assertions = 0
function check(value, message) { assert.ok(value, message); assertions++ }
function evaluate(source, additions = {}) {
  const code = source.replace(/^import[\s\S]*?from ['"][^'"]+['"];?[ \t]*$/gm, '')
    .replace(/^import\s+['"][^'"]+['"];?[ \t]*$/gm, '').replace('export default', 'module.exports =')
  const context = { module: {}, window: { innerHeight: 900 }, ...additions }
  vm.runInNewContext(code, context)
  return context.module.exports
}
const read = file => fs.readFileSync(path.join(__dirname, '../src', file), 'utf8')
async function run() {
  let profileCalls = 0
  const profile = { role: { authority: '0' } }
  const noop = () => {}
  const user = evaluate(read('store/modules/user.js'), {
    crypto: require('crypto'), getToken: () => 'synthetic-token', getDefaultPassword: () => false,
    setToken: noop, setName: noop, setServerId: noop, setDefaultPassword: noop,
    login: async () => ({ data: { accessToken: 'synthetic-token', username: 'test-user', role: { authority: '0' } } }),
    getUserInfo: async () => { profileCalls++; return { data: profile } }
  })
  const getters = evaluate(read('store/getters.js'))
  const state = user.state
  const commit = (type, value) => user.mutations[type.replace(/^user\//, '')](state, value)
  const store = { state: { user: state }, getters: {}, commit,
    dispatch: name => user.actions[name.replace(/^user\//, '')]({ commit, state }) }
  for (const key of ['authority', 'name']) Object.defineProperty(store.getters, key, { get: () => getters[key](store.state) })
  const component = compiler.parseComponent(read('views/channel/index.vue'))
  const dependencies = { autoPlayStates: {} }
  for (const match of component.script.content.matchAll(/^import (\w+) from /gm)) dependencies[match[1]] = {}
  const definition = evaluate(component.script.content, dependencies)
  const compiled = compiler.compileToFunctions(component.template.content)
  function toolbarButton() {
    const view = new Vue({ data: definition.data, methods: definition.methods, render: compiled.render, staticRenderFns: compiled.staticRenderFns,
      beforeCreate() { this.$store = store; this.$channelTypeList = {} } })
    let opened = false
    view.$refs.autoPlayDialog = { open: () => { opened = true } }
    function find(node) {
      if (!node) return null
      if (node.tag === 'el-button' && (node.children || []).some(child => child.text === '自动续播')) return node
      for (const child of node.children || []) { const match = find(child); if (match) return match }
      return null
    }
    return { button: find(view._render()), opened: () => opened }
  }
  check(store.getters.authority === null && !toolbarButton().button, 'unknown role does not reveal administrator controls')
  await user.actions.login({ commit }, { username: 'test-user', password: 'synthetic-password' })
  check(store.getters.authority === 0, 'login stores numeric authority from the string role')
  const admin = toolbarButton();check(!!admin.button, 'real channel toolbar renders automatic-play entry for admin')
  admin.button.data.on.click();check(admin.opened(), 'toolbar entry invokes its dialog')
  for (const authority of ['1', 2, null, undefined, '', false]) {
    commit('SET_AUTHORITY', authority)
    check(!toolbarButton().button, 'non-administrator or missing authority does not reveal entry')
  }
  let guard
  evaluate(read('permission.js'), {
    router: { beforeEach: fn => { guard = fn }, afterEach: noop }, store,
    NProgress: { configure: noop, start: noop, done: noop }, getToken: () => 'synthetic-token',
    getName: () => 'test-user', getServerId: () => 'server', getDefaultPassword: () => false,
    getPageTitle: () => 'channels', document: {}
  })
  commit('RESET_STATE');let navigated = 0
  await guard({ path: '/channel', meta: {} }, {}, () => { navigated++ })
  check(navigated === 1 && profileCalls === 1, 'refresh guard requests the current profile once')
  check(store.getters.authority === 0 && !!toolbarButton().button, 'page refresh restores the administrator entry')
  profile.role = { authority: null };commit('SET_AUTHORITY', null)
  await store.dispatch('user/getUserInfo')
  check(store.getters.authority === null && !toolbarButton().button, 'profile with incomplete role fails closed')
  commit('SET_AUTHORITY', 0);commit('RESET_STATE')
  check(store.getters.authority === null, 'clearing a login also clears its authority')
  let lastRequest
  const apiSource = read('api/autoPlay.js').replace(/^import .*$/gm, '').replace(/^export /gm, '') + '\nmodule.exports = { getAutoPlayTasks, setAutoPlayTasks }'
  const api = evaluate(apiSource, { request: async options => {
    lastRequest = options
    return { code: 0, data: options.method === 'get' ? [{ channelId: 145 }] : { success: true } }
  } })
  const tasks = await api.getAutoPlayTasks()
  check(Array.isArray(tasks) && tasks[0].channelId === 145, 'task list unwraps the actual WVP response envelope')
  const saved = await api.setAutoPlayTasks([145], false)
  check(saved.success && lastRequest.data.checkIntervalSeconds === 60, 'saving unwraps the result and keeps the default interval')
  const denied = evaluate(apiSource, { request: async () => undefined })
  await assert.rejects(denied.setAutoPlayTasks([145], true));assertions++
  console.log(JSON.stringify({ passed: true, assertions }))
}
run().catch(error => { console.error(error); process.exitCode = 1 })
