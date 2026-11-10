<template>
  <div class="log-panel" style="height:100%">
    <el-form :inline="true" size="mini">
      <el-form-item label="过滤"><el-input v-model="filter" placeholder="过滤关键字" style="width:20vw" /></el-form-item>
      <el-form-item v-if="remoteUrl"><el-tag :type="connectionState === '已连接' ? 'success' : 'info'">{{ connectionState }}</el-tag></el-form-item>
      <el-form-item v-if="remoteUrl"><el-button @click="reconnect">重新连接</el-button></el-form-item>
      <el-form-item style="float:right"><el-button icon="el-icon-download" @click="downloadFile">下载</el-button></el-form-item>
    </el-form>
    <log-viewer :log="logData" :auto-scroll="true" :height="winHeight" style="height:calc(100% - 60px)" />
  </div>
</template>
<script>
import moment from 'moment/moment'
import logViewer from '@femessage/log-viewer'
import stripAnsi from 'strip-ansi'
import request from '@/utils/request'
import { getToken } from '@/utils/auth'
export default {
  name: 'Log', components: { logViewer }, props: ['fileUrl', 'remoteUrl', 'loadEnd'],
  data() { return { data: [], filter: '', websocket: null, retryTimer: null, retryCount: 0, disposed: false, generation: 0, connectionState: '未连接', winHeight: window.innerHeight - 100 } },
  computed: { logData() { return this.data.filter(line => !this.filter || line.includes(this.filter)).join('\r\n') } },
  watch: { remoteUrl() { this.initData() }, fileUrl() { this.initData() } },
  created() { this.initData() },
  beforeDestroy() { this.disposed = true; this.disconnect() },
  methods: {
    disconnect() {
      this.generation++
      clearTimeout(this.retryTimer)
      if (this.websocket) { this.websocket.onclose = null; this.websocket.onmessage = null; this.websocket.onerror = null; this.websocket.close(); this.websocket = null }
    },
    initData() {
      this.disconnect(); this.data = []
      if (this.fileUrl) {
        const generation = this.generation
        request({ method: 'get', url: this.fileUrl }).then(res => {
          if (this.disposed || generation !== this.generation) return
          this.data = String(res).split('\n')
          if (typeof this.loadEnd === 'function') this.loadEnd()
        }).catch(() => { this.connectionState = '日志读取失败' })
      } else if (this.remoteUrl) this.connect()
    },
    connect() {
      if (this.disposed || !this.remoteUrl) return
      const token = getToken()
      if (!token) { this.connectionState = '请重新登录'; return }
      try {
        const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
        if (payload.exp && payload.exp * 1000 <= Date.now()) { this.connectionState = '请重新登录'; return }
      } catch (error) { this.connectionState = '登录凭证无效，请重新登录'; return }
      const generation = this.generation
      this.connectionState = '连接中'
      let socket
      try { socket = new WebSocket(this.remoteUrl, token) } catch (error) { this.connectionState = '日志连接失败，请重新登录'; return }
      this.websocket = socket
      socket.onopen = () => { if (generation !== this.generation || this.disposed) return; this.retryCount = 0; this.connectionState = '已连接' }
      socket.onmessage = event => {
        if (generation !== this.generation || this.disposed) return
        this.data.push(...String(event.data).split('\n'))
        if (this.data.length > 2000) this.data.splice(0, this.data.length - 2000)
      }
      socket.onerror = () => { if (generation === this.generation) this.connectionState = '连接异常，检查登录状态及日志权限' }
      socket.onclose = event => {
        if (this.disposed || generation !== this.generation) return
        this.websocket = null
        if (event.code === 1008 || !getToken()) { this.connectionState = '请重新登录'; return }
        this.connectionState = '连接断开，等待重连'
        const delay = [2000, 5000, 10000, 30000][Math.min(this.retryCount++, 3)]
        this.retryTimer = setTimeout(() => this.connect(), delay)
      }
    },
    reconnect() { this.disconnect(); this.retryCount = 0; this.connect() },
    getLogData() { return this.logData },
    getLogDataWithOutAnsi() { return stripAnsi(this.logData) },
    downloadFile() {
      const url = URL.createObjectURL(new Blob([this.getLogDataWithOutAnsi()], { type: 'text/plain;charset=utf-8' }))
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'wvp-' + moment().format('YYYY-MM-DD-HH-mm-ss') + '.log'; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    }
  }
}
</script>
